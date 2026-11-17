package org.kirikick.spike.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.hibernate.Session;
import org.hibernate.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.kirikick.api.ApiApplication;
import org.kirikick.api.auth.domain.SignupApplication;
import org.kirikick.api.support.JpaSliceTest;
import org.kirikick.api.user.domain.User;
import org.kirikick.spike.n1.N1JpaTestConfiguration;
import org.kirikick.spike.n1.game.domain.Game;
import org.kirikick.spike.n1.game.service.GameAssemblyService;
import org.kirikick.spike.n1.reward.domain.RewardPreset;
import org.kirikick.spike.n1.room.domain.Room;
import org.kirikick.spike.publication.domain.PublicationSpikeRecord;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Test-only protocol, not a publication implementation or production time guarantee. */
@JpaSliceTest
@ContextConfiguration(classes = {ApiApplication.class, N1JpaTestConfiguration.class})
@ActiveProfiles("test")
@EntityScan(
    basePackageClasses = {
      User.class,
      SignupApplication.class,
      Room.class,
      Game.class,
      RewardPreset.class,
      PublicationSpikeRecord.class
    })
@Import({GameAssemblyService.class, PublicationSweepSpikePostgresTest.ClockConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PublicationSweepSpikePostgresTest {
  private static final String SCHEMA =
      "publication_sweep_" + UUID.randomUUID().toString().replace("-", "");
  private static final Instant NOW = Instant.parse("2026-11-17T09:00:00Z");
  private static final Duration GRACE = Duration.ofSeconds(1);
  private static final Set<Session> SEEN = Collections.newSetFromMap(new IdentityHashMap<>());
  private static int completedStage;
  private final Map<Thread, Session> activeSessions = new HashMap<>();
  private final Map<Thread, Long> activePids = new HashMap<>();

  @Autowired private EntityManager em;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager manager;
  @Autowired private Flyway flyway;
  @Autowired private GameAssemblyService assembly;

  @DynamicPropertySource
  static void migratedSchema(DynamicPropertyRegistry registry) throws SQLException {
    var container = PostgresTestDatabase.container();
    assertThat(container.isRunning()).isTrue();
    assertThat(container.getContainerId()).isNotBlank();
    // Verify this JVM's disposable container before Flyway or application connections.
    try (var connection =
            DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
        var statement = connection.createStatement();
        var rows = statement.executeQuery("select current_database(), version()")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString(1)).isEqualTo(container.getDatabaseName());
      assertThat(rows.getString(2)).startsWith("PostgreSQL 17.");
      System.out.printf(
          "SPIKE IDENTITY container=%s image=%s imageId=%s testcontainers=%s database=%s version=%s schema=%s%n",
          container.getContainerId(),
          container.getDockerImageName(),
          container.getContainerInfo().getImageId(),
          container.getClass().getProtectionDomain().getCodeSource().getLocation().getFile(),
          rows.getString(1),
          rows.getString(2),
          SCHEMA);
      try (var check =
          connection.prepareStatement("select count(*) from pg_namespace where nspname=?")) {
        check.setString(1, SCHEMA);
        try (var existing = check.executeQuery()) {
          assertThat(existing.next()).isTrue();
          assertThat(existing.getInt(1)).as("new isolated schema before Flyway").isZero();
        }
      }
      System.out.printf("SWEEP PREFLYWAY schema=%s preexisting=false%n", SCHEMA);
    }
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.schemas", () -> SCHEMA);
    registry.add("spring.flyway.default-schema", () -> SCHEMA);
    registry.add(
        "spring.flyway.locations",
        () -> "classpath:db/migration,classpath:db/spike/n1,classpath:db/spike/publication");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    registry.add(
        "spring.datasource.hikari.connection-init-sql", () -> "set search_path to " + SCHEMA);
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> 6);
    registry.add("spring.jpa.show-sql", () -> false);
  }

  @Test
  @Order(1)
  @DisplayName("S1/S3-C: 종료 commit 전후 fresh scalar와 관리 Room 캐시를 구분한다")
  void readsCommittedEndWithoutConfusingManagedCache() throws Exception {
    environment();
    Instant opened = tx("DB fixture clock", this::dbNow);
    AtomicReference<Instant> end = new AtomicReference<>();
    Seed seed = seed(opened, null, "SUCCEEDED", opened.minusSeconds(1));
    Map<String, Object> before = tx("record baseline", () -> row(seed.id()));
    CountDownLatch updated = new CountDownLatch(1);
    CountDownLatch allowCommit = new CountDownLatch(1);
    CountDownLatch committed = new CountDownLatch(1);
    ExecutorService workers = Executors.newSingleThreadExecutor();
    Future<?>[] writer = new Future<?>[1];
    try {
      tx(
          "R record lock only; no User lock",
          () -> {
            Room managed = em.find(Game.class, seed.game()).getRoom();
            assertThat(managed.getConfirmedEarlyEndAt()).isNull();
            assertThat(managed.getClosesAt()).isEqualTo(opened.plus(Duration.ofHours(24)));
            lock(seed.id());
            writer[0] =
                workers.submit(() -> {
                  tx(
                      "E User then Room UPDATE",
                      () -> {
                        end.set(dbNow());
                        jdbc.queryForObject(
                            "select id from users where id=? for update", Long.class, seed.user());
                        assertThat(
                                jdbc.update(
                                    "update rooms set confirmed_early_end_at=? where id=?",
                                    timestamp(end.get()),
                                    seed.room()))
                            .isEqualTo(1);
                        updated.countDown();
                        await(allowCommit);
                        return null;
                      });
                  System.out.println("SWEEP ORDER E transaction returned; commit confirmed");
                  committed.countDown();
                });
            await(updated);
            assertThat(freshEnd(seed.game())).isNull();
            assertThat(jpaEnd(seed.game())).isNull();
            System.out.println("SWEEP S1 uncommitted E invisible to R fresh JDBC/JPA scalar");
            allowCommit.countDown();
            await(committed);
            assertThat(freshEnd(seed.game())).isEqualTo(end.get());
            assertThat(jpaEnd(seed.game())).isEqualTo(end.get());
            Room cached = em.find(Game.class, seed.game()).getRoom();
            assertThat(cached).isSameAs(managed);
            assertThat(cached.getConfirmedEarlyEndAt()).isNull();
            trace("R after E commit; same tx/context, new statements");
            System.out.printf(
                "SWEEP S1 fresh=%s managed=null sameInstance=true session=%d%n",
                end.get(), System.identityHashCode(currentSession()));
            return null;
          });
      writer[0].get(15, TimeUnit.SECONDS);
    } finally {
      allowCommit.countDown();
      terminate(workers, writer[0]);
    }
    tx(
        "S1 fresh observer after R/E transactions",
        () -> {
          Room room = em.find(Game.class, seed.game()).getRoom();
          assertThat(room.getConfirmedEarlyEndAt()).isEqualTo(end.get());
          assertThat(room.getClosesAt()).isEqualTo(opened.plus(Duration.ofHours(24)));
          assertThat(row(seed.id())).isEqualTo(before);
          business(seed);
          return null;
        });
    completedStage = 1;
    System.out.println("SWEEP RESULT S1/S3-C SUPPORTED (mapped cache vs fresh scalar/JDBC only)");
  }

  @Test
  @Order(2)
  @DisplayName("S2/S3-E: 만료 전·정각·후와 물리 존재를 분리한다")
  void distinguishesLogicalExpiryFromPhysicalDeletion() {
    requireStage(1);
    Instant base = tx("S2 DB fixture clock", this::dbNow);
    for (Duration grace : List.of(Duration.ZERO, GRACE)) {
      for (int mode = 0; mode < 3; mode++) {
        Instant opened = base.minus(Duration.ofHours(2));
        Instant close = opened.plus(Duration.ofHours(24));
        Instant early =
            mode == 0 ? null : mode == 1 ? base.minusSeconds(60) : close.plusSeconds(60);
        Seed seed = seed(opened, early, "SUCCEEDED", base.minus(Duration.ofDays(1)));
        Instant oracle =
            (early != null && early.isBefore(close) ? early : close)
                .plus(Duration.ofMinutes(30))
                .plus(grace);
        Instant roundTrip =
            tx(
                "S2 timestamp precision roundtrip",
                () ->
                    jdbc.queryForObject(
                            "select cast(? as timestamptz)", Timestamp.class, timestamp(oracle))
                        .toInstant());
        assertThat(roundTrip).isEqualTo(oracle);
        for (long micros : List.of(-1L, 0L, 1L)) {
          Instant input = roundTrip.plusNanos(micros * 1000);
          Decision decision =
              tx(
                  "S2 locked logical decision; injected internal time",
                  () -> {
                    lock(seed.id());
                    return decide(seed.id(), input, grace);
                  });
          assertThat(decision.expiry()).isEqualTo(oracle);
          assertThat(decision.expired()).isEqualTo(micros > 0);
          assertThat(!input.isBefore(decision.expiry())).isEqualTo(micros >= 0);
          tx(
              "S2 fresh physical observer; no sweep yet",
              () -> {
                assertThat(row(seed.id())).isNotEmpty();
                business(seed);
                return null;
              });
          System.out.printf(
              "SWEEP S2 mode=%d grace=%s deltaMicros=%d expiry=%s input=%s DBobserved=%s strictAfter=%s inclusive=%s%n",
              mode,
              grace,
              micros,
              oracle,
              input,
              decision.observed(),
              decision.expired(),
              !input.isBefore(oracle));
        }
      }
    }
    Seed expired =
        seed(base.minus(Duration.ofHours(27)), null, "SUCCEEDED", base.plus(Duration.ofDays(1)));
    Seed live = seed(base, null, "SUCCEEDED", base.minus(Duration.ofDays(1)));
    assertThat(
            tx(
                "S2 actual post-lock DB time sweep",
                () -> sweep(List.of(expired.id(), live.id()), false)))
        .containsExactly(expired.id());
    observe(List.of(expired, live), List.of(expired.id()));
    // shortcut: arithmetic example only; commit-ack latency and client minimum retention need a
    // later experiment.
    Instant decisionAt = base.minus(Duration.ofMinutes(2));
    Instant pretendCommitAt = decisionAt.plusSeconds(65);
    assertThat(
            Duration.between(pretendCommitAt, decisionAt.plus(Duration.ofMinutes(30)).plus(GRACE)))
        .isLessThan(Duration.ofMinutes(30));
    System.out.println(
        "SWEEP RESULT S2/S3-E SUPPORTED off-boundary; equality product contract INCONCLUSIVE; g_test=0/1s, delta=1us, sweep candidate uses > not >=");
    completedStage = 2;
  }

  @Test
  @Order(3)
  @DisplayName("S3/S3-A: row lock holder가 살아 있는 동안 SKIP LOCKED가 다른 만료 행을 삭제한다")
  void skipsLockedRecordAndCommitsIndependentRecord() throws Exception {
    requireStage(2);
    Instant base = tx("S3 DB fixture clock", this::dbNow);
    Seed x = seed(base.minus(Duration.ofHours(27)), null, "SUCCEEDED", base);
    Seed y = seed(base.minus(Duration.ofHours(27)), null, "SUCCEEDED", base);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<?> holder =
        workers.submit(() ->
            tx(
                "H holds X record row lock",
                () -> {
                  lock(x.id());
                  locked.countDown();
                  await(release);
                  return null;
                }));
    Future<List<UUID>> sweeper = null;
    try {
      await(locked);
      sweeper =
          workers.submit(() ->
              tx("W skip locked bounded candidates", () -> sweep(List.of(x.id(), y.id()), false)));
      assertThat(sweeper.get(8, TimeUnit.SECONDS)).containsExactly(y.id());
      assertThat(holder.isDone()).isFalse();
      observe(List.of(x, y), List.of(y.id()));
      assertThat(holder.isDone()).isFalse();
      System.out.println(
          "SWEEP S3 W committed and fresh O confirmed X retained/Y deleted BEFORE H release");
      release.countDown();
      holder.get(15, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      if (sweeper != null) sweeper.cancel(true);
      terminate(workers, holder);
    }
    assertThat(tx("S3 manual subsequent pass after H commit", () -> sweep(List.of(x.id()), false)))
        .containsExactly(x.id());
    observe(List.of(x, y), List.of(x.id(), y.id()));
    System.out.println(
        "SWEEP RESULT S3/S3-A SUPPORTED (row lock skip, not an all-waits/SLO guarantee)");
    completedStage = 3;
  }

  @Test
  @Order(4)
  @DisplayName("S4/S3-B: stale 후보를 락 후 재판정하고 금지 행 보존·시험 batch rollback을 확인한다")
  void rechecksAfterLockAndRollsBackTrialBatch() throws Exception {
    requireStage(3);
    Instant base = tx("S4 DB fixture clock", this::dbNow);
    Instant past = base.minus(Duration.ofHours(2));
    Instant future = base.plus(Duration.ofHours(2));
    Instant oldOpen = base.minus(Duration.ofHours(27));
    Seed processing = seed(oldOpen, null, "PROCESSING", past);
    Seed changedState = seed(oldOpen, null, "SUCCEEDED", past);
    Seed changedEnd = seed(base.minus(Duration.ofHours(3)), null, "SUCCEEDED", future);
    Seed live = seed(base, null, "SUCCEEDED", past);
    Seed expired = seed(oldOpen, null, "SUCCEEDED", future);
    Seed noGame = seed(oldOpen, null, "SUCCEEDED", past);
    Seed unopened = seed(oldOpen, null, "SUCCEEDED", past);
    Seed failedBlockedOnly = seed(oldOpen, null, "FAILED", base.plus(Duration.ofMinutes(15)));
    Seed failedExpired = seed(oldOpen, null, "FAILED", past);
    Seed retryLive = seed(oldOpen, null, "RETRYABLE", base.plus(Duration.ofMinutes(15)));
    Seed retryExpired = seed(oldOpen, null, "RETRYABLE", past);
    List<Seed> seeds =
        List.of(
            processing,
            changedState,
            changedEnd,
            live,
            expired,
            noGame,
            unopened,
            failedBlockedOnly,
            failedExpired,
            retryLive,
            retryExpired);
    List<UUID> ids = seeds.stream().map(Seed::id).toList();
    tx(
        "S4 nullable guard fixtures and FAILED block-only",
        () -> {
          assertThat(
                  jdbc.update(
                      "update publication_spike_records set game_id=null where id=?", noGame.id()))
              .isEqualTo(1);
          assertThat(jdbc.update("update rooms set opened_at=null where id=?", unopened.room()))
              .isEqualTo(1);
          assertThat(
                  jdbc.queryForObject(
                      "select blocked_until<clock_timestamp() and expires_at>clock_timestamp() from publication_spike_records where id=?",
                      Boolean.class,
                      failedBlockedOnly.id()))
              .isTrue();
          return null;
        });
    CountDownLatch collected = new CountDownLatch(1);
    CountDownLatch modified = new CountDownLatch(1);
    ExecutorService workers = Executors.newSingleThreadExecutor();
    Future<List<UUID>> worker =
        workers.submit(() -> {
          List<UUID> advisory =
              tx(
                  "W unlocked advisory IDs only; no production candidate filter",
                  () ->
                      jdbc.queryForList(
                          "select id from publication_spike_records where id in ("
                              + placeholders(ids)
                              + ")",
                          UUID.class,
                          ids.toArray()));
          assertThat(advisory).containsExactlyInAnyOrderElementsOf(ids);
          collected.countDown();
          await(modified);
          return tx(
              "W new transaction locks then rechecks current state/end/time",
              () -> sweep(advisory, false));
        });
    Map<UUID, Map<String, Object>> expected = new HashMap<>();
    List<UUID> deleted;
    try {
      await(collected);
      tx(
          "M fixture race mutation commits before W locks",
          () -> {
            assertThat(
                    jdbc.update(
                        "update publication_spike_records set state='PROCESSING',attempt=2,terminal_at=null where id=?",
                        changedState.id()))
                .isEqualTo(1);
            assertThat(
                    jdbc.update(
                        "update rooms set confirmed_early_end_at=? where id=?",
                        timestamp(past),
                        changedEnd.room()))
                .isEqualTo(1);
            return null;
          });
      tx(
          "S4 observer of committed M before W resume",
          () -> {
            for (Seed seed : seeds) expected.put(seed.id(), row(seed.id()));
            return null;
          });
      modified.countDown();
      deleted = worker.get(15, TimeUnit.SECONDS);
    } finally {
      modified.countDown();
      terminate(workers, worker);
    }
    assertThat(deleted)
        .containsExactlyInAnyOrder(
            changedEnd.id(), expired.id(), failedExpired.id(), retryExpired.id());
    observe(seeds, deleted);
    tx(
        "S4 fresh full-row preservation observer",
        () -> {
          for (Seed seed : seeds)
            if (!deleted.contains(seed.id()))
              assertThat(row(seed.id())).isEqualTo(expected.get(seed.id()));
          assertThat(row(changedState.id()))
              .containsEntry("state", "PROCESSING")
              .containsEntry("attempt", 2L);
          return null;
        });

    Seed a = seed(oldOpen, null, "SUCCEEDED", future);
    Seed b = seed(oldOpen, null, "SUCCEEDED", future);
    Map<UUID, Map<String, Object>> originals =
        tx("S4 batch baseline", () -> Map.of(a.id(), row(a.id()), b.id(), row(b.id())));
    AtomicReference<Long> jpaMarker = new AtomicReference<>();
    assertThatThrownBy(() ->
            tx(
                "S4 injected batch error after first DELETE and JPA flush",
                () -> {
                  User marker = User.create("rollback", null);
                  em.persist(marker);
                  em.flush();
                  jpaMarker.set(marker.getId());
                  trace("S4 JPA write flushed before JDBC delete; same transaction");
                  return sweep(List.of(a.id(), b.id()), true);
                }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected after first batch DELETE");
    assertThat(jpaMarker.get()).isNotNull();
    tx(
        "S4 fresh observer confirms entire batch and JPA rollback",
        () -> {
          assertThat(row(a.id())).isEqualTo(originals.get(a.id()));
          assertThat(row(b.id())).isEqualTo(originals.get(b.id()));
          assertThat(
                  jdbc.queryForObject(
                      "select count(*) from users where id=?", Integer.class, jpaMarker.get()))
              .isZero();
          business(a);
          business(b);
          return null;
        });
    assertThat(
            tx(
                "S4 explicit retry of rolled-back trial batch",
                () -> sweep(List.of(a.id(), b.id()), false)))
        .containsExactlyInAnyOrder(a.id(), b.id());
    observe(List.of(a, b), List.of(a.id(), b.id()));
    System.out.println(
        "SWEEP RESULT S4/S3-B SUPPORTED (fixture state mutation, JDBC-only sweep, one-batch tx candidate)");
    completedStage = 4;
  }

  private void environment() throws SQLException {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(manager).isInstanceOf(JpaTransactionManager.class);
    assertThat(AopUtils.isAopProxy(assembly)).isTrue();
    assertThat(em.getEntityManagerFactory().getProperties())
        .containsEntry("hibernate.hbm2ddl.auto", "validate");
    flyway.validate();
    assertThat(flyway.info().pending()).isEmpty();
    assertThat(flyway.info().applied())
        .filteredOn(m -> m.getVersion() != null)
        .extracting(m -> m.getVersion().getVersion())
        .containsExactly("1", "2", "9000", "9001");
    try (var connection = dataSource.getConnection()) {
      assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
      assertThat(connection.getSchema()).isEqualTo(SCHEMA);
      System.out.printf(
          "SWEEP ENV JDK=%s boot=%s hibernate=%s flyway=%s driver=%s manager=%s ddl=validate g_test=%s comparator=strictAfter%n",
          System.getProperty("java.version"),
          SpringBootVersion.getVersion(),
          Version.getVersionString(),
          Flyway.class.getProtectionDomain().getCodeSource().getLocation().getFile(),
          connection.getMetaData().getDriverVersion(),
          manager.getClass().getName(),
          GRACE);
    }
    for (var m : flyway.info().applied())
      System.out.printf(
          "SWEEP MIGRATION version=%s script=%s checksum=%s state=%s%n",
          m.getVersion(), m.getScript(), m.getChecksum(), m.getState());
  }

  private Seed seed(Instant opened, Instant early, String state, Instant copiedExpiry) {
    return tx(
        "seed real N1 assembly and publication fixture",
        () -> {
          User user = User.create("sweep host", null);
          RewardPreset reward = RewardPreset.create("sweep", "test only");
          em.persist(user);
          em.persist(reward);
          em.flush();
          Long game =
              assembly.createRoomWithFirstGame(
                  user.getId(), reward.getId(), 1, "question", List.of("one", "two"), 0);
          em.flush();
          Long room = jdbc.queryForObject("select room_id from games where id=?", Long.class, game);
          assertThat(
                  jdbc.update(
                      "update rooms set opened_at=?,confirmed_early_end_at=? where id=?",
                      timestamp(opened),
                      timestamp(early),
                      room))
              .isEqualTo(1);
          Seed seed = new Seed(UUID.randomUUID(), user.getId(), room, game);
          assertThat(
                  jdbc.update(
                      """
                      insert into publication_spike_records(id,user_id,request_key,state,attempt,started_at,terminal_at,expires_at,game_id)
                      values(?,?,?,?,1,statement_timestamp(),case when ?='PROCESSING' then null else statement_timestamp() end,?,?)
                      """,
                      seed.id(),
                      seed.user(),
                      UUID.randomUUID(),
                      state,
                      state,
                      timestamp(copiedExpiry),
                      game))
              .isEqualTo(1);
          if ("FAILED".equals(state)) {
            assertThat(
                    jdbc.update(
                        "update publication_spike_records set terminal_at=?,blocked_until=? where id=?",
                        timestamp(copiedExpiry.minus(Duration.ofHours(1))),
                        timestamp(copiedExpiry.minus(Duration.ofMinutes(30))),
                        seed.id()))
                .isEqualTo(1);
          } else if ("RETRYABLE".equals(state)) {
            assertThat(
                    jdbc.update(
                        "update publication_spike_records set terminal_at=? where id=?",
                        timestamp(copiedExpiry.minus(Duration.ofMinutes(30))),
                        seed.id()))
                .isEqualTo(1);
          }
          return seed;
        });
  }

  private void lock(UUID id) {
    assertThat(
            jdbc.queryForObject(
                "select id from publication_spike_records where id=? for update", UUID.class, id))
        .isEqualTo(id);
    System.out.printf("SWEEP LOCK id=%s acquiredAt=%s%n", id, dbNow());
  }

  private List<UUID> sweep(List<UUID> advisory, boolean abortAfterFirstDelete) {
    String sql =
        "select id from publication_spike_records where id in ("
            + placeholders(advisory)
            + ") order by id for update skip locked";
    List<UUID> locked = jdbc.queryForList(sql, UUID.class, advisory.toArray());
    System.out.printf("SWEEP SQL %s candidates=%s locked=%s%n", sql, advisory, locked);
    List<UUID> deleted = new ArrayList<>();
    for (UUID id : locked) {
      Decision decision = decide(id, null, GRACE);
      if (!decision.expired()) continue;
      assertThat(jdbc.update("delete from publication_spike_records where id=?", id)).isEqualTo(1);
      deleted.add(id);
      System.out.printf("SWEEP DELETE id=%s%n", id);
      if (abortAfterFirstDelete)
        throw new IllegalStateException("injected after first batch DELETE");
    }
    return deleted;
  }

  private Decision decide(UUID id, Instant injected, Duration grace) {
    Map<String, Object> current = row(id);
    String state = (String) current.get("state");
    String reason = "KNOWN";
    Instant expiry = null;
    Instant close = null;
    Instant early = null;
    if ("PROCESSING".equals(state)) {
      reason = "PROCESSING_PROTECTED";
    } else if ("SUCCEEDED".equals(state)) {
      List<Map<String, Object>> ends =
          jdbc.queryForList(
              """
              select r.opened_at,r.confirmed_early_end_at from games g join rooms r on r.id=g.room_id where g.id=?
              """,
              current.get("game_id"));
      if (ends.isEmpty() || ends.getFirst().get("opened_at") == null) {
        reason = "END_UNKNOWN";
      } else {
        close =
            ((Timestamp) ends.getFirst().get("opened_at")).toInstant().plus(Duration.ofHours(24));
        Timestamp value = (Timestamp) ends.getFirst().get("confirmed_early_end_at");
        early = value == null ? null : value.toInstant();
        expiry =
            (early != null && early.isBefore(close) ? early : close)
                .plus(Duration.ofMinutes(30))
                .plus(grace);
      }
    } else if ("FAILED".equals(state) || "RETRYABLE".equals(state)) {
      Timestamp value = (Timestamp) current.get("expires_at");
      if (value == null) reason = "RETENTION_UNKNOWN";
      else {
        expiry = value.toInstant();
        if ("FAILED".equals(state)) {
          // Fixture invariant from the first spike; malformed failure retention is not a deletion
          // proof.
          assertThat(current.get("blocked_until")).isInstanceOf(Timestamp.class);
          assertThat(expiry)
              .isAfterOrEqualTo(((Timestamp) current.get("blocked_until")).toInstant());
        }
      }
    } else {
      reason = "STATE_UNKNOWN";
    }
    // Read wall clock AFTER locking and the fresh Room statement; transaction_timestamp is not a
    // decision clock.
    Instant observed = dbNow();
    Decision result =
        new Decision(reason, expiry, observed, injected == null ? observed : injected);
    System.out.printf(
        "SWEEP DECISION id=%s state=%s attempt=%s copiedExpires=%s close=%s early=%s grace=%s reason=%s expiry=%s DBobserved=%s input=%s expired=%s%n",
        id,
        state,
        current.get("attempt"),
        current.get("expires_at"),
        close,
        early,
        grace,
        reason,
        expiry,
        observed,
        result.input(),
        result.expired());
    return result;
  }

  private Instant freshEnd(Long game) {
    System.out.println(
        "SWEEP QUERY JDBC select r.confirmed_early_end_at from games g join rooms r on r.id=g.room_id where g.id=?");
    Timestamp value =
        jdbc.queryForObject(
            "select r.confirmed_early_end_at from games g join rooms r on r.id=g.room_id where g.id=?",
            Timestamp.class,
            game);
    return value == null ? null : value.toInstant();
  }

  private Instant jpaEnd(Long game) {
    String query = "select r.confirmedEarlyEndAt from Game g join g.room r where g.id=:id";
    System.out.printf("SWEEP QUERY JPA scalar %s%n", query);
    return em.createQuery(query, Instant.class).setParameter("id", game).getSingleResult();
  }

  private Instant dbNow() {
    Map<String, Object> times =
        jdbc.queryForMap(
            "select transaction_timestamp() as tx_start,statement_timestamp() as statement_start,clock_timestamp() as observed,current_setting('TimeZone') as zone");
    System.out.printf("SWEEP DB CLOCK %s%n", times);
    return ((Timestamp) times.get("observed")).toInstant();
  }

  private Map<String, Object> row(UUID id) {
    List<Map<String, Object>> rows =
        jdbc.queryForList("select * from publication_spike_records where id=?", id);
    return rows.isEmpty() ? Map.of() : rows.getFirst();
  }

  private void observe(List<Seed> seeds, List<UUID> deleted) {
    tx(
        "fresh observer after actor transaction completion",
        () -> {
          for (Seed seed : seeds) {
            assertThat(row(seed.id()).isEmpty()).isEqualTo(deleted.contains(seed.id()));
            business(seed);
            System.out.printf(
                "SWEEP OBSERVER id=%s recordPresent=%s business=1/1/2%n",
                seed.id(), !deleted.contains(seed.id()));
          }
          return null;
        });
  }

  private void business(Seed seed) {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rooms where host_user_id=?", Integer.class, seed.user()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from games where room_id=?", Integer.class, seed.room()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from choices where game_id=?", Integer.class, seed.game()))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from games g join rooms r on r.id=g.room_id join choices c on c.id=g.correct_choice_id and c.game_id=g.id where g.id=? and r.id=? and r.host_user_id=? and c.position=0 and c.text_snapshot='one'",
                Integer.class,
                seed.game(),
                seed.room(),
                seed.user()))
        .isEqualTo(1);
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static String placeholders(List<UUID> ids) {
    return String.join(",", Collections.nCopies(ids.size(), "?"));
  }

  private static void requireStage(int stage) {
    assertThat(completedStage)
        .as("prerequisite scenario; stop dependent experiments on failure")
        .isEqualTo(stage);
  }

  private record Seed(UUID id, Long user, Long room, Long game) {}

  private record Decision(String reason, Instant expiry, Instant observed, Instant input) {
    boolean expired() {
      return "KNOWN".equals(reason) && input.isAfter(expiry);
    }
  }

  private <T> T tx(String actor, Supplier<T> action) {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    TransactionTemplate template = new TransactionTemplate(manager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setTimeout(10);
    try {
      T result =
          template.execute(status -> {
            jdbc.execute("set local statement_timeout='5s'");
            trace(actor);
            Thread thread = Thread.currentThread();
            Session session = currentSession();
            long pid = jdbc.queryForObject("select pg_backend_pid()", Long.class);
            synchronized (activeSessions) {
              synchronized (SEEN) {
                assertThat(SEEN.add(session))
                    .as("new persistence context per actor/observer transaction")
                    .isTrue();
              }
              assertThat(activeSessions.values()).doesNotContain(session);
              assertThat(activePids.values()).doesNotContain(pid);
              if (!activePids.isEmpty())
                System.out.printf(
                    "SWEEP DISTINCT overlapping=%s newActor=%s newPid=%d%n",
                    activePids, actor, pid);
              activeSessions.put(thread, session);
              activePids.put(thread, pid);
            }
            try {
              return action.get();
            } finally {
              synchronized (activeSessions) {
                activeSessions.remove(thread);
                activePids.remove(thread);
              }
            }
          });
      System.out.printf("SWEEP TX COMMITTED actor=%s%n", actor);
      return result;
    } catch (RuntimeException | Error failure) {
      System.out.printf(
          "SWEEP TX FAILED; observer required actor=%s type=%s%n",
          actor, failure.getClass().getName());
      throw failure;
    }
  }

  private void trace(String actor) {
    Object[] jpa =
        (Object[])
            em.createNativeQuery(
                    "select pg_backend_pid(),txid_current(),current_setting('transaction_isolation')")
                .getSingleResult();
    Map<String, Object> sql =
        jdbc.queryForMap(
            "select pg_backend_pid() as pid,txid_current() as tx,current_setting('transaction_isolation') as isolation");
    assertThat(((Number) jpa[0]).longValue()).isEqualTo(((Number) sql.get("pid")).longValue());
    assertThat(((Number) jpa[1]).longValue()).isEqualTo(((Number) sql.get("tx")).longValue());
    assertThat(jpa[2]).isEqualTo("read committed");
    assertThat(sql.get("isolation")).isEqualTo("read committed");
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    boolean autoCommit = currentSession().doReturningWork(connection -> connection.getAutoCommit());
    assertThat(autoCommit).isFalse();
    assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);
    assertThat(jdbc.queryForObject("show search_path", String.class)).isEqualTo(SCHEMA);
    System.out.printf(
        "SWEEP TX actor=%s thread=%s session=%s jpaPid=%s jdbcPid=%s jpaTx=%s jdbcTx=%s isolation=%s autoCommit=false schema=%s%n",
        actor,
        Thread.currentThread().getName(),
        System.identityHashCode(currentSession()),
        jpa[0],
        sql.get("pid"),
        jpa[1],
        sql.get("tx"),
        jpa[2],
        SCHEMA);
  }

  private Session currentSession() {
    return (Session) em.getDelegate();
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).as("latch watchdog").isTrue();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("latch interrupted", failure);
    }
  }

  private static void terminate(ExecutorService workers, Future<?> future) {
    if (future != null) future.cancel(true);
    workers.shutdownNow();
    try {
      assertThat(workers.awaitTermination(15, TimeUnit.SECONDS)).as("worker watchdog").isTrue();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("worker shutdown interrupted", failure);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {
    @Bean
    Clock spikeClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}

package org.kirikick.spike.publication.replacement;

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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
import org.kirikick.spike.publication.replacement.domain.PublicationReplacementSpikeRecord;
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

/** Local protocol candidate only; no API, content equivalence or operational ID guarantee. */
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
      PublicationReplacementSpikeRecord.class
    })
@Import({
  GameAssemblyService.class,
  PublicationReplacementSpikePostgresTest.ClockConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class PublicationReplacementSpikePostgresTest {
  private static final String SCHEMA =
      "publication_replacement_" + UUID.randomUUID().toString().replace("-", "");
  private static final String TABLE = "publication_replacement_spike_records";
  private static final Duration GRACE = Duration.ofSeconds(1);
  private static final Set<Session> SEEN = Collections.newSetFromMap(new IdentityHashMap<>());
  private static int completedStage;
  private final Map<Thread, Session> activeSessions = new HashMap<>();
  private final Map<Thread, Integer> activePids = new HashMap<>();
  private final AtomicReference<Throwable> workerFailure = new AtomicReference<>();
  private final AtomicLong lastAllocated = new AtomicLong();
  @Autowired private EntityManager em;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager manager;
  @Autowired private Flyway flyway;
  @Autowired private GameAssemblyService assembly;

  @DynamicPropertySource
  static void migratedSchema(DynamicPropertyRegistry registry) throws SQLException {
    var c = PostgresTestDatabase.container();
    assertThat(c.isRunning()).isTrue();
    try (var connection =
            DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword());
        var statement = connection.createStatement();
        var rows = statement.executeQuery("select current_database(),version()")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString(1)).isEqualTo(c.getDatabaseName());
      assertThat(rows.getString(2)).startsWith("PostgreSQL 17.");
      try (var check =
          connection.prepareStatement("select count(*) from pg_namespace where nspname=?")) {
        check.setString(1, SCHEMA);
        try (var found = check.executeQuery()) {
          assertThat(found.next()).isTrue();
          assertThat(found.getInt(1)).isZero();
        }
      }
      System.out.printf(
          "REPLACE PREFLYWAY container=%s image=%s imageId=%s database=%s version=%s schema=%s absent=true%n",
          c.getContainerId(),
          c.getDockerImageName(),
          c.getContainerInfo().getImageId(),
          rows.getString(1),
          rows.getString(2),
          SCHEMA);
    }
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.schemas", () -> SCHEMA);
    registry.add("spring.flyway.default-schema", () -> SCHEMA);
    registry.add(
        "spring.flyway.locations",
        () ->
            "classpath:db/migration,classpath:db/spike/n1,classpath:db/spike/publication-replacement");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    registry.add(
        "spring.datasource.hikari.connection-init-sql", () -> "set search_path to " + SCHEMA);
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> 6);
    registry.add("spring.jpa.show-sql", () -> false);
  }

  @Test
  @Order(1)
  @DisplayName("S1/R1: 만료 terminal만 교체하고 미커밋 중간 상태와 PROCESSING 삭제를 차단한다")
  void replacesOnlyExpiredTerminalAtomically() throws Exception {
    environment();
    for (String state : List.of("SUCCEEDED", "FAILED", "RETRYABLE")) {
      Seed seed = seed(state, true);
      Map<String, Object> original = snapshot(seed.key());
      CountDownLatch inserted = new CountDownLatch(1), release = new CountDownLatch(1);
      AtomicLong next = new AtomicLong();
      ExecutorService workers = Executors.newSingleThreadExecutor();
      Future<Long> b =
          submit(
              workers,
              () ->
                  tx(
                      "S1 B staged replacement " + state,
                      () -> {
                        long id = admit(seed.key(), Abort.NONE);
                        next.set(id);
                        inserted.countDown();
                        await(release);
                        return id;
                      }));
      try {
        await(inserted);
        assertThat(next.get()).isNotEqualTo(seed.id());
        tx(
            "S1 O before B commit",
            () -> {
              assertThat(lookup(seed.key(), false)).isEqualTo(original);
              assertThat(row(next.get())).isEmpty();
              business(seed, 0);
              return null;
            });
        assertThat(b.isDone()).isFalse();
        release.countDown();
        assertThat(result(b)).isEqualTo(next.get());
      } finally {
        release.countDown();
        terminate(workers, b);
      }
      observeNew(seed, next.get());
    }
    for (String guard :
        List.of("PROCESSING", "LIVE_SUCCESS", "UNKNOWN_SUCCESS", "BLOCK_ONLY_FAILED")) {
      Seed seed =
          seed(
              guard.equals("PROCESSING")
                  ? "PROCESSING"
                  : guard.equals("BLOCK_ONLY_FAILED") ? "FAILED" : "SUCCEEDED",
              !guard.equals("LIVE_SUCCESS"));
      tx(
          "S1 guard fixture " + guard,
          () -> {
            if (guard.equals("UNKNOWN_SUCCESS"))
              jdbc.update("update " + TABLE + " set game_id=null where id=?", seed.id());
            if (guard.equals("BLOCK_ONLY_FAILED"))
              jdbc.update(
                  "update "
                      + TABLE
                      + " set terminal_at=clock_timestamp()-interval '45 minutes',blocked_until=clock_timestamp()-interval '15 minutes',expires_at=clock_timestamp()+interval '15 minutes' where id=?",
                  seed.id());
            return null;
          });
      Map<String, Object> before = snapshot(seed.key());
      assertThat(tx("S1 B guard " + guard, () -> admit(seed.key(), Abort.NONE)))
          .isEqualTo(seed.id());
      preserve(seed, before, 0);
    }
    completedStage = 1;
    System.out.println(
        "REPLACE RESULT S1/R1 SUPPORTED expired S/F/R; protected PROCESSING/live/unknown/block-only; visibility");
  }

  @Test
  @Order(2)
  @DisplayName("S2/R4: DELETE 직후와 새 IDENTITY INSERT flush 후 실패는 old를 완전히 복원한다")
  void rollsBackDeleteAndJpaInsertTogether() {
    requireStage(1);
    for (Abort abort : List.of(Abort.AFTER_DELETE, Abort.AFTER_INSERT)) {
      Seed seed = seed("SUCCEEDED", true);
      Map<String, Object> before = snapshot(seed.key());
      lastAllocated.set(0);
      assertThatThrownBy(() -> tx("S2 " + abort, () -> admit(seed.key(), abort)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("injected " + abort);
      long allocated = lastAllocated.get();
      if (abort == Abort.AFTER_INSERT) assertThat(allocated).isPositive().isNotEqualTo(seed.id());
      preserve(seed, before, 0);
      tx(
          "S2 O allocated new row absent after rollback",
          () -> {
            if (allocated != 0) assertThat(row(allocated)).isEmpty();
            return null;
          });
      System.out.printf(
          "REPLACE ROLLBACK old=%d allocated=%d point=%s restored=true%n",
          seed.id(), allocated, abort);
    }
    completedStage = 2;
    System.out.println(
        "REPLACE RESULT S2/R4 SUPPORTED JDBC DELETE + JPA IDENTITY INSERT rollback; gaps allowed");
  }

  @Test
  @Order(3)
  @DisplayName("S3/R2: 만료 terminal의 새 ID와 같은 attempt 1에도 늦은 old 생성·세 CAS는 무효과다")
  void fencesOldAttemptAcrossReplacement() throws Exception {
    requireStage(2);
    Seed activation = activation();
    CountDownLatch parked = new CountDownLatch(1), resume = new CountDownLatch(1);
    AtomicInteger aAssembly = new AtomicInteger();
    ExecutorService workers = Executors.newSingleThreadExecutor();
    Future<Long> a =
        submit(
            workers,
            () -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
              System.out.println("REPLACE A parked before generation; no tx/connection/context");
              parked.countDown();
              await(resume);
              return generate(activation.key(), activation.id(), 1, activation.reward(), aAssembly);
            });
    try {
      await(parked);
      tx(
          "S3 fixture age then eligible attempt 2 takeover",
          () -> {
            jdbc.update(
                "update "
                    + TABLE
                    + " set started_at=clock_timestamp()-interval '31 seconds' where id=?",
                activation.id());
            Map<String, Object> current = lookup(activation.key(), true);
            assertThat(current.get("state")).isEqualTo("PROCESSING");
            assertThat(
                    jdbc.update(
                        "update "
                            + TABLE
                            + " set attempt=2,started_at=clock_timestamp() where id=? and state='PROCESSING' and attempt=1 and started_at<=clock_timestamp()-interval '30 seconds'",
                        activation.id()))
                .isEqualTo(1);
            return null;
          });
      Long oldGame =
          generate(activation.key(), activation.id(), 2, activation.reward(), new AtomicInteger());
      Seed seed =
          tx(
              "S3 terminal success aging fixture",
              () -> {
                assertThat(oldGame).isNotNull();
                long room =
                    jdbc.queryForObject(
                        "select room_id from games where id=?", Long.class, oldGame);
                jdbc.update(
                    "update rooms set opened_at=clock_timestamp()-interval '27 hours' where id=?",
                    room);
                assertThat(row(activation.id()).get("state")).isEqualTo("SUCCEEDED");
                return new Seed(
                    activation.key(), activation.id(), room, oldGame, activation.reward());
              });
      long next = tx("S3 B new generation admission", () -> admit(seed.key(), Abort.NONE));
      observeNew(seed, next);
      Map<String, Object> processing = snapshot(seed.key());
      resume.countDown();
      assertThat(result(a)).isNull();
      assertThat(aAssembly.get()).isZero();
      lateAll(seed, oldGame);
      preserve(seed, processing, 0);
      Long newGame = generate(seed.key(), next, 1, seed.reward(), new AtomicInteger());
      tx(
          "S3 O B committed business and result",
          () -> {
            Map<String, Object> current = lookup(seed.key(), false);
            assertThat(current)
                .containsEntry("id", next)
                .containsEntry("state", "SUCCEEDED")
                .containsEntry("attempt", 1L)
                .containsEntry("game_id", newGame);
            Game mapped = em.find(Game.class, newGame);
            assertThat(mapped.getRoom().getHost().getId()).isEqualTo(seed.key().user());
            assertThat(row(seed.id())).isEmpty();
            business(seed, 1);
            return null;
          });
      Map<String, Object> succeeded = snapshot(seed.key());
      lateAll(seed, oldGame);
      preserve(seed, succeeded, 1);
    } finally {
      resume.countDown();
      terminate(workers, a);
    }
    completedStage = 3;
    System.out.println(
        "REPLACE RESULT S3/R2 SUPPORTED old-ID generation entry no business; late F/R/S CAS=0 before/after B success");
  }

  @Test
  @Order(4)
  @DisplayName("S4/R3: 동시 재등록의 row 대기와 unique 23505·확정 롤백 뒤 재조회를 분리한다")
  void observesRowAndUniqueWaitsSeparately() throws Exception {
    requireStage(3);
    rowWait(false);
    Seed seed = seed("SUCCEEDED", true);
    assertThat(tx("S4b committed deletion creates absence", () -> sweep(List.of(seed.id()))))
        .containsExactly(seed.id());
    CountDownLatch absent = new CountDownLatch(1),
        insertC = new CountDownLatch(1),
        bInserted = new CountDownLatch(1),
        releaseB = new CountDownLatch(1),
        cReady = new CountDownLatch(1);
    AtomicInteger bPid = new AtomicInteger(), cPid = new AtomicInteger();
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<?> b = null, c = null;
    try {
      c =
          submit(
              workers,
              () -> {
                try {
                  tx(
                      "S4b C plain JPA insert transaction",
                      () -> {
                        assertThat(lookup(seed.key(), false)).isEmpty();
                        absent.countDown();
                        await(insertC);
                        cPid.set(pid());
                        cReady.countDown();
                        insert(seed.key());
                        throw new AssertionError("competing insert unexpectedly succeeded");
                      });
                  throw new AssertionError("unique violation absent");
                } catch (RuntimeException failure) {
                  assertThat(sqlState(failure)).isEqualTo("23505");
                  assertThat(messages(failure)).contains("uk_publication_replacement_user_key");
                  System.out.printf(
                      "REPLACE UNIQUE rollback confirmed sqlstate=%s constraint=uk_publication_replacement_user_key type=%s%n",
                      sqlState(failure), failure.getClass().getName());
                  return snapshot(seed.key());
                }
              });
      await(absent);
      b =
          submit(
              workers,
              () ->
                  tx(
                      "S4b B uncommitted unique owner",
                      () -> {
                        assertThat(lookup(seed.key(), false)).isEmpty();
                        bPid.set(pid());
                        long next = insert(seed.key());
                        bInserted.countDown();
                        await(releaseB);
                        return next;
                      }));
      await(bInserted);
      insertC.countDown();
      await(cReady);
      waitForBlock(cPid.get(), bPid.get(), "S4b unique INSERT", c);
      releaseB.countDown();
      long next = ((Number) result(b)).longValue();
      @SuppressWarnings("unchecked")
      Map<String, Object> recovered = (Map<String, Object>) result(c);
      assertThat(recovered).containsEntry("id", next).containsEntry("state", "PROCESSING");
      observeNew(seed, next);
    } finally {
      insertC.countDown();
      releaseB.countDown();
      terminate(workers, b, c);
    }
    completedStage = 4;
    System.out.println(
        "REPLACE RESULT S4/R3 SUPPORTED actual row wait; unique wait -> 23505 -> rollback -> fresh tx reread");
  }

  @Test
  @Order(5)
  @DisplayName("S5/R5: B 우선이면 W가 skip하고 W 우선이면 B가 삭제 후 재조회해 새 PROCESSING을 보존한다")
  void racesSweeperInBothLockOrders() throws Exception {
    requireStage(4);
    Seed seed = seed("SUCCEEDED", true), y = seed("SUCCEEDED", true);
    Map<String, Object> before = snapshot(seed.key());
    CountDownLatch inserted = new CountDownLatch(1), release = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<Long> b =
        submit(
            workers,
            () ->
                tx(
                    "S5a B staged delete+insert",
                    () -> {
                      long next = admit(seed.key(), Abort.NONE);
                      inserted.countDown();
                      await(release);
                      return next;
                    }));
    Future<List<Long>> w = null;
    try {
      await(inserted);
      w =
          submit(
              workers,
              () -> tx("S5a W SKIP LOCKED old/Y", () -> sweep(List.of(seed.id(), y.id()))));
      assertThat(result(w)).containsExactly(y.id());
      assertThat(b.isDone()).isFalse();
      tx(
          "S5a O while B still holds old/new uncommitted",
          () -> {
            assertThat(lookup(seed.key(), false)).isEqualTo(before);
            assertThat(row(y.id())).isEmpty();
            business(seed, 0);
            business(y, 0);
            return null;
          });
      assertThat(b.isDone()).isFalse();
      System.out.println("REPLACE ORDER S5a W committed + O finished before B release");
      release.countDown();
      long next = result(b);
      observeNew(seed, next);
      Map<String, Object> current = snapshot(seed.key());
      assertThat(tx("S5a W next manual pass", () -> sweep(List.of(seed.id(), next)))).isEmpty();
      preserve(seed, current, 0);
    } finally {
      release.countDown();
      terminate(workers, b, w);
    }
    rowWait(true);
    completedStage = 5;
    System.out.println(
        "REPLACE RESULT S5/R5 SUPPORTED B-first skip/independent progress; W-first delete/requery/register; new protected");
  }

  private void rowWait(boolean sweeperFirst) throws Exception {
    Seed seed = seed("SUCCEEDED", true);
    tx("advisory B read completed", () -> lookup(seed.key(), false));
    tx("advisory C read completed", () -> lookup(seed.key(), false));
    CountDownLatch locked = new CountDownLatch(1),
        release = new CountDownLatch(1),
        contenderReady = new CountDownLatch(1);
    AtomicInteger ownerPid = new AtomicInteger(), contenderPid = new AtomicInteger();
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<Long> owner =
        submit(
            workers,
            () ->
                tx(
                    sweeperFirst ? "S5b W owner" : "S4a B owner",
                    () -> {
                      Map<String, Object> current = lookup(seed.key(), true);
                      assertThat(decide(current)).isTrue();
                      ownerPid.set(pid());
                      locked.countDown();
                      await(release);
                      if (sweeperFirst) {
                        delete(current);
                        return 0L;
                      }
                      return replace(seed.key(), current, Abort.NONE);
                    }));
    Future<Long> contender = null;
    try {
      await(locked);
      contender =
          submit(
              workers,
              () ->
                  tx(
                      sweeperFirst
                          ? "S5b B waiting fresh admission"
                          : "S4a C waiting fresh admission",
                      () -> {
                        contenderPid.set(pid());
                        contenderReady.countDown();
                        return admit(seed.key(), Abort.NONE);
                      }));
      await(contenderReady);
      waitForBlock(
          contenderPid.get(),
          ownerPid.get(),
          sweeperFirst ? "S5b row SELECT" : "S4a row SELECT",
          contender);
      release.countDown();
      long winner = result(owner), next = result(contender);
      if (!sweeperFirst) assertThat(next).isEqualTo(winner);
      observeNew(seed, next);
      if (sweeperFirst) {
        Map<String, Object> before = snapshot(seed.key());
        assertThat(tx("S5b W stale old ID pass", () -> sweep(List.of(seed.id())))).isEmpty();
        assertThat(tx("S5b W current PROCESSING recheck", () -> sweep(List.of(next)))).isEmpty();
        preserve(seed, before, 0);
      }
    } finally {
      release.countDown();
      terminate(workers, owner, contender);
    }
  }

  private long admit(Key key, Abort abort) {
    Map<String, Object> current = lookup(key, true);
    System.out.printf(
        "REPLACE ADMISSION first-lock user=%d key=%s row=%s%n", key.user(), key.key(), current);
    if (current.isEmpty()) {
      current = lookup(key, true);
      System.out.printf("REPLACE ADMISSION fresh-statement-after-absence row=%s%n", current);
    }
    if (current.isEmpty()) return insert(key);
    if (!decide(current)) return number(current, "id");
    return replace(key, current, abort);
  }

  private long replace(Key key, Map<String, Object> current, Abort abort) {
    assertThat(current.get("user_id")).isEqualTo(key.user());
    assertThat(current.get("request_key")).isEqualTo(key.key());
    // Recheck under the retained row lock, not the advisory candidate snapshot.
    assertThat(decide(current)).isTrue();
    delete(current);
    if (abort == Abort.AFTER_DELETE) throw new IllegalStateException("injected " + abort);
    long next = insert(key);
    assertThat(next).isNotEqualTo(number(current, "id"));
    if (abort == Abort.AFTER_INSERT) throw new IllegalStateException("injected " + abort);
    return next;
  }

  private void delete(Map<String, Object> current) {
    int changed =
        jdbc.update(
            "delete from " + TABLE + " where id=? and state=? and attempt=?",
            current.get("id"),
            current.get("state"),
            current.get("attempt"));
    assertThat(changed).isEqualTo(1);
    System.out.printf(
        "REPLACE DELETE id=%s state=%s attempt=%s rows=%d%n",
        current.get("id"), current.get("state"), current.get("attempt"), changed);
  }

  private long insert(Key key) {
    var record =
        PublicationReplacementSpikeRecord.createProcessing(key.user(), key.key(), 1, dbNow());
    System.out.printf(
        "REPLACE INSERT before persist user=%d key=%s pid=%d%n", key.user(), key.key(), pid());
    try {
      em.persist(record);
      System.out.printf("REPLACE IDENTITY after persist id=%s%n", record.getId());
      em.flush();
    } catch (RuntimeException failure) {
      System.out.printf(
          "REPLACE JPA INSERT ERROR sqlstate=%s rollbackOnly=%s message=%s%n",
          sqlState(failure), session().getTransaction().getRollbackOnly(), messages(failure));
      throw failure;
    }
    assertThat(record.getId()).isPositive();
    lastAllocated.set(record.getId());
    trace("after IDENTITY persist/flush");
    System.out.printf(
        "REPLACE INSERT flushed id=%d user=%d key=%s%n", record.getId(), key.user(), key.key());
    return record.getId();
  }

  private boolean decide(Map<String, Object> current) {
    String state = (String) current.get("state"), reason = "KNOWN";
    Instant expiry = null;
    if (state.equals("PROCESSING")) reason = "PROCESSING_PROTECTED";
    else if (state.equals("SUCCEEDED")) {
      List<Map<String, Object>> ends =
          jdbc.queryForList(
              "select r.opened_at,r.confirmed_early_end_at from games g join rooms r on r.id=g.room_id where g.id=?",
              current.get("game_id"));
      if (ends.isEmpty() || ends.getFirst().get("opened_at") == null) reason = "END_UNKNOWN";
      else {
        Instant close = instant(ends.getFirst().get("opened_at")).plus(Duration.ofHours(24));
        Instant early = instant(ends.getFirst().get("confirmed_early_end_at"));
        expiry =
            (early != null && early.isBefore(close) ? early : close)
                .plus(Duration.ofMinutes(30))
                .plus(GRACE);
      }
    } else if (state.equals("FAILED") || state.equals("RETRYABLE")) {
      expiry = instant(current.get("expires_at"));
      if (expiry == null) reason = "RETENTION_UNKNOWN";
      if (state.equals("FAILED") && expiry != null)
        assertThat(expiry).isAfterOrEqualTo(instant(current.get("blocked_until")));
    } else reason = "STATE_UNKNOWN";
    Instant observed = dbNow();
    boolean expired = reason.equals("KNOWN") && observed.isAfter(expiry);
    System.out.printf(
        "REPLACE DECISION id=%s state=%s attempt=%s copiedExpires=%s expiry=%s observed=%s g_test=%s comparator=strictAfter reason=%s expired=%s%n",
        current.get("id"),
        state,
        current.get("attempt"),
        current.get("expires_at"),
        expiry,
        observed,
        GRACE,
        reason,
        expired);
    return expired;
  }

  private List<Long> sweep(List<Long> advisory) {
    String sql =
        "select id from "
            + TABLE
            + " where id in ("
            + String.join(",", Collections.nCopies(advisory.size(), "?"))
            + ") order by id for update skip locked";
    List<Long> locked = jdbc.queryForList(sql, Long.class, advisory.toArray());
    System.out.printf("REPLACE SWEEP sql=%s advisory=%s locked=%s%n", sql, advisory, locked);
    List<Long> deleted = new ArrayList<>();
    for (long id : locked) {
      Map<String, Object> current = row(id);
      if (!decide(current)) continue;
      delete(current);
      deleted.add(id);
    }
    return deleted;
  }

  private Seed activation() {
    return tx(
        "S3 A activation commit before parking",
        () -> {
          User user = User.create("race host", null);
          RewardPreset reward = RewardPreset.create("race", "test only");
          em.persist(user);
          em.persist(reward);
          em.flush();
          Key key = new Key(user.getId(), UUID.randomUUID());
          return new Seed(key, insert(key), 0, 0, reward.getId());
        });
  }

  private Seed seed(String state, boolean expired) {
    return tx(
        "seed " + state + " expired=" + expired,
        () -> {
          Instant now = dbNow();
          User user = User.create("race host", null);
          RewardPreset reward = RewardPreset.create("race", "test only");
          em.persist(user);
          em.persist(reward);
          em.flush();
          long game =
              assembly.createRoomWithFirstGame(
                  user.getId(), reward.getId(), 1, "race question", List.of("one", "two"), 0);
          em.flush();
          long room = jdbc.queryForObject("select room_id from games where id=?", Long.class, game);
          jdbc.update(
              "update rooms set opened_at=? where id=?",
              Timestamp.from(expired ? now.minus(Duration.ofHours(27)) : now),
              room);
          Key key = new Key(user.getId(), UUID.randomUUID());
          long id = insert(key);
          Instant terminal = now.minus(Duration.ofHours(2));
          Instant expiry =
              state.equals("SUCCEEDED")
                  ? (expired ? now.plus(Duration.ofHours(2)) : now.minusSeconds(1))
                  : terminal.plus(
                      state.equals("FAILED") ? Duration.ofHours(1) : Duration.ofMinutes(30));
          jdbc.update(
              "update "
                  + TABLE
                  + " set state=?,attempt=?,started_at=?,terminal_at=?,blocked_until=?,expires_at=?,game_id=? where id=?",
              state,
              state.equals("PROCESSING") ? 1 : 2,
              Timestamp.from(now.minus(Duration.ofHours(3))),
              state.equals("PROCESSING") ? null : Timestamp.from(terminal),
              state.equals("FAILED") ? Timestamp.from(terminal.plus(Duration.ofMinutes(30))) : null,
              Timestamp.from(expiry),
              state.equals("SUCCEEDED") ? game : null,
              id);
          return new Seed(key, id, room, game, reward.getId());
        });
  }

  private Long generate(Key key, long id, long expected, long reward, AtomicInteger calls) {
    return tx(
        "generation exact id=" + id + " attempt=" + expected,
        () -> {
          List<Map<String, Object>> rows =
              jdbc.queryForList("select * from " + TABLE + " where id=? for update", id);
          if (rows.isEmpty()
              || !rows.getFirst().get("state").equals("PROCESSING")
              || number(rows.getFirst(), "attempt") != expected) {
            System.out.printf(
                "REPLACE GENERATION FENCE no-op exactOldId=%d attempt=%d observed=%s%n",
                id, expected, rows);
            return null;
          }
          assertThat(rows.getFirst())
              .containsEntry("user_id", key.user())
              .containsEntry("request_key", key.key());
          jdbc.queryForObject("select id from users where id=? for update", Long.class, key.user());
          calls.incrementAndGet();
          long game =
              assembly.createRoomWithFirstGame(
                  key.user(), reward, 1, "race question", List.of("one", "two"), 0);
          em.flush();
          trace("business flushed before success CAS");
          int changed = successCas(id, expected, game);
          if (changed != 1) throw new IllegalStateException("SUCCESS_CAS_ZERO");
          return game;
        });
  }

  private int successCas(long id, long expected, long game) {
    int changed =
        jdbc.update(
            "update "
                + TABLE
                + " set state='SUCCEEDED',game_id=?,terminal_at=clock_timestamp(),expires_at=(select r.opened_at+interval '24 hours 30 minutes 1 second' from games g join rooms r on r.id=g.room_id where g.id=?) where id=? and state='PROCESSING' and attempt=?",
            game,
            game,
            id,
            expected);
    System.out.printf(
        "REPLACE SUCCESS CAS id=%d expected=%d game=%d rows=%d%n", id, expected, game, changed);
    return changed;
  }

  private void lateAll(Seed seed, long game) {
    for (String state : List.of("FAILED", "RETRYABLE", "SUCCEEDED")) {
      int changed =
          tx(
              "S3 adversarial late old " + state,
              () ->
                  state.equals("SUCCEEDED")
                      ? successCas(seed.id(), 1, game)
                      : jdbc.update(
                          "update "
                              + TABLE
                              + " set state=?,terminal_at=clock_timestamp(),blocked_until=case when ?='FAILED' then clock_timestamp()+interval '30 minutes' else null end,expires_at=clock_timestamp()+case when ?='FAILED' then interval '1 hour' else interval '30 minutes' end where id=? and state='PROCESSING' and attempt=?",
                          state,
                          state,
                          state,
                          seed.id(),
                          1));
      assertThat(changed).isZero();
      System.out.printf(
          "REPLACE LATE CAS id=%d expected=1 target=%s rows=%d%n", seed.id(), state, changed);
    }
  }

  private Map<String, Object> lookup(Key key, boolean lock) {
    String sql =
        "select * from "
            + TABLE
            + " where user_id=? and request_key=?"
            + (lock ? " for update" : "");
    System.out.printf(
        "REPLACE QUERY pid=%d sql=%s user=%d key=%s%n", pid(), sql, key.user(), key.key());
    List<Map<String, Object>> rows = jdbc.queryForList(sql, key.user(), key.key());
    assertThat(rows).hasSizeLessThanOrEqualTo(1);
    return rows.isEmpty() ? Map.of() : rows.getFirst();
  }

  private Map<String, Object> row(long id) {
    List<Map<String, Object>> rows =
        jdbc.queryForList("select * from " + TABLE + " where id=?", id);
    return rows.isEmpty() ? Map.of() : rows.getFirst();
  }

  private Map<String, Object> snapshot(Key key) {
    return tx(
        "fresh O full-row snapshot",
        () -> {
          Map<String, Object> current = lookup(key, false);
          System.out.printf("REPLACE SNAPSHOT %s%n", current);
          return current;
        });
  }

  private void preserve(Seed seed, Map<String, Object> expected, int extra) {
    tx(
        "fresh O full-row preservation",
        () -> {
          assertThat(lookup(seed.key(), false)).isEqualTo(expected);
          business(seed, extra);
          System.out.printf("REPLACE OBSERVER preserved=%s businessDelta=%d%n", expected, extra);
          return null;
        });
  }

  private void observeNew(Seed seed, long next) {
    tx(
        "fresh O committed new generation",
        () -> {
          assertThat(next).isPositive().isNotEqualTo(seed.id());
          assertThat(row(seed.id())).isEmpty();
          Map<String, Object> current = lookup(seed.key(), false);
          assertThat(current)
              .containsEntry("id", next)
              .containsEntry("state", "PROCESSING")
              .containsEntry("attempt", 1L);
          assertThat(current.get("terminal_at")).isNull();
          assertThat(current.get("blocked_until")).isNull();
          assertThat(current.get("expires_at")).isNull();
          assertThat(current.get("game_id")).isNull();
          assertThat(
                  jdbc.queryForObject(
                      "select count(*) from " + TABLE + " where user_id=? and request_key=?",
                      Integer.class,
                      seed.key().user(),
                      seed.key().key()))
              .isEqualTo(1);
          System.out.printf(
              "REPLACE PHYSICAL %s%n",
              jdbc.queryForMap(
                  "select tableoid::regclass::text table_name,id,attempt,state from "
                      + TABLE
                      + " where id=?",
                  next));
          business(seed, 0);
          return null;
        });
  }

  private void business(Seed seed, int extra) {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rooms where host_user_id=?",
                Integer.class,
                seed.key().user()))
        .isEqualTo(1 + extra);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from games g join rooms r on r.id=g.room_id where r.host_user_id=?",
                Integer.class,
                seed.key().user()))
        .isEqualTo(1 + extra);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from choices c join games g on g.id=c.game_id join rooms r on r.id=g.room_id where r.host_user_id=?",
                Integer.class,
                seed.key().user()))
        .isEqualTo(2 * (1 + extra));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from games g join rooms r on r.id=g.room_id join choices c on c.id=g.correct_choice_id and c.game_id=g.id where r.host_user_id=? and c.position=0 and c.text_snapshot='one'",
                Integer.class,
                seed.key().user()))
        .isEqualTo(1 + extra);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from games where id=? and room_id=?",
                Integer.class,
                seed.game(),
                seed.room()))
        .isEqualTo(1);
    System.out.printf(
        "REPLACE BUSINESS user=%d rooms=%d games=%d choices=%d correctFK=true oldGame=%d oldRoom=%d%n",
        seed.key().user(), 1 + extra, 1 + extra, 2 * (1 + extra), seed.game(), seed.room());
  }

  private void waitForBlock(int waiting, int owner, String label, Future<?> contender) {
    tx(
        "wait monitor " + label,
        () -> {
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          while (System.nanoTime() < deadline) {
            checkWorker();
            if (contender.isDone())
              throw new AssertionError("contender completed before observed lock wait: " + label);
            jdbc.execute("select pg_stat_clear_snapshot()");
            Boolean blocked =
                jdbc.queryForObject(
                    "select ?=any(pg_blocking_pids(?))", Boolean.class, owner, waiting);
            if (Boolean.TRUE.equals(blocked)) {
              Map<String, Object> wait =
                  jdbc.queryForMap(
                      "select pid,wait_event_type,wait_event,pg_blocking_pids(pid)::text blockers,query from pg_stat_activity where pid=?",
                      waiting);
              assertThat(wait.get("wait_event_type")).isEqualTo("Lock");
              System.out.printf(
                  "REPLACE WAIT label=%s owner=%d contender=%d observed=%s%n",
                  label, owner, waiting, wait);
              return null;
            }
          }
          throw new AssertionError("no DB blocking evidence before watchdog: " + label);
        });
  }

  private void environment() {
    assertThat(manager).isInstanceOf(JpaTransactionManager.class);
    assertThat(AopUtils.isAopProxy(assembly)).isTrue();
    assertThat(em.getEntityManagerFactory().getProperties())
        .containsEntry("hibernate.hbm2ddl.auto", "validate");
    flyway.validate();
    assertThat(flyway.info().pending()).isEmpty();
    assertThat(flyway.info().applied())
        .filteredOn(m -> m.getVersion() != null)
        .extracting(m -> m.getVersion().getVersion())
        .containsExactly("1", "2", "9000", "9002");
    for (var m : flyway.info().applied())
      System.out.printf(
          "REPLACE MIGRATION version=%s script=%s checksum=%s state=%s%n",
          m.getVersion(), m.getScript(), m.getChecksum(), m.getState());
    tx(
        "environment actual DB/driver",
        () -> {
          session()
              .doWork(c ->
                  System.out.printf(
                      "REPLACE ENV JDK=%s boot=%s hibernate=%s flyway=%s driver=%s manager=%s ddl=validate testcontainers=%s database=%s%n",
                      System.getProperty("java.version"),
                      SpringBootVersion.getVersion(),
                      Version.getVersionString(),
                      Flyway.class.getProtectionDomain().getCodeSource().getLocation().getFile(),
                      c.getMetaData().getDriverVersion(),
                      manager.getClass().getName(),
                      PostgresTestDatabase.container()
                          .getClass()
                          .getProtectionDomain()
                          .getCodeSource()
                          .getLocation()
                          .getFile(),
                      c.getMetaData().getDatabaseProductName()));
          System.out.printf(
              "REPLACE ID COLUMN %s%n",
              jdbc.queryForMap(
                  "select data_type,is_identity,identity_generation from information_schema.columns where table_schema=? and table_name=? and column_name='id'",
                  SCHEMA,
                  TABLE));
          assertThat(
                  jdbc.queryForObject(
                      "select is_identity from information_schema.columns where table_schema=? and table_name=? and column_name='id'",
                      String.class,
                      SCHEMA,
                      TABLE))
              .isEqualTo("YES");
          return null;
        });
  }

  private <T> T tx(String actor, Supplier<T> action) {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    TransactionTemplate template = new TransactionTemplate(manager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setTimeout(30);
    try {
      T result =
          template.execute(status -> {
            jdbc.execute("set local statement_timeout='10s'");
            trace(actor);
            Thread thread = Thread.currentThread();
            Session session = session();
            int pid = pid();
            synchronized (activeSessions) {
              synchronized (SEEN) {
                assertThat(SEEN.add(session)).as("new actual context per transaction").isTrue();
              }
              assertThat(activeSessions.values()).doesNotContain(session);
              assertThat(activePids.values()).doesNotContain(pid);
              System.out.printf(
                  "REPLACE DISTINCT active=%s newActor=%s newPid=%d%n", activePids, actor, pid);
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
      System.out.printf("REPLACE TX COMMITTED actor=%s%n", actor);
      return result;
    } catch (RuntimeException | Error failure) {
      System.out.printf(
          "REPLACE TX FAILED actor=%s type=%s sqlstate=%s message=%s%n",
          actor, failure.getClass().getName(), sqlState(failure), messages(failure));
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
            "select pg_backend_pid() pid,txid_current() tx,current_setting('transaction_isolation') isolation");
    assertThat(((Number) jpa[0]).longValue()).isEqualTo(number(sql, "pid"));
    assertThat(((Number) jpa[1]).longValue()).isEqualTo(number(sql, "tx"));
    assertThat(jpa[2]).isEqualTo("read committed");
    assertThat(sql.get("isolation")).isEqualTo("read committed");
    boolean autoCommit = session().doReturningWork(c -> c.getAutoCommit());
    assertThat(autoCommit).isFalse();
    assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);
    assertThat(jdbc.queryForObject("show search_path", String.class)).isEqualTo(SCHEMA);
    System.out.printf(
        "REPLACE TX actor=%s thread=%s session=%d jpaPid=%s jdbcPid=%s jpaTx=%s jdbcTx=%s isolation=%s autoCommit=false schema=%s%n",
        actor,
        Thread.currentThread().getName(),
        System.identityHashCode(session()),
        jpa[0],
        sql.get("pid"),
        jpa[1],
        sql.get("tx"),
        jpa[2],
        SCHEMA);
  }

  private Instant dbNow() {
    Map<String, Object> times =
        jdbc.queryForMap(
            "select transaction_timestamp() tx_start,statement_timestamp() statement_start,clock_timestamp() observed,current_setting('TimeZone') zone");
    System.out.printf("REPLACE CLOCK %s%n", times);
    return instant(times.get("observed"));
  }

  private int pid() {
    return jdbc.queryForObject("select pg_backend_pid()", Integer.class);
  }

  private Session session() {
    return (Session) em.getDelegate();
  }

  private static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static Instant instant(Object value) {
    return value == null ? null : ((Timestamp) value).toInstant();
  }

  private static void requireStage(int expected) {
    assertThat(completedStage)
        .as("stop dependent actors unless prior oracle completed")
        .isEqualTo(expected);
  }

  private <T> Future<T> submit(ExecutorService workers, Supplier<T> work) {
    return workers.submit(() -> {
      try {
        return work.get();
      } catch (RuntimeException | Error failure) {
        workerFailure.compareAndSet(null, failure);
        failure.printStackTrace();
        throw failure;
      }
    });
  }

  private void checkWorker() {
    Throwable failure = workerFailure.get();
    if (failure != null) throw new AssertionError("worker failed before synchronization", failure);
  }

  private void await(CountDownLatch latch) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    try {
      while (System.nanoTime() < deadline) {
        checkWorker();
        if (latch.await(25, TimeUnit.MILLISECONDS)) return;
      }
      checkWorker();
      throw new AssertionError("latch watchdog");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("latch interrupted", failure);
    }
  }

  private static <T> T result(Future<T> future) throws Exception {
    try {
      return future.get(15, TimeUnit.SECONDS);
    } catch (ExecutionException failure) {
      throw new AssertionError("worker cause", failure.getCause());
    }
  }

  private static void terminate(ExecutorService workers, Future<?>... futures) {
    for (Future<?> future : futures) if (future != null && !future.isDone()) future.cancel(true);
    workers.shutdownNow();
    try {
      assertThat(workers.awaitTermination(15, TimeUnit.SECONDS))
          .as("worker termination watchdog")
          .isTrue();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(failure);
    }
  }

  private static String sqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      if (cause instanceof SQLException sql) return sql.getSQLState();
    return "none";
  }

  private static String messages(Throwable failure) {
    StringBuilder text = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      text.append(cause.getMessage()).append(" | ");
    return text.toString();
  }

  private enum Abort {
    NONE,
    AFTER_DELETE,
    AFTER_INSERT
  }

  private record Key(Long user, UUID key) {}

  private record Seed(Key key, long id, long room, long game, long reward) {}

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {
    @Bean
    Clock spikeClock() {
      return Clock.fixed(Instant.parse("2026-11-17T09:00:00Z"), ZoneOffset.UTC);
    }
  }
}

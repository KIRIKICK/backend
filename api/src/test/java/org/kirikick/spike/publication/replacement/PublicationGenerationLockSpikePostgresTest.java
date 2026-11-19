package org.kirikick.spike.publication.replacement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.hibernate.Session;
import org.hibernate.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
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
import org.springframework.core.SpringVersion;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
  PublicationGenerationLockSpikePostgresTest.ClockConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class PublicationGenerationLockSpikePostgresTest {

  private static final String SCHEMA =
      "pub_generation_" + UUID.randomUUID().toString().replace("-", "");
  private static final String TABLE = "publication_replacement_spike_records";
  private static final String CONTENDER =
      "/* generation S1-B */ select * from "
          + TABLE
          + " where user_id=? and request_key=? for update";
  private static final Footprint EMPTY = new Footprint(0, 0, 0);
  private static final Footprint CREATED = new Footprint(1, 1, 2);

  private static Instant roomClock;
  private static String serverIdentity;

  @Autowired private EntityManager em;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager manager;
  @Autowired private GameAssemblyService assembly;
  @Autowired private Flyway flyway;

  private boolean s1Supported;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws SQLException {
    var container = PostgresTestDatabase.container();
    String url = container.getJdbcUrl();
    URI endpoint = URI.create(url.substring("jdbc:".length()));
    assertThat(container.isRunning()).isTrue();
    assertThat(endpoint.getHost()).isIn("localhost", "127.0.0.1", "[::1]");
    assertThat(endpoint.getPort()).isEqualTo(container.getMappedPort(5432));
    assertThat(endpoint.getPath()).isEqualTo("/" + container.getDatabaseName());

    Properties credentials = new Properties();
    credentials.setProperty("user", container.getUsername());
    credentials.setProperty("password", container.getPassword());
    credentials.setProperty("connectTimeout", "5");
    credentials.setProperty("socketTimeout", "10");
    try (var connection = DriverManager.getConnection(url, credentials);
        var statement =
            connection.prepareStatement(
                """
                select current_database(), version(), pg_postmaster_start_time()::text,
                       clock_timestamp(), inet_server_port(),
                       exists(select 1 from pg_namespace where nspname=?)
                """)) {
      statement.setString(1, SCHEMA);
      try (var rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo(container.getDatabaseName());
        assertThat(rs.getString(2)).startsWith("PostgreSQL 17.");
        assertThat(rs.getInt(5)).isEqualTo(5432);
        assertThat(rs.getBoolean(6)).as("Flyway 전 새 schema 부재").isFalse();
        serverIdentity = rs.getString(3);
        roomClock = rs.getObject(4, OffsetDateTime.class).toInstant();
        System.out.printf(
            "GEN PRE_FLYWAY container=%s image=%s imageId=%s endpoint=%s:%d db=%s schema=%s "
                + "schemaAbsent=true server=%s postmaster=%s roomClock=%s%n",
            container.getContainerId(),
            container.getDockerImageName(),
            container.getContainerInfo().getImageId(),
            endpoint.getHost(),
            endpoint.getPort(),
            rs.getString(1),
            SCHEMA,
            rs.getString(2),
            serverIdentity,
            roomClock);
      }
    }
    registry.add("spring.flyway.url", container::getJdbcUrl);
    registry.add("spring.flyway.user", container::getUsername);
    registry.add("spring.flyway.password", container::getPassword);
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.schemas", () -> SCHEMA);
    registry.add("spring.flyway.default-schema", () -> SCHEMA);
    registry.add(
        "spring.flyway.locations",
        () ->
            "classpath:db/spike/baseline,classpath:db/spike/n1,classpath:db/spike/publication-replacement");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    registry.add(
        "spring.datasource.hikari.connection-init-sql", () -> "set search_path to " + SCHEMA);
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> 6);
    registry.add("spring.datasource.hikari.connection-timeout", () -> 3000);
    registry.add("spring.jpa.show-sql", () -> false);
  }

  @Test
  @Order(1)
  @DisplayName("S1: 생성 락을 기다린 경쟁 요청은 커밋된 현재 성공 기록을 재확인한다")
  void s1GenerationLockAndRecheck() throws Exception {
    try {
      verifyHarness();
    } catch (RuntimeException | AssertionError failure) {
      result("H0", "BLOCKED", failure);
      result("S1", "BLOCKED", failure);
      throw failure;
    }
    try {
      verifyCompletedContenderGuard();
      Seed seed = seed("S1");
      Map<String, Object> baseline = baseline(seed, "S1");
      compete(seed, baseline);
      result("S1", "SUPPORTED", null);
      s1Supported = true;
    } catch (Exception | AssertionError failure) {
      result("S1", verdict(failure), failure);
      throw failure;
    }
  }

  @Test
  @Order(2)
  @DisplayName("S2: 정상 fence와 업무 flush 뒤 성공 CAS 0건은 업무 전체를 롤백한다")
  void s2ZeroCasRollsBack() {
    if (!s1Supported) {
      result("S2", "BLOCKED", null);
    }
    assumeTrue(s1Supported, "S1이 같은 테스트 인스턴스에서 SUPPORTED일 때만 S2를 실행한다");
    try {
      Seed seed = seed("S2");
      Map<String, Object> baseline = baseline(seed, "S2");
      SuccessCasZero rollback =
          assertThrowsExactly(
              SuccessCasZero.class,
              () ->
                  tx(
                      "S2.A",
                      () -> {
                        TxId owner = lockForGeneration(seed, "S2.A");
                        Business created = assemble(seed, owner, "S2.A");
                        observe(
                            cas(seed, 999, created, owner, "S2.A") == 0,
                            "wrong-attempt CAS did not touch zero");
                        throw new SuccessCasZero(created);
                      }));
      tx(
          "S2.final observer",
          () -> {
            Map<String, Object> current = only(keyRows(seed));
            Footprint counts = footprint(seed.user());
            Business created = rollback.created;
            long room =
                jdbc.queryForObject(
                    "select count(*) from rooms where id=?", Long.class, created.room());
            long game =
                jdbc.queryForObject(
                    "select count(*) from games where id=?", Long.class, created.game());
            long choices =
                jdbc.queryForObject(
                    "select count(*) from choices where id in (?,?)",
                    Long.class,
                    created.choices().get(0),
                    created.choices().get(1));
            System.out.printf(
                "GEN S2 FINAL row=%s scoped=%s allocatedIdHits=%d/%d/%d%n",
                current, counts, room, game, choices);
            invariant(
                current.equals(baseline)
                    && current.get("game_id") == null
                    && "PROCESSING".equals(current.get("state"))
                    && EMPTY.equals(counts)
                    && room == 0
                    && game == 0
                    && choices == 0,
                "confirmed zero-CAS rollback left business or changed the record");
            return null;
          });
      result("S2", "SUPPORTED", null);
    } catch (RuntimeException | AssertionError failure) {
      result("S2", verdict(failure), failure);
      throw failure;
    }
  }

  private void compete(Seed seed, Map<String, Object> baseline) throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean permitCas = new AtomicBoolean();
    CompletableFuture<Paused> aReady = new CompletableFuture<>();
    CompletableFuture<TxId> bReady = new CompletableFuture<>();
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Throwable cause = null;
    try {
      Future<Business> a =
          workers.submit(() ->
              publishFailure(
                  aReady,
                  () ->
                      tx(
                          "S1.A",
                          () -> {
                            TxId owner = lockForGeneration(seed, "S1.A");
                            Business created = assemble(seed, owner, "S1.A");
                            OpenTx active = openTx(owner.pid());
                            observe(
                                active != null && active.xid() != null && active.started() != null,
                                "A transaction identity unavailable after flush");
                            aReady.complete(new Paused(owner, active, created));
                            awaitRelease(release);
                            observe(
                                permitCas.get(),
                                "A aborted before CAS: blocking evidence unavailable");
                            observe(
                                cas(seed, 1, created, owner, "S1.A") == 1,
                                "S1 success CAS not exactly one");
                            return created;
                          })));
      Paused paused = resultOf(aReady);
      // Both observations share a budget starting before B can use its 10s lock timeout.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      Future<Recheck> b =
          workers.submit(() ->
              publishFailure(
                  bReady,
                  () ->
                      tx(
                          "S1.B",
                          () -> {
                            jdbc.execute("set local lock_timeout='10s'");
                            TxId waiting = jdbcIdentity();
                            Map<String, Object> previous = only(keyRows(seed));
                            Instant beforeLock = dbNow();
                            observe(
                                "PROCESSING".equals(previous.get("state"))
                                    && number(previous, "id") == seed.id()
                                    && !beforeLock.isBefore(
                                        instant(previous.get("started_at")).plusSeconds(30)),
                                "B did not see the eligible same PROCESSING row before requesting its lock");
                            System.out.printf(
                                "GEN S1 B REQUEST tx=%s started=%s observed=%s sql=%s%n",
                                waiting,
                                instant(previous.get("started_at")),
                                beforeLock,
                                CONTENDER);
                            bReady.complete(waiting);
                            Map<String, Object> current =
                                only(jdbc.queryForList(CONTENDER, seed.user(), seed.key()));
                            if (number(current, "id") == seed.id()
                                && paused.active().equals(openTx(paused.tx().pid()))) {
                              throw new Finding(
                                  "COUNTEREXAMPLE",
                                  "B obtained the target lock while A's transaction was still open");
                            }
                            observe(
                                permitCas.get(),
                                "B acquired after A aborted before successful observation");
                            return recheck(current);
                          })));
      TxId waiting = resultOf(bReady);
      tx(
          "S1.wait observer",
          () -> {
            observeBlocking("WAIT", paused, waiting, a, b, deadline);
            Footprint invisible = footprint(seed.user());
            invariant(
                only(keyRows(seed)).equals(baseline) && invisible.equals(EMPTY),
                "observer saw business or record changes before A commit");
            System.out.printf("GEN S1 PRECOMMIT scoped=%s unchanged=true%n", invisible);
            return null;
          });
      tx(
          "S1.pre-CAS observer",
          () -> {
            observeBlocking("PRE_CAS_WAIT", paused, waiting, a, b, deadline);
            return null;
          });
      // shortcut: B can still time out after this check; its post-commit result remains mandatory.
      authorizeCas(permitCas, a, b, deadline);
      release.countDown();
      Business committed = resultOf(a);
      Recheck observed;
      try {
        observed = resultOf(b);
      } catch (Exception failure) {
        throw new Finding("INCONCLUSIVE", "B could not confirm recheck after A committed", failure);
      }
      tx(
          "S1.final observer",
          () -> {
            verifyS1(seed, committed, observed);
            return null;
          });
    } catch (Exception | Error failure) {
      cause = failure;
      throw failure;
    } finally {
      release.countDown();
      workers.shutdownNow();
      try {
        observe(workers.awaitTermination(20, TimeUnit.SECONDS), "worker shutdown watchdog");
      } catch (InterruptedException | Error failure) {
        if (failure instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        if (cause == null) {
          throw failure;
        }
        cause.addSuppressed(failure);
      }
    }
  }

  private static void verifyCompletedContenderGuard() {
    var permit = new AtomicBoolean();
    var timeout = new SQLException("injected B lock timeout", "55P03");
    var failure =
        assertThrowsExactly(
            Finding.class,
            () ->
                authorizeCas(
                    permit,
                    new CompletableFuture<>(),
                    CompletableFuture.failedFuture(timeout),
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
    assertThat(permit).isFalse();
    assertThat(failure.getCause()).isSameAs(timeout);
    assertThat(verdict(failure)).isEqualTo("INCONCLUSIVE");
    assertThat(sqlState(failure)).isEqualTo("55P03");
    System.out.println("GEN S1 GUARD_PROBE injectedSqlState=55P03 casPermitted=false");
  }

  private static void authorizeCas(AtomicBoolean permit, Future<?> a, Future<?> b, long deadline) {
    requirePendingWorkers(a, b);
    observe(System.nanoTime() < deadline, "5s watchdog exhausted before CAS permission");
    permit.set(true);
  }

  private static void requirePendingWorkers(Future<?> a, Future<?> b) {
    boolean bDone = b.isDone();
    if (!bDone && !a.isDone()) {
      return;
    }
    Future<?> completed = bDone ? b : a;
    String reason = (bDone ? "B" : "A") + " finished before CAS permission";
    try {
      resultOf(completed);
    } catch (Exception failure) {
      throw new Finding(verdict(failure), reason, failure);
    }
    throw new Finding("INCONCLUSIVE", reason);
  }

  private void observeBlocking(
      String phase,
      Paused owner,
      TxId waiting,
      Future<Business> a,
      Future<Recheck> b,
      long deadline) {
    TxId observer = jdbcIdentity();
    observe(
        Set.of(owner.tx().pid(), waiting.pid(), observer.pid()).size() == 3
            && Set.of(owner.tx().xid(), waiting.xid(), observer.xid()).size() == 3,
        "A, B, observer did not have three independent backends and transactions");
    var pool = (HikariDataSource) dataSource;
    while (System.nanoTime() < deadline) {
      requirePendingWorkers(a, b);
      jdbc.execute("select pg_stat_clear_snapshot()");
      List<Map<String, Object>> samples =
          jdbc.queryForList(
              """
              select a.xact_start owner_start, a.backend_xid::text owner_xid, a.state owner_state,
                     b.wait_event_type, b.wait_event, b.query, pg_blocking_pids(b.pid)::text blockers,
                     b.query_start, clock_timestamp() sampled_at,
                     ?=any(pg_blocking_pids(b.pid)) blocked
              from pg_stat_activity a cross join pg_stat_activity b
              where a.pid=? and b.pid=?
              """,
              owner.tx().pid(),
              owner.tx().pid(),
              waiting.pid());
      if (samples.isEmpty()) {
        continue;
      }
      Map<String, Object> w = samples.getFirst();
      if (Boolean.TRUE.equals(w.get("blocked"))
          && "Lock".equals(w.get("wait_event_type"))
          && String.valueOf(w.get("query")).startsWith("/* generation S1-B */")
          && System.nanoTime() < deadline) {
        OpenTx live = new OpenTx(instant(w.get("owner_start")), (String) w.get("owner_xid"));
        observe(
            owner.active().equals(live),
            "blocking A is not the same still-open generation transaction");
        requirePendingWorkers(a, b);
        int active = pool.getHikariPoolMXBean().getActiveConnections();
        if (active < 3) {
          throw new Finding("BLOCKED", "pool cannot evidence three simultaneous connections");
        }
        System.out.printf(
            "GEN S1 %s A=%s B=%s O=%s poolActive=%d poolMax=%d watchdogLeftMs=%d created=%s sample=%s%n",
            phase,
            owner.tx(),
            waiting,
            observer,
            active,
            pool.getMaximumPoolSize(),
            TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()),
            owner.created(),
            w);
        return;
      }
    }
    requirePendingWorkers(a, b);
    throw new Finding(
        "INCONCLUSIVE", "5s watchdog: no matching PostgreSQL row-lock wait: " + phase);
  }

  private Recheck recheck(Map<String, Object> current) {
    Instant decision = dbNow();
    boolean takeover =
        "PROCESSING".equals(current.get("state"))
            && !decision.isBefore(instant(current.get("started_at")).plusSeconds(30));
    Instant expiry = null;
    if ("SUCCEEDED".equals(current.get("state")) && current.get("game_id") != null) {
      Map<String, Object> end =
          jdbc.queryForMap(
              """
              select r.opened_at, r.confirmed_early_end_at from rooms r join games g on g.room_id=r.id
              where g.id=?
              """,
              number(current, "game_id"));
      if (end.get("opened_at") != null) {
        Instant common = instant(end.get("opened_at")).plus(24, ChronoUnit.HOURS);
        Instant early = instant(end.get("confirmed_early_end_at"));
        Instant effective = early != null && early.isBefore(common) ? early : common;
        expiry = effective.plusSeconds(1801);
      }
    }
    boolean replace = expiry != null && !decision.isBefore(expiry);
    System.out.printf(
        "GEN S1 B RECHECK row=%s decision=%s expiry=%s takeover=%s replace=%s%n",
        current, decision, expiry, takeover, replace);
    return new Recheck(current, decision, expiry, takeover, replace);
  }

  private void verifyS1(Seed seed, Business created, Recheck observed) {
    Map<String, Object> oracle =
        jdbc.queryForMap(
            """
            select raw.*, ends_at+interval '30 minutes 1 second' expiry,
                   cast(? as timestamptz) < ends_at+interval '30 minutes 1 second' valid
            from (select r.opened_at, r.confirmed_early_end_at, r.opened_at+interval '24 hours' common_end,
                         least(r.opened_at+interval '24 hours', r.confirmed_early_end_at) ends_at
                  from games g join rooms r on r.id=g.room_id where g.id=?) raw
            """,
            observed.decision().atOffset(ZoneOffset.UTC),
            created.game());
    System.out.printf(
        "GEN S1 ORACLE decision=%s graceSeconds=1 game=%d result=%s%n",
        observed.decision(), created.game(), oracle);
    observe(
        Boolean.TRUE.equals(oracle.get("valid")),
        "fixture success is not independently still valid");
    Map<String, Object> row = only(keyRows(seed));
    System.out.printf("GEN S1 FINAL row=%s business=%s%n", row, created);
    invariant(
        observed.row().equals(row)
            && !observed.takeover()
            && !observed.replace()
            && seed.id() == number(row, "id")
            && number(row, "attempt") == 1
            && "SUCCEEDED".equals(row.get("state"))
            && row.get("terminal_at") != null
            && row.get("game_id") != null
            && created.game() == number(row, "game_id")
            && instant(oracle.get("expiry")).equals(observed.expiry()),
        "B or final observer did not preserve the current valid success");
    invariant(
        CREATED.equals(footprint(seed.user())) && created.equals(business(seed, created.game())),
        "wrong committed business/FK after S1");
  }

  private Seed seed(String scenario) {
    return tx(
        scenario + ".seed",
        () -> {
          User user = User.create("gen-host", null);
          RewardPreset reward = RewardPreset.create("generation", "test only");
          em.persist(user);
          em.persist(reward);
          em.flush();
          UUID key = UUID.randomUUID();
          Instant started = dbNow().minusSeconds(31);
          var record =
              PublicationReplacementSpikeRecord.createProcessing(user.getId(), key, 1, started);
          em.persist(record);
          em.flush();
          assertThat(record.getId()).isPositive();
          return new Seed(record.getId(), user.getId(), key, reward.getId());
        });
  }

  private Map<String, Object> baseline(Seed seed, String scenario) {
    return tx(
        scenario + ".baseline observer",
        () -> {
          Map<String, Object> row = only(keyRows(seed));
          assertThat(row.get("state")).isEqualTo("PROCESSING");
          assertThat(number(row, "attempt")).isEqualTo(1);
          assertThat(row.get("game_id")).isNull();
          Footprint footprint = footprint(seed.user());
          assertThat(footprint).isEqualTo(EMPTY);
          System.out.printf("GEN %s BASELINE row=%s scoped=%s%n", scenario, row, footprint);
          return row;
        });
  }

  private TxId lockForGeneration(Seed seed, String actor) {
    Map<String, Object> row =
        jdbc.queryForMap(
            "select *,pg_backend_pid() pid,txid_current() xid from "
                + TABLE
                + " where id=? for update",
            seed.id());
    assertThat(row).containsEntry("user_id", seed.user()).containsEntry("request_key", seed.key());
    assertThat(row.get("state")).isEqualTo("PROCESSING");
    assertThat(number(row, "id")).isEqualTo(seed.id());
    assertThat(number(row, "attempt")).isEqualTo(1);
    TxId stamp = new TxId((int) number(row, "pid"), number(row, "xid"));
    System.out.printf("GEN %s FENCE id=%d attempt=1 tx=%s%n", actor, seed.id(), stamp);
    return stamp;
  }

  private Business assemble(Seed seed, TxId owner, String actor) {
    assertThat(
            jdbc.queryForObject(
                "select id from users where id=? for update", Long.class, seed.user()))
        .isEqualTo(seed.user());
    Session session = em.unwrap(Session.class);
    TxId before = session.doReturningWork(this::identity);
    observe(owner.equals(before) && em.isJoinedToTransaction(), "JPA not joined before assembly");
    long game =
        assembly.createRoomWithFirstGame(
            seed.user(), seed.reward(), 1, "generation lock fixture", List.of("one", "two"), 0);
    em.flush();
    TxId after = session.doReturningWork(this::identity);
    observe(
        owner.equals(after) && em.unwrap(Session.class) == session && em.isJoinedToTransaction(),
        "JPA session/transaction changed over assembly and flush");
    Footprint counts = footprint(seed.user());
    assertThat(counts).isEqualTo(CREATED);
    Business flushed = business(seed, game);
    System.out.printf(
        "GEN %s FLUSH jpaBefore=%s jpaAfter=%s session=%d jdbc=%s scoped=%s created=%s%n",
        actor, before, after, System.identityHashCode(session), jdbcIdentity(), counts, flushed);
    return flushed;
  }

  private int cas(Seed seed, long expected, Business created, TxId owner, String actor) {
    return jdbc.execute(
        (ConnectionCallback<Integer>)
            connection -> {
              TxId actual = identity(connection);
              observe(owner.equals(actual), "CAS connection differs from generation lock");
              try (var p =
                  connection.prepareStatement(
                      "update "
                          + TABLE
                          + " set state='SUCCEEDED',game_id=?,terminal_at=clock_timestamp()"
                          + " where id=? and state='PROCESSING' and attempt=?")) {
                p.setLong(1, created.game());
                p.setLong(2, seed.id());
                p.setLong(3, expected);
                int changed = p.executeUpdate();
                System.out.printf(
                    "GEN %s CAS tx=%s id=%d expected=%d rows=%d game=%d%n",
                    actor, actual, seed.id(), expected, changed, created.game());
                return changed;
              }
            });
  }

  private List<Map<String, Object>> keyRows(Seed seed) {
    return jdbc.queryForList(
        "select * from " + TABLE + " where user_id=? and request_key=?", seed.user(), seed.key());
  }

  private static Map<String, Object> only(List<Map<String, Object>> rows) {
    assertThat(rows).hasSize(1);
    return rows.getFirst();
  }

  private Footprint footprint(long user) {
    return jdbc.queryForObject(
        """
        select (select count(*) from rooms where host_user_id=?) rooms,
               (select count(*) from games g join rooms r on r.id=g.room_id where r.host_user_id=?) games,
               (select count(*) from choices c join games g on g.id=c.game_id
                join rooms r on r.id=g.room_id where r.host_user_id=?) choices
        """,
        (rs, ignored) -> new Footprint(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
        user,
        user,
        user);
  }

  private Business business(Seed seed, long game) {
    Map<String, Object> row =
        jdbc.queryForMap(
            """
            select r.id room, g.correct_choice_id correct
            from games g join rooms r on r.id=g.room_id where g.id=? and r.host_user_id=?
            """,
            game,
            seed.user());
    List<Long> choices =
        jdbc.queryForList("select id from choices where game_id=? order by id", Long.class, game);
    long correct = number(row, "correct");
    assertThat(choices).hasSize(2).contains(correct);
    return new Business(number(row, "room"), game, choices, correct);
  }

  private void verifyHarness() {
    assertThat(Runtime.version().feature()).isEqualTo(21);
    assertThat(manager).isInstanceOf(JpaTransactionManager.class);
    assertThat(AopUtils.isAopProxy(assembly)).isTrue();
    assertThat(em.getEntityManagerFactory().getProperties())
        .containsEntry("hibernate.hbm2ddl.auto", "validate");
    var pool = (HikariDataSource) dataSource;
    assertThat(pool.getMaximumPoolSize()).isEqualTo(6);
    assertThat(pool.getConnectionTimeout()).isEqualTo(3000);
    assertThat(pool.getJdbcUrl()).isEqualTo(PostgresTestDatabase.container().getJdbcUrl());
    assertThat(flyway.getConfiguration().getLocations())
        .extracting(location -> location.getDescriptor())
        .containsExactly(
            "classpath:db/spike/baseline",
            "classpath:db/spike/n1",
            "classpath:db/spike/publication-replacement");
    flyway.validate();
    assertThat(flyway.info().pending()).isEmpty();
    var applied = flyway.info().applied();
    assertThat(applied)
        .filteredOn(migration -> migration.getVersion() != null)
        .extracting(migration -> migration.getVersion().getVersion())
        .containsExactly("1", "2", "9000", "9002");
    for (var migration : applied) {
      if (migration.getVersion() != null) {
        System.out.printf(
            "GEN MIGRATION version=%s script=%s checksum=%s state=%s%n",
            migration.getVersion(),
            migration.getScript(),
            migration.getChecksum(),
            migration.getState());
      }
    }
    tx(
        "H0.validation",
        () -> {
          TxId expected = jdbcIdentity();
          assertThat(em.unwrap(Session.class).doReturningWork(this::identity)).isEqualTo(expected);
          assertThat(
                  jdbc.queryForMap(
                      """
                      select data_type,is_identity from information_schema.columns
                      where table_schema=? and table_name=? and column_name='id'
                      """,
                      SCHEMA,
                      TABLE))
              .containsEntry("data_type", "bigint")
              .containsEntry("is_identity", "YES");
          assertThat(jdbc.queryForObject("select pg_postmaster_start_time()::text", String.class))
              .isEqualTo(serverIdentity);
          String driver =
              jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDriverVersion());
          System.out.printf(
              "GEN H0 java=%s boot=%s spring=%s hibernate=%s flywayJar=%s driver=%s manager=%s "
                  + "validate=true identity=%s tx=%s poolMax=%d poolTimeoutMs=%d%n",
              System.getProperty("java.version"),
              SpringBootVersion.getVersion(),
              SpringVersion.getVersion(),
              Version.getVersionString(),
              Flyway.class.getProtectionDomain().getCodeSource().getLocation().getPath(),
              driver,
              manager.getClass().getSimpleName(),
              SCHEMA,
              expected,
              pool.getMaximumPoolSize(),
              pool.getConnectionTimeout());
          return null;
        });
    result("H0", "SUPPORTED", null);
  }

  private TxId jdbcIdentity() {
    return jdbc.execute((ConnectionCallback<TxId>) this::identity);
  }

  private TxId identity(Connection c) throws SQLException {
    try (var s = c.createStatement();
        var rs =
            s.executeQuery(
                "select pg_backend_pid(),txid_current(),current_setting('transaction_isolation'),current_schema()")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(3)).isEqualTo("read committed");
      assertThat(rs.getString(4)).isEqualTo(SCHEMA);
      assertThat(c.getAutoCommit()).isFalse();
      return new TxId(rs.getInt(1), rs.getLong(2));
    }
  }

  private OpenTx openTx(int pid) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select xact_start,backend_xid::text xid from pg_stat_activity where pid=?", pid);
    return rows.isEmpty()
        ? null
        : new OpenTx(
            instant(rows.getFirst().get("xact_start")), (String) rows.getFirst().get("xid"));
  }

  private Instant dbNow() {
    return jdbc.queryForObject(
        "select clock_timestamp()",
        (rs, ignored) -> rs.getObject(1, OffsetDateTime.class).toInstant());
  }

  private <T> T tx(String actor, Supplier<T> action) {
    observe(
        !TransactionSynchronizationManager.isActualTransactionActive(),
        "unexpected outer test transaction");
    TransactionTemplate template = new TransactionTemplate(manager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setTimeout(30);
    AtomicInteger completion = new AtomicInteger(TransactionSynchronization.STATUS_UNKNOWN);
    try {
      T output =
          template.execute(status -> {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                  @Override
                  public void afterCompletion(int status) {
                    completion.set(status);
                  }
                });
            jdbc.execute("set local statement_timeout='15s'");
            return action.get();
          });
      observe(
          completion.get() == TransactionSynchronization.STATUS_COMMITTED, "commit not confirmed");
      System.out.printf("GEN TX COMMITTED actor=%s%n", actor);
      return output;
    } catch (RuntimeException | Error failure) {
      System.out.printf(
          "GEN TX FAILED actor=%s completion=%d sqlstate=%s type=%s message=%s%n",
          actor,
          completion.get(),
          sqlState(failure),
          failure.getClass().getSimpleName(),
          failure.getMessage());
      if (failure instanceof SuccessCasZero
          && completion.get() != TransactionSynchronization.STATUS_ROLLED_BACK) {
        throw new Finding("INCONCLUSIVE", "rollback completion was not confirmed", failure);
      }
      throw failure;
    }
  }

  private static <T> T publishFailure(CompletableFuture<?> ready, Supplier<T> work) {
    try {
      return work.get();
    } catch (RuntimeException | Error failure) {
      ready.completeExceptionally(failure);
      throw failure;
    }
  }

  private static void awaitRelease(CountDownLatch release) {
    try {
      observe(release.await(15, TimeUnit.SECONDS), "A release watchdog");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new Finding("INCONCLUSIVE", "A interrupted before CAS", failure);
    }
  }

  private static <T> T resultOf(Future<T> future) throws Exception {
    try {
      return future.get(15, TimeUnit.SECONDS);
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof Error error) {
        throw error;
      }
      if (failure.getCause() instanceof Exception exception) {
        throw exception;
      }
      throw new Finding("INCONCLUSIVE", "worker failure", failure);
    }
  }

  private static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static Instant instant(Object value) {
    return value == null ? null : ((Timestamp) value).toInstant();
  }

  private static void observe(boolean condition, String reason) {
    if (!condition) {
      throw new Finding("INCONCLUSIVE", reason);
    }
  }

  private static void invariant(boolean condition, String reason) {
    if (!condition) {
      throw new Finding("COUNTEREXAMPLE", reason);
    }
  }

  private static String verdict(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof Finding finding) {
        return finding.verdict;
      }
      if (cause instanceof CannotCreateTransactionException
          || cause instanceof SQLTransientConnectionException) {
        return "BLOCKED";
      }
    }
    return "INCONCLUSIVE";
  }

  private static String sqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && sql.getSQLState() != null) {
        return sql.getSQLState();
      }
    }
    return "none";
  }

  private static void result(String phase, String verdict, Throwable failure) {
    System.out.printf(
        "GEN RESULT %s %s sqlstate=%s reason=%s%n",
        phase, verdict, sqlState(failure), failure == null ? "-" : failure.getMessage());
  }

  private record Seed(long id, long user, UUID key, long reward) {}

  private record TxId(int pid, long xid) {}

  private record OpenTx(Instant started, String xid) {}

  private record Paused(TxId tx, OpenTx active, Business created) {}

  private record Footprint(long rooms, long games, long choices) {}

  private record Business(long room, long game, List<Long> choices, long correct) {}

  private record Recheck(
      Map<String, Object> row,
      Instant decision,
      Instant expiry,
      boolean takeover,
      boolean replace) {}

  private static final class Finding extends AssertionError {
    private final String verdict;

    private Finding(String verdict, String reason) {
      super(reason);
      this.verdict = verdict;
    }

    private Finding(String verdict, String reason, Throwable cause) {
      super(reason, cause);
      this.verdict = verdict;
    }
  }

  private static final class SuccessCasZero extends RuntimeException {
    private final Business created;

    private SuccessCasZero(Business created) {
      super("intentional success CAS zero after business flush");
      this.created = created;
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {

    @Bean
    Clock clock() {
      return Clock.fixed(roomClock, ZoneOffset.UTC);
    }
  }
}

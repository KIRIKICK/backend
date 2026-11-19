package org.kirikick.spike.publication.singletx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.kirikick.api.ApiApplication;
import org.kirikick.api.auth.domain.SignupApplication;
import org.kirikick.api.support.JpaSliceTest;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.spike.n1.N1JpaTestConfiguration;
import org.kirikick.spike.n1.game.domain.Game;
import org.kirikick.spike.n1.game.service.GameAssemblyService;
import org.kirikick.spike.n1.reward.domain.RewardPreset;
import org.kirikick.spike.n1.reward.repository.RewardPresetRepository;
import org.kirikick.spike.n1.room.domain.Room;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Local-only: no product mapping, no production migration, and no network-failure claim. */
@JpaSliceTest
@ContextConfiguration(classes = {ApiApplication.class, N1JpaTestConfiguration.class})
@ActiveProfiles("test")
@EntityScan(
    basePackageClasses = {
      User.class,
      SignupApplication.class,
      Room.class,
      Game.class,
      RewardPreset.class
    })
@Import({
  GameAssemblyService.class,
  PublicationSingleTransactionSpikePostgresTest.ClockConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(value = 40, unit = TimeUnit.SECONDS)
class PublicationSingleTransactionSpikePostgresTest {
  private static final String SCHEMA =
      "pub_single_" + UUID.randomUUID().toString().replace("-", "");
  private static final String TABLE = "publication_single_tx_records";
  private static final Instant NOW = Instant.parse("2026-11-18T04:00:00Z");
  private static final long GRACE_SECONDS = 47;
  private static final long SUCCESS_RETENTION_SECONDS = 30 * 60;
  private static String jdbcUrl;
  private static Properties credentials;

  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private EntityManager entityManager;
  @Autowired private Flyway flyway;
  @Autowired private GameAssemblyService assembly;
  @Autowired private UserRepository users;
  @Autowired private RewardPresetRepository rewards;

  private static String serverIdentity;
  private static final String SERVER_IDENTITY =
      "select current_database() || '/' || version() || '/' || inet_server_addr()::text || ':' || inet_server_port() || '/' || pg_postmaster_start_time()::text";

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws SQLException {
    var container = PostgresTestDatabase.container();
    jdbcUrl = container.getJdbcUrl();
    credentials = new Properties();
    credentials.setProperty("user", container.getUsername());
    credentials.setProperty("password", container.getPassword());
    credentials.setProperty("connectTimeout", "5");
    credentials.setProperty("socketTimeout", "15");
    URI endpoint = URI.create(jdbcUrl.substring("jdbc:".length()));
    assertThat(container.isRunning()).isTrue();
    assertThat(endpoint.getHost()).isIn("localhost", "127.0.0.1", "[::1]");
    assertThat(endpoint.getPort()).isEqualTo(container.getMappedPort(5432));
    assertThat(endpoint.getPath()).isEqualTo("/" + container.getDatabaseName());
    try (Connection observer = DriverManager.getConnection(jdbcUrl, credentials);
        var statement =
            observer.prepareStatement(
                "select version(), current_setting('server_version_num')::int, current_database(), "
                    + "inet_server_port(), pg_postmaster_start_time()::text, "
                    + "exists(select 1 from pg_namespace where nspname=?)")) {
      statement.setString(1, SCHEMA);
      try (var rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).startsWith("PostgreSQL 17.");
        assertThat(rs.getInt(2)).isBetween(170000, 179999);
        assertThat(rs.getString(3)).isEqualTo(container.getDatabaseName());
        assertThat(rs.getInt(4)).isEqualTo(5432);
        assertThat(rs.getString(5)).isNotBlank();
        assertThat(rs.getBoolean(6)).isFalse();
      }
    }
    try (Connection check = DriverManager.getConnection(jdbcUrl, credentials);
        var statement = check.createStatement();
        var row = statement.executeQuery(SERVER_IDENTITY)) {
      assertThat(row.next()).isTrue();
      serverIdentity = row.getString(1);
      log(
          "PRE_FLYWAY server=%s container=%s image=%s schemaAbsent=%s",
          serverIdentity,
          container.getContainerId(),
          container.getContainerInfo().getImageId(),
          SCHEMA);
    }
    registry.add("spring.datasource.hikari.data-source-properties.connectTimeout", () -> "3");
    registry.add("spring.datasource.hikari.data-source-properties.socketTimeout", () -> "15");
    registry.add("spring.flyway.url", () -> jdbcUrl);
    registry.add("spring.flyway.user", container::getUsername);
    registry.add("spring.flyway.password", container::getPassword);
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.schemas", () -> SCHEMA);
    registry.add("spring.flyway.default-schema", () -> SCHEMA);
    registry.add(
        "spring.flyway.locations",
        () ->
            "classpath:db/spike/baseline,classpath:db/spike/n1,classpath:db/spike/publication-single-transaction");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    registry.add(
        "spring.datasource.hikari.connection-init-sql", () -> "set search_path to " + SCHEMA);
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
    registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    registry.add("spring.datasource.hikari.connection-timeout", () -> 3000);
    registry.add("spring.jpa.show-sql", () -> false);
  }

  private record Seed(long user, long reward, UUID key, String text) {
    Seed withText(String next) {
      return new Seed(user, reward, key, next);
    }
  }

  private record Tx(int pid, String xid) {}

  private record Receipt(String meaning, long game, Instant expiry) {}

  private record Business(long room, long game, List<Long> choices, long correct, Instant opened) {}

  private record Outcome(String kind, Long game) {}

  private enum Claim {
    UPSERT,
    PLAIN,
    CTE
  }

  private enum Pause {
    NONE,
    AFTER_CLAIM,
    AFTER_FLUSH
  }

  private static final String INSERT =
      "insert into " + TABLE + " (user_id, request_key, input_text) values (?, ?, ?)";
  private static final String UPSERT =
      INSERT + " on conflict on constraint uk_single_tx_user_key do nothing";
  private static final String RETURNING = " returning user_id";
  private static final String LOOKUP = " from " + TABLE + " where user_id=? and request_key=?";
  private static final String READ = "select input_text,game_id,expires_at" + LOOKUP;
  private static final String CLAIM_CTE =
      "with claimed as ("
          + UPSERT
          + RETURNING
          + ") select user_id from claimed union all select user_id"
          + LOOKUP;
  private static boolean workersDrained = true;

  private static final class Actor {
    final String name;
    final String tag;
    volatile Tx tx;
    volatile Business business;
    volatile int completion = TransactionSynchronization.STATUS_UNKNOWN;
    volatile long ended;
    volatile String phase;
    final CountDownLatch parked = new CountDownLatch(1);
    final CountDownLatch resume = new CountDownLatch(1);

    Actor(String name) {
      this.name = name;
      tag = "/* single " + name + " " + UUID.randomUUID() + " */ ";
    }

    void pause() {
      parked.countDown();
      await(resume);
    }
  }

  @BeforeEach
  void verifiesHarness() throws SQLException {
    if (!workersDrained) {
      throw new AssertionError("BLOCKED: preceding workers or pool did not drain");
    }
    assertThat(Runtime.version().feature()).isEqualTo(21);
    assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
    assertThat(((JpaTransactionManager) transactionManager).getDataSource()).isSameAs(dataSource);
    assertThat(AopUtils.isAopProxy(assembly)).isTrue();
    assertThat(pool().getMaximumPoolSize()).isEqualTo(2);
    assertThat(pool().getJdbcUrl()).isEqualTo(jdbcUrl);
    try (Connection flywayConnection = flyway.getConfiguration().getDataSource().getConnection()) {
      assertThat(on(flywayConnection).queryForObject(SERVER_IDENTITY, String.class))
          .isEqualTo(serverIdentity);
    }
    assertThat(entityManager.getEntityManagerFactory().getProperties())
        .containsEntry("hibernate.hbm2ddl.auto", "validate");
    assertThat(flyway.info().pending()).isEmpty();
    flyway.validate();
    assertThat(flyway.info().applied())
        .filteredOn(info -> info.getVersion() != null)
        .extracting(info -> info.getVersion().getVersion())
        .containsExactly("1", "2", "9000", "9003");
    for (var migration : flyway.info().applied()) {
      if (migration.getVersion() != null) {
        log("H0 migration=%s checksum=%s", migration.getVersion(), migration.getChecksum());
      }
    }
    assertThat(
            jdbc.queryForList(
                """
                select pg_get_constraintdef(oid) from pg_constraint
                where conrelid='publication_single_tx_records'::regclass
                  and conname='uk_single_tx_user_key' and contype='u' and not condeferrable
                """,
                String.class))
        .containsExactly("UNIQUE (user_id, request_key)");
    assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);
    assertThat(jdbc.queryForObject(SERVER_IDENTITY, String.class)).isEqualTo(serverIdentity);
    assertIdle();
    log("H0 SUPPORTED schema=%s server=%s pool=%s max=2", SCHEMA, serverIdentity, poolId());
  }

  @Test
  @DisplayName("S1: 실제 동일 키 대기 후 새 SQL 문장으로 커밋된 성공을 복구한다")
  void commitAndRecover() throws Exception {
    race("S1-upsert", Claim.UPSERT, false, false);
    race("S1-cte", Claim.CTE, false, false);
    race("S1-plain", Claim.PLAIN, false, false);
  }

  @Test
  @DisplayName("S2: 실제 업무와 성공 기록을 함께 롤백하고 후행 INSERT가 직접 선점한다")
  void rollbackAndReclaim() throws Exception {
    race("S2-upsert", Claim.UPSERT, true, false);
    race("S2-plain", Claim.PLAIN, true, false);
    try (Connection observer = observer()) {
      var o = on(observer);
      Seed seed = seed("domain retry");
      var denied = new Actor("S2-domain");
      assertThatThrownBy(
              () -> publish(denied, seed, Claim.UPSERT, 4000, Pause.NONE, false, true, false))
          .isInstanceOf(GenerationDenied.class);
      assertRolledBack(o, denied);
      assertEmpty(o, seed);
      var retry =
          publish(new Actor("S2-retry"), seed, Claim.UPSERT, 4000, Pause.NONE, false, true, true);
      assertThat(retry.kind()).isEqualTo("created");
      assertSuccess(o, seed, retry.game());
    }
    log("S2-domain SUPPORTED rollback_then_same_input_rejudged");
  }

  @Test
  @DisplayName("S4: 다른 의미는 커밋된 성공에만 불일치이고 롤백 뒤에는 새 판단을 받는다")
  void differentMeaning() throws Exception {
    race("S4-commit", Claim.UPSERT, false, true);
    race("S4-rollback", Claim.UPSERT, true, true);
  }

  private void race(String label, Claim mode, boolean rollback, boolean changed) throws Exception {
    Seed aSeed = seed("original");
    Seed bSeed = changed ? aSeed.withText("different") : aSeed;
    Actor a = new Actor(label + "-A");
    Actor b = new Actor(label + "-B");
    var workers = Executors.newFixedThreadPool(2);
    try (Connection observer = observer()) {
      JdbcTemplate o = on(observer);
      var first =
          workers.submit(
              () -> publish(a, aSeed, Claim.UPSERT, 4000, Pause.AFTER_FLUSH, rollback, true, true));
      await(a.parked);
      assertThat(a.business).isNotNull();
      assertEmpty(o, aSeed);
      var second =
          workers.submit(() ->
              publish(
                  b,
                  bSeed,
                  mode,
                  4000,
                  rollback ? Pause.AFTER_CLAIM : Pause.NONE,
                  false,
                  true,
                  true));
      waitForLock(o, a, b, "transactionid", 3000);
      a.resume.countDown();
      if (rollback) {
        assertThatThrownBy(() -> result(first)).isInstanceOf(InjectedRollback.class);
        assertRolledBack(o, a);
        await(b.parked);
        assertEmpty(o, aSeed);
        assertAbsent(o, a.business);
        b.resume.countDown();
        Outcome winner = result(second);
        assertThat(winner.kind()).isEqualTo("created");
        assertThat(winner.game()).isNotEqualTo(a.business.game());
        assertSuccess(o, bSeed, winner.game());
      } else {
        Outcome winner = result(first);
        assertThat(winner.kind()).isEqualTo("created");
        Outcome recovery;
        if (mode == Claim.PLAIN) {
          SQLException error = expectPg(() -> result(second), "23505");
          assertThat(pgField(error, "getConstraint")).isEqualTo("uk_single_tx_user_key");
          assertThat(pgField(error, "getTable")).isEqualTo(TABLE);
          assertThat(b.phase).isEqualTo("claim");
          assertRolledBack(o, b);
          assertIdle();
          Actor replay = new Actor(label + "-fresh");
          recovery = tx(replay, () -> recover(bSeed, true));
          assertThat(replay.tx.xid()).isNotEqualTo(b.tx.xid());
        } else {
          recovery = result(second);
        }
        assertThat(recovery.kind()).isEqualTo(changed ? "mismatch" : "recovered");
        assertThat(recovery.game()).isEqualTo(changed ? null : winner.game());
        assertThat(b.business).isNull();
        assertSuccess(o, aSeed, winner.game());
      }
      log("%s SUPPORTED owner=%s contender=%s Bphase=%s", label, a.tx, b.tx, b.phase);
    } finally {
      drain(workers, a, b);
    }
  }

  @Test
  @DisplayName("S3: B rollback 뒤 같은 2슬롯 풀의 probe가 A 점유 중 반환 슬롯을 얻는다")
  void timedOutClaimReturnsSamePoolSlot() throws Exception {
    Seed seed = seed("timed out");
    Actor a = new Actor("S3-A");
    Actor b = new Actor("S3-B");
    var workers = Executors.newFixedThreadPool(3);
    try (Connection observer = observer()) {
      JdbcTemplate o = on(observer);
      int observerPid = o.queryForObject("select pg_backend_pid()", Integer.class);
      var first =
          workers.submit(
              () -> publish(a, seed, Claim.UPSERT, 4000, Pause.AFTER_FLUSH, false, true, true));
      await(a.parked);
      assertEmpty(o, seed);
      var second =
          workers.submit(() -> publish(b, seed, Claim.UPSERT, 2000, Pause.NONE, false, true, true));
      long observed = waitForLock(o, a, b, "transactionid", 1500);
      assertThat(observerPid).isNotIn(a.tx.pid(), b.tx.pid());
      assertThat(pool().getHikariPoolMXBean().getActiveConnections()).isEqualTo(2);
      var ready = new CountDownLatch(1);
      var probe =
          workers.submit(() -> {
            var samePool = pool();
            long requested = System.nanoTime();
            ready.countDown();
            try (Connection slot = samePool.getConnection()) {
              long acquired = System.nanoTime();
              var viaPool = on(slot);
              assertThat(viaPool.queryForObject("select 1", Integer.class)).isEqualTo(1);
              assertThat(viaPool.queryForObject("show lock_timeout", String.class)).isEqualTo("0");
              int pid = viaPool.queryForObject("select pg_backend_pid()", Integer.class);
              assertThat(openXid(o, a.tx.pid())).isEqualTo(a.tx.xid());
              return new Probe(requested, acquired, pid, System.identityHashCode(samePool));
            }
          });
      await(ready);
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(900);
      while (pool().getHikariPoolMXBean().getThreadsAwaitingConnection() != 1
          && !probe.isDone()
          && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
      assertThat(probe.isDone())
          .as("INCONCLUSIVE: probe acquired without pending sample")
          .isFalse();
      assertThat(pool().getHikariPoolMXBean().getThreadsAwaitingConnection()).isEqualTo(1);
      long pending = System.nanoTime();
      SQLException error = expectPg(() -> result(second), "55P03");
      assertThat(b.phase).isEqualTo("claim");
      Probe acquired = result(probe);
      assertThat(acquired.pool()).isEqualTo(poolId());
      assertThat(acquired.pid()).isNotIn(a.tx.pid(), observerPid);
      assertThat(acquired.requested()).isGreaterThan(observed).isLessThanOrEqualTo(pending);
      assertThat(b.ended).isGreaterThan(pending).isLessThan(acquired.acquired());
      assertRolledBack(o, b);
      assertThat(openXid(o, a.tx.pid())).isEqualTo(a.tx.xid());
      assertThat(pool().getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
      assertThat(b.business).isNull();
      log(
          "S3 ORDER poolA/B/probe=%d O=DriverManager:%d A=%s B=%s probePid=%d "
              + "wait=%d request=%d pending=%d rollback=%d acquired=%d",
          poolId(),
          observerPid,
          a.tx,
          b.tx,
          acquired.pid(),
          observed,
          acquired.requested(),
          pending,
          b.ended,
          acquired.acquired());
      assertEmpty(o, seed);
      a.resume.countDown();
      Outcome owner = result(first);
      var replay =
          publish(new Actor("S3-retry"), seed, Claim.UPSERT, 4000, Pause.NONE, false, true, true);
      assertThat(replay).isEqualTo(new Outcome("recovered", owner.game()));
      assertSuccess(o, seed, owner.game());
      log(
          "S3 SUPPORTED observed=unique blocker productSignals=claim/%s mapping=NOT_SUPPORTED",
          error.getSQLState());
    } finally {
      drain(workers, a, b);
    }
  }

  private record Probe(long requested, long acquired, int pid, int pool) {}

  @Test
  @DisplayName("대조: 같은 claim 문장의 relation 락 55P03은 동일 키 경합이 아니다")
  void relationTimeoutIsNotUniqueWait() throws Exception {
    Seed seed = seed("relation");
    Actor a = new Actor("relation-A");
    Actor b = new Actor("relation-B");
    var workers = Executors.newFixedThreadPool(2);
    try (Connection observer = observer()) {
      JdbcTemplate o = on(observer);
      var holder =
          workers.submit(() ->
              tx(
                  a,
                  () -> {
                    jdbc.execute("lock table " + TABLE + " in access exclusive mode");
                    a.pause();
                    return true;
                  }));
      await(a.parked);
      var blocked =
          workers.submit(() -> publish(b, seed, Claim.UPSERT, 2000, Pause.NONE, false, true, true));
      waitForLock(o, a, b, "relation", 1500);
      expectPg(() -> result(blocked), "55P03");
      assertRolledBack(o, b);
      assertThat(b.phase).isEqualTo("claim");
      a.resume.countDown();
      assertThat(result(holder)).isTrue();
      assertEmpty(o, seed);
      log(
          "CONTROL observed=relation productSignals=claim/55P03 retrySafety=no_write mapping=NOT_KEY_WAIT");
    } finally {
      drain(workers, a, b);
    }
  }

  @Test
  @DisplayName("대조: statement timeout·풀 대여 실패·다른 제약은 키 경합 오류와 다르다")
  void otherErrorsRemainDistinct() throws Exception {
    try (Connection observer = observer()) {
      JdbcTemplate o = on(observer);
      Actor timeout = new Actor("statement-timeout");
      expectPg(
          () ->
              tx(
                  timeout,
                  () -> {
                    jdbc.execute("set local statement_timeout='100ms'");
                    jdbc.execute("select pg_sleep(2)");
                    return true;
                  }),
          "57014");
      assertRolledBack(o, timeout);
      log(
          "CONTROL observed=statement_timeout productSignals=work/57014 retrySafety=rollback mapping=NOT_KEY_WAIT");

      try (Connection first = pool().getConnection();
          Connection second = pool().getConnection()) {
        assertThat(first).isNotSameAs(second);
        assertThatThrownBy(() -> pool().getConnection())
            .isInstanceOf(SQLTransientConnectionException.class);
      }
      assertIdle();
      log(
          "CONTROL observed=pool_checkout_timeout productSignals=no_SQL_statement retrySafety=no_start mapping=NOT_KEY_WAIT");

      Seed seed = seed("other constraint");
      Outcome success =
          publish(
              new Actor("other-unique-A"), seed, Claim.UPSERT, 4000, Pause.NONE, false, true, true);
      Seed duplicate = new Seed(seed.user(), seed.reward(), UUID.randomUUID(), seed.text());
      Actor loser = new Actor("other-unique-B");
      SQLException violation =
          expectPg(
              () ->
                  tx(
                      loser,
                      () -> {
                        jdbc.update(
                            "insert into "
                                + TABLE
                                + " (user_id,request_key,input_text,game_id,expires_at) values (?,?,?,?,?)",
                            duplicate.user(),
                            duplicate.key(),
                            duplicate.text(),
                            success.game(),
                            OffsetDateTime.ofInstant(NOW.plusSeconds(90000), ZoneOffset.UTC));
                        return true;
                      }),
              "23505");
      assertThat(pgField(violation, "getConstraint")).isEqualTo("uk_single_tx_game");
      assertRolledBack(o, loser);
      assertThat(find(o, duplicate)).isEmpty();
      assertSuccess(o, seed, success.game());
      log(
          "CONTROL observed=other_unique productSignals=work/23505/uk_single_tx_game retrySafety=rollback mapping=NOT_KEY_WAIT");
    }
  }

  @Test
  @DisplayName("S5: 실제 커밋 뒤 결과 전달만 생략하면 같은 묶음으로 복구한다")
  void responseOmissionIsNotCommitFailure() throws Exception {
    Seed seed = seed("response");
    try (Connection observer = observer()) {
      JdbcTemplate o = on(observer);
      Outcome[] caller = {null};
      assertThatThrownBy(() -> caller[0] = suppressResponse(o, seed))
          .isInstanceOf(ResponseOmitted.class);
      assertThat(caller[0]).isNull();
      Receipt before = find(o, seed).getFirst();
      Outcome replay =
          publish(new Actor("S5-retry"), seed, Claim.UPSERT, 4000, Pause.NONE, false, true, true);
      assertThat(replay).isEqualTo(new Outcome("recovered", before.game()));
      assertThat(find(o, seed)).containsExactly(before);
      assertSuccess(o, seed, before.game());
      log(
          "S5 SUPPORTED confirmed_commit_before_local_omission recovered=%d no_network_fault",
          before.game());
    }
  }

  private Outcome suppressResponse(JdbcTemplate o, Seed seed) {
    Outcome committed =
        publish(new Actor("S5-A"), seed, Claim.UPSERT, 4000, Pause.NONE, false, true, true);
    assertSuccess(o, seed, committed.game());
    throw new ResponseOmitted();
  }

  @Test
  @DisplayName("S6: 현재 접근 인가와 종료된 방의 신규 출제 제한을 구분한다")
  void recoveryAuthorizationIsIndependentOfCreation() throws Exception {
    Seed seed = seed("authorization");
    try (Connection observer = observer()) {
      JdbcTemplate o = on(observer);
      Outcome first =
          publish(new Actor("S6-create"), seed, Claim.UPSERT, 4000, Pause.NONE, false, true, true);
      Receipt before = find(o, seed).getFirst();
      assertThatThrownBy(() ->
              publish(
                  new Actor("S6-denied"),
                  seed.withText("different"),
                  Claim.UPSERT,
                  4000,
                  Pause.NONE,
                  false,
                  false,
                  false))
          .isInstanceOf(RecoveryDenied.class);
      Outcome replay =
          publish(
              new Actor("S6-ended-recover"),
              seed,
              Claim.UPSERT,
              4000,
              Pause.NONE,
              false,
              true,
              false);
      assertThat(replay).isEqualTo(new Outcome("recovered", first.game()));
      Seed fresh = new Seed(seed.user(), seed.reward(), UUID.randomUUID(), seed.text());
      assertThatThrownBy(() ->
              publish(
                  new Actor("S6-ended-create"),
                  fresh,
                  Claim.UPSERT,
                  4000,
                  Pause.NONE,
                  false,
                  true,
                  false))
          .isInstanceOf(GenerationDenied.class);
      assertThat(find(o, fresh)).isEmpty();
      assertThat(find(o, seed)).containsExactly(before);
      assertSuccess(o, seed, first.game());
      log("S6 SUPPORTED synthetic_policy recovery_denied_no_payload ended_generation_denied");
    }
  }

  private Outcome publish(
      Actor actor,
      Seed seed,
      Claim mode,
      int lockMillis,
      Pause pause,
      boolean rollback,
      boolean mayRecover,
      boolean mayGenerate) {
    return tx(
        actor,
        () -> {
          String original = jdbc.queryForObject("show lock_timeout", String.class);
          jdbc.queryForObject(
              "select set_config('lock_timeout', ?, true)", String.class, lockMillis + "ms");
          actor.phase = "claim";
          long began = System.nanoTime();
          List<Long> rows;
          try {
            rows =
                mode == Claim.CTE
                    ? jdbc.queryForList(
                        actor.tag + CLAIM_CTE,
                        Long.class,
                        seed.user(),
                        seed.key(),
                        seed.text(),
                        seed.user(),
                        seed.key())
                    : jdbc.queryForList(
                        actor.tag + (mode == Claim.PLAIN ? INSERT : UPSERT) + RETURNING,
                        Long.class,
                        seed.user(),
                        seed.key(),
                        seed.text());
            log(
                "%s claim mode=%s returned=%s ms=%d %s",
                actor.name,
                mode,
                rows,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began),
                actor.tx);
          } catch (RuntimeException error) {
            log(
                "%s claim failed budget=%dms elapsed=%dms",
                actor.name, lockMillis, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began));
            throw error;
          }
          jdbc.queryForObject("select set_config('lock_timeout', ?, true)", String.class, original);
          assertThat(identity()).isEqualTo(actor.tx);
          if (mode == Claim.CTE) assertThat(rows).as("same RC statement snapshot").isEmpty();
          if (rows.isEmpty()) {
            actor.phase = "fresh-recovery";
            Outcome recovery = recover(seed, mayRecover);
            assertThat(identity()).isEqualTo(actor.tx);
            log("%s fresh_statement recovery=%s %s", actor.name, recovery.kind(), actor.tx);
            return recovery;
          }
          assertThat(rows).containsExactly(seed.user());
          if (pause == Pause.AFTER_CLAIM) actor.pause();
          if (!mayGenerate) throw new GenerationDenied();
          var session = entityManager.unwrap(Session.class);
          assertThat(entityManager.isJoinedToTransaction()).isTrue();
          assertThat(session.<Tx>doReturningWork(this::identity)).isEqualTo(actor.tx);
          long game =
              assembly.createRoomWithFirstGame(
                  seed.user(), seed.reward(), 1, seed.text(), List.of("one", "two"), 0);
          entityManager.flush();
          assertThat(entityManager.unwrap(Session.class)).isSameAs(session);
          assertThat(session.<Tx>doReturningWork(this::identity)).isEqualTo(actor.tx);
          actor.business = readBusiness(jdbc, seed.user(), game);
          assertThat(actor.business.choices()).hasSize(2);
          assertThat(actor.business.correct()).isEqualTo(actor.business.choices().getFirst());
          Instant expires =
              actor
                  .business
                  .opened()
                  .plus(24, ChronoUnit.HOURS)
                  .plusSeconds(SUCCESS_RETENTION_SECONDS + GRACE_SECONDS);
          assertThat(
                  jdbc.update(
                      "update "
                          + TABLE
                          + " set game_id=?,expires_at=? where user_id=? and request_key=?",
                      game,
                      OffsetDateTime.ofInstant(expires, ZoneOffset.UTC),
                      seed.user(),
                      seed.key()))
              .isEqualTo(1);
          assertThat(identity()).isEqualTo(actor.tx);
          assertThat(find(jdbc, seed)).containsExactly(new Receipt(seed.text(), game, expires));
          log(
              "%s sharedJPA/JDBC=%s flush=%s expires=%s",
              actor.name, actor.tx, actor.business, expires);
          if (pause == Pause.AFTER_FLUSH) actor.pause();
          if (rollback) throw new InjectedRollback();
          return new Outcome("created", game);
        });
  }

  private Outcome recover(Seed seed, boolean allowed) {
    if (!allowed) throw new RecoveryDenied();
    List<Receipt> current = find(jdbc, seed);
    assertThat(current).hasSize(1);
    Receipt receipt = current.getFirst();
    assertThat(receipt.game()).isPositive();
    assertThat(receipt.expiry()).isAfter(NOW);
    return seed.text().equals(receipt.meaning())
        ? new Outcome("recovered", receipt.game())
        : new Outcome("mismatch", null);
  }

  private Seed seed(String meaning) {
    return tx(
        new Actor("seed"),
        () ->
            new Seed(
                users.save(User.create("singlehost", null)).getId(),
                rewards.save(RewardPreset.create("single", "test-only scope")).getId(),
                UUID.randomUUID(),
                meaning));
  }

  private List<Receipt> find(JdbcTemplate db, Seed seed) {
    return db.query(
        READ,
        (rs, ignored) ->
            new Receipt(
                rs.getString(1), rs.getLong(2), rs.getObject(3, OffsetDateTime.class).toInstant()),
        seed.user(),
        seed.key());
  }

  private Business readBusiness(JdbcTemplate db, long user, long game) {
    var choices =
        db.queryForList(
            "select id from choices where game_id=? order by position", Long.class, game);
    return db.queryForObject(
        """
        select r.id,g.correct_choice_id,r.opened_at
        from rooms r join games g on g.room_id=r.id where g.id=? and r.host_user_id=?
        """,
        (rs, ignored) ->
            new Business(
                rs.getLong(1),
                game,
                choices,
                rs.getLong(2),
                rs.getObject(3, OffsetDateTime.class).toInstant()),
        game,
        user);
  }

  private void assertEmpty(JdbcTemplate o, Seed seed) {
    assertThat(o.queryForObject("select count(*)" + LOOKUP, Integer.class, seed.user(), seed.key()))
        .isZero();
    assertThat(
            o.queryForObject(
                "select count(*) from rooms where host_user_id=?", Integer.class, seed.user()))
        .isZero();
  }

  private void assertAbsent(JdbcTemplate o, Business old) {
    assertThat(o.queryForObject("select count(*) from rooms where id=?", Integer.class, old.room()))
        .isZero();
    assertThat(o.queryForObject("select count(*) from games where id=?", Integer.class, old.game()))
        .isZero();
    for (long choice : old.choices()) {
      assertThat(o.queryForObject("select count(*) from choices where id=?", Integer.class, choice))
          .isZero();
    }
    log("observer absent rolled_back_ids=%s", old);
  }

  private void assertSuccess(JdbcTemplate o, Seed seed, long expectedGame) {
    assertThat(
            o.queryForObject(
                "select count(*) from " + TABLE + " where user_id=?", Integer.class, seed.user()))
        .isEqualTo(1);
    assertThat(
            o.queryForObject(
                "select count(*) from rooms where host_user_id=?", Integer.class, seed.user()))
        .isEqualTo(1);
    assertThat(
            o.queryForObject(
                """
                select count(*) from games g join rooms r on r.id=g.room_id where r.host_user_id=?
                """,
                Integer.class,
                seed.user()))
        .isEqualTo(1);
    Business business = readBusiness(o, seed.user(), expectedGame);
    assertThat(business.choices()).hasSize(2);
    assertThat(business.correct()).isEqualTo(business.choices().getFirst());
    Instant expectedExpiry = business.opened().plusSeconds(86400L + 1800L + 47L);
    assertThat(find(o, seed))
        .containsExactly(new Receipt(seed.text(), expectedGame, expectedExpiry));
    log("observer committed user=%s business=%s expiry=%s", seed.user(), business, expectedExpiry);
  }

  private long waitForLock(JdbcTemplate o, Actor a, Actor b, String lock, long millis)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    while (System.nanoTime() < deadline) {
      if (a.tx != null
          && b.tx != null
          && b.completion == TransactionSynchronization.STATUS_UNKNOWN) {
        var samples =
            o.queryForList(
                """
                select b.wait_event_type,b.wait_event,b.query,b.query_start,
                       a.xact_start,a.backend_xid::text owner_xid,
                       pg_blocking_pids(b.pid)::text blockers,l.locktype
                from pg_stat_activity a, pg_stat_activity b, pg_locks l
                where a.pid=? and b.pid=? and a.pid=any(pg_blocking_pids(b.pid))
                  and l.pid=b.pid and not l.granted
                """,
                a.tx.pid(),
                b.tx.pid());
        for (var sample : samples) {
          if ("Lock".equals(sample.get("wait_event_type"))
              && ((String) sample.get("query")).startsWith(b.tag)
              && a.tx.xid().equals(sample.get("owner_xid"))
              && lock.equals(sample.get("locktype"))) {
            long observed = System.nanoTime();
            log(
                "O_WAIT observer=direct poolA/B=%d A=%s B=%s %s ns=%d",
                poolId(), a.tx, b.tx, sample, observed);
            return observed;
          }
        }
      }
      Thread.sleep(10);
    }
    throw new AssertionError("INCONCLUSIVE: did not observe " + lock + ": " + a.tx + " / " + b.tx);
  }

  private void assertRolledBack(JdbcTemplate o, Actor actor) {
    assertThat(actor.completion).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
    assertThat(
            o.queryForObject(
                "select not exists (select 1 from pg_stat_activity where pid=? and xact_start is not null)",
                Boolean.class,
                actor.tx.pid()))
        .isTrue();
    log("O_ROLLBACK actor=%s %s ended=%d", actor.name, actor.tx, actor.ended);
  }

  private String openXid(JdbcTemplate o, int pid) {
    return o.queryForObject(
        "select (select backend_xid::text from pg_stat_activity where pid=?)", String.class, pid);
  }

  private <T> T tx(Actor actor, Supplier<T> work) {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setTimeout(12);
    return template.execute(status -> {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int value) {
              actor.ended = System.nanoTime();
              actor.completion = value;
              log(
                  "afterCompletion actor=%s tx=%s status=%d at=%d",
                  actor.name, actor.tx, value, actor.ended);
            }
          });
      jdbc.execute("set local statement_timeout='7s'");
      actor.tx = identity();
      log("transaction actor=%s pool=%d %s", actor.name, poolId(), actor.tx);
      return work.get();
    });
  }

  private Tx identity() {
    return jdbc.execute((ConnectionCallback<Tx>) this::identity);
  }

  private Tx identity(Connection connection) throws SQLException {
    assertThat(connection.getAutoCommit()).isFalse();
    try (var statement = connection.createStatement();
        var row =
            statement.executeQuery(
                """
                select pg_backend_pid(),pg_current_xact_id()::text,
                       current_setting('transaction_isolation'),current_schema()
                """)) {
      assertThat(row.next()).isTrue();
      assertThat(row.getString(3)).isEqualTo("read committed");
      assertThat(row.getString(4)).isEqualTo(SCHEMA);
      return new Tx(row.getInt(1), row.getString(2));
    }
  }

  private Connection observer() throws SQLException {
    Connection connection = DriverManager.getConnection(jdbcUrl, credentials);
    try {
      connection.setAutoCommit(true);
      try (var statement = connection.createStatement()) {
        statement.execute("set statement_timeout='3s'");
        statement.execute("set search_path to " + SCHEMA);
      }
      assertThat(on(connection).queryForObject(SERVER_IDENTITY, String.class))
          .isEqualTo(serverIdentity);
      return connection;
    } catch (SQLException | RuntimeException | AssertionError failure) {
      try {
        connection.close();
      } catch (SQLException close) {
        failure.addSuppressed(close);
      }
      throw failure;
    }
  }

  private JdbcTemplate on(Connection connection) {
    return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
  }

  private HikariDataSource pool() {
    return (HikariDataSource) dataSource;
  }

  private int poolId() {
    return System.identityHashCode(pool());
  }

  private void assertIdle() {
    assertThat(pool().getHikariPoolMXBean().getActiveConnections()).isZero();
    assertThat(pool().getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
  }

  private void drain(ExecutorService workers, Actor... actors) throws InterruptedException {
    workersDrained = false;
    for (Actor actor : actors) actor.resume.countDown();
    workers.shutdown();
    if (!workers.awaitTermination(12, TimeUnit.SECONDS)) {
      workers.shutdownNow();
      assertThat(workers.awaitTermination(4, TimeUnit.SECONDS))
          .as("INCONCLUSIVE: worker cleanup unproven")
          .isTrue();
    }
    assertIdle();
    workersDrained = true;
  }

  private static <T> T result(Future<T> task) throws Exception {
    try {
      return task.get(12, TimeUnit.SECONDS);
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof Exception actual) throw actual;
      if (failure.getCause() instanceof Error actual) throw actual;
      throw failure;
    }
  }

  private SQLException expectPg(ThrowingCallable operation, String state) throws Exception {
    try {
      operation.call();
    } catch (Exception error) {
      for (Throwable at = error; at != null; at = at.getCause()) {
        if (at instanceof SQLException pg) {
          assertThat(pg.getSQLState()).isEqualTo(state);
          log(
              "PGERROR state=%s constraint=%s schema=%s table=%s detail=%s context=%s chain=%s",
              pg.getSQLState(),
              pgField(pg, "getConstraint"),
              pgField(pg, "getSchema"),
              pgField(pg, "getTable"),
              pgField(pg, "getDetail"),
              pgField(pg, "getWhere"),
              error.getClass().getSimpleName());
          return pg;
        }
      }
      throw error;
    } catch (Throwable unexpected) {
      if (unexpected instanceof Error error) throw error;
      throw new AssertionError(unexpected);
    }
    throw new AssertionError("expected PostgreSQL SQLSTATE " + state);
  }

  // The PostgreSQL driver is on the runtime classpath, not the test compile classpath.
  private String pgField(SQLException error, String field) throws ReflectiveOperationException {
    Object diagnostics = error.getClass().getMethod("getServerErrorMessage").invoke(error);
    return (String) diagnostics.getClass().getMethod(field).invoke(diagnostics);
  }

  private static void await(CountDownLatch signal) {
    try {
      assertThat(signal.await(10, TimeUnit.SECONDS)).as("INCONCLUSIVE: barrier deadline").isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("INCONCLUSIVE: barrier interrupted", interrupted);
    }
  }

  private static void log(String format, Object... args) {
    System.out.println("SINGLE " + String.format(format, args));
  }

  static final class InjectedRollback extends RuntimeException {}

  static final class RecoveryDenied extends RuntimeException {}

  static final class GenerationDenied extends RuntimeException {}

  static final class ResponseOmitted extends RuntimeException {}

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {
    @Bean
    Clock spikeClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}

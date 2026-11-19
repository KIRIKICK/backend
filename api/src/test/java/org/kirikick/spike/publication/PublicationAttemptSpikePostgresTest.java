package org.kirikick.spike.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
@Import({GameAssemblyService.class, PublicationAttemptSpikePostgresTest.ClockConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PublicationAttemptSpikePostgresTest {
  private static final String SCHEMA =
      "publication_" + UUID.randomUUID().toString().replace("-", "");
  private static final Instant NOW = Instant.parse("2026-11-17T09:00:00Z");
  private static boolean s1FencePassed;
  private static boolean s1AtomicityPassed;
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
    }
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.schemas", () -> SCHEMA);
    registry.add("spring.flyway.default-schema", () -> SCHEMA);
    registry.add(
        "spring.flyway.locations",
        () -> "classpath:db/spike/baseline,classpath:db/spike/n1,classpath:db/spike/publication");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    registry.add(
        "spring.datasource.hikari.connection-init-sql", () -> "set search_path to " + SCHEMA);
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> 6);
    registry.add("spring.jpa.show-sql", () -> false);
  }

  @Test
  @Order(1)
  @DisplayName("S1-A: 인계 전 지연된 A는 무효과이고 B만 생성과 성공을 커밋한다")
  void s1StaleAttemptCannotGenerate() throws Exception {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(manager).isInstanceOf(JpaTransactionManager.class);
    assertThat(AopUtils.isAopProxy(assembly)).isTrue();
    assertThat(em.getEntityManagerFactory().getProperties())
        .containsEntry("hibernate.hbm2ddl.auto", "validate");
    flyway.validate();
    assertThat(flyway.info().pending()).isEmpty();
    assertThat(flyway.info().applied())
        .filteredOn(migration -> migration.getVersion() != null)
        .extracting(migration -> migration.getVersion().getVersion())
        .containsExactly("1", "2", "9000", "9001");
    assertThat(flyway.info().applied())
        .anySatisfy(migration ->
            assertThat(migration.getScript()).isEqualTo("V9001__publication_spike_fixture.sql"));
    try (var connection = dataSource.getConnection()) {
      assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
      assertThat(connection.getSchema()).isEqualTo(SCHEMA);
      System.out.printf(
          "SPIKE ENV JDK=%s boot=%s hibernate=%s flyway=%s driver=%s manager=%s ddl=%s%n",
          System.getProperty("java.version"),
          SpringBootVersion.getVersion(),
          Version.getVersionString(),
          Flyway.class.getProtectionDomain().getCodeSource().getLocation().getFile(),
          connection.getMetaData().getDriverVersion(),
          manager.getClass().getName(),
          em.getEntityManagerFactory().getProperties().get("hibernate.hbm2ddl.auto"));
    }
    for (var migration : flyway.info().applied()) {
      System.out.printf(
          "SPIKE MIGRATION version=%s script=%s state=%s%n",
          migration.getVersion(), migration.getScript(), migration.getState());
    }
    Seed seed = seed();
    CountDownLatch beforeGeneration = new CountDownLatch(1);
    CountDownLatch resumeA = new CountDownLatch(1);
    ExecutorService workers = Executors.newSingleThreadExecutor();
    Future<Long> a =
        workers.submit(() -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
          System.out.println("SPIKE S1-A A parked before tx: no bound connection/context");
          beforeGeneration.countDown();
          await(resumeA);
          return generate(seed, 1, 1, Fault.NONE);
        });
    try {
      await(beforeGeneration);
      makeEligible(seed);
      takeover(seed);
      assertState(seed, "PROCESSING", 2, null, 0);
      Map<String, Object> beforeA = observeRecord(seed);
      resumeA.countDown();
      assertThat(a.get(15, TimeUnit.SECONDS)).isNull();
      assertThat(observeRecord(seed)).isEqualTo(beforeA);
      assertState(seed, "PROCESSING", 2, null, 0);
      Long game = generate(seed, 2, 2, Fault.NONE);
      assertThat(game).isNotNull();
      assertState(seed, "SUCCEEDED", 2, game, 1);
      s1FencePassed = true;
      System.out.println("SPIKE RESULT S1-A SUPPORTED");
    } finally {
      resumeA.countDown();
      terminate(workers, a);
    }
  }

  @Test
  @Order(2)
  @DisplayName("S1-B: 정상 fence와 업무 flush 뒤 성공 CAS가 0건이면 업무 전체를 롤백한다")
  void s1SuccessCasZeroRollsBackEverything() {
    assertThat(s1FencePassed).as("S1-A prerequisite; no downstream experiment on failure").isTrue();
    Seed seed = seed();
    assertThatThrownBy(() -> generate(seed, 1, 999, Fault.NONE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("SUCCESS_CAS_ZERO");
    assertState(seed, "PROCESSING", 1, null, 0);
    s1AtomicityPassed = true;
    System.out.println("SPIKE RESULT S1-B SUPPORTED");
  }

  @Test
  @Order(3)
  @DisplayName("S2-A: 쓰기 전 실제 도메인 검증 실패는 부분 생성 없이 FAILED만 커밋한다")
  void s2DomainValidationBeforeWrites() {
    requireS1();
    Seed seed = seed();
    assertThat(generate(seed, 1, 1, Fault.DOMAIN)).isNull();
    assertState(seed, "FAILED", 1, null, 0);
    assertRetention(seed);
    System.out.println("SPIKE RESULT S2-A SUPPORTED");
  }

  @Test
  @Order(4)
  @DisplayName("S2-B: JPA unique 오류의 확정 롤백 후 별도 트랜잭션에서 FAILED를 기록한다")
  void s2JpaConstraintRollbackThenFailed() {
    requireS1();
    Seed seed = seed();
    failAndObserveRollback(seed, Fault.UNIQUE);
    assertThat(recordFailure(seed, 1, "FAILED")).isEqualTo(1);
    assertState(seed, "FAILED", 1, null, 0);
    assertRetention(seed);
    System.out.println("SPIKE RESULT S2-B SUPPORTED");
  }

  @Test
  @Order(5)
  @DisplayName("S2-C: 실제 User 락 timeout의 확정 롤백 후 RETRYABLE만 기록한다")
  void s2UserLockRollbackThenRetryable() throws Exception {
    requireS1();
    Seed seed = seed();
    lockFailureAndObserveRollback(seed);
    assertThat(recordFailure(seed, 1, "RETRYABLE")).isEqualTo(1);
    assertState(seed, "RETRYABLE", 1, null, 0);
    assertRetention(seed);
    System.out.println("SPIKE RESULT S2-C SUPPORTED");
  }

  @Test
  @Order(6)
  @DisplayName("S2-D: A 롤백 후 B 인계 또는 성공이면 늦은 FAILED와 RETRYABLE은 모두 0건이다")
  void s2LateFailureCannotOverwriteB() throws Exception {
    requireS1();
    for (Fault fault : List.of(Fault.UNIQUE, Fault.LOCK)) {
      for (boolean succeedB : List.of(false, true)) {
        Seed seed = seed();
        CountDownLatch rolledBack = new CountDownLatch(1);
        CountDownLatch resumeA = new CountDownLatch(1);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        Future<Integer> a =
            workers.submit(() -> {
              if (fault == Fault.UNIQUE) failAndObserveRollback(seed, fault);
              else lockFailureAndObserveRollback(seed);
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
              rolledBack.countDown();
              await(resumeA);
              return recordFailure(seed, 1, fault == Fault.UNIQUE ? "FAILED" : "RETRYABLE");
            });
        try {
          await(rolledBack);
          makeEligible(seed);
          takeover(seed);
          Long game = succeedB ? generate(seed, 2, 2, Fault.NONE) : null;
          String state = succeedB ? "SUCCEEDED" : "PROCESSING";
          assertState(seed, state, 2, game, succeedB ? 1 : 0);
          Map<String, Object> before = observeRecord(seed);
          resumeA.countDown();
          assertThat(a.get(15, TimeUnit.SECONDS)).isZero();
          assertThat(observeRecord(seed)).isEqualTo(before);
          assertState(seed, state, 2, game, succeedB ? 1 : 0);
          System.out.printf("SPIKE RESULT S2-D-%s-B-%s SUPPORTED%n", fault, state);
        } finally {
          resumeA.countDown();
          terminate(workers, a);
        }
      }
    }
  }

  @Test
  @Order(7)
  @DisplayName("가드: 기록이 없거나 PROCESSING이 아니면 업무와 상태 쓰기가 없다")
  void missingAndTerminalRecordsAreNoOps() {
    requireS1();
    Seed seed = seed();
    tx(
        "remove fixture record",
        () -> jdbc.update("delete from publication_spike_records where id=?", seed.id()));
    assertThat(generate(seed, 1, 1, Fault.NONE)).isNull();
    assertThat(recordFailure(seed, 1, "FAILED")).isZero();
    assertThat(recordFailure(seed, 1, "RETRYABLE")).isZero();
    tx(
        "missing observer",
        () -> {
          assertThat(
                  jdbc.queryForList(
                      "select * from publication_spike_records where id=?", seed.id()))
              .isEmpty();
          assertBusiness(seed, 0);
          return null;
        });
    for (String state : List.of("FAILED", "RETRYABLE", "SUCCEEDED")) {
      Seed terminal = seed();
      Long game;
      if (state.equals("SUCCEEDED")) game = generate(terminal, 1, 1, Fault.NONE);
      else {
        assertThat(recordFailure(terminal, 1, state)).isEqualTo(1);
        game = null;
      }
      Map<String, Object> before = observeRecord(terminal);
      assertThat(generate(terminal, 1, 1, Fault.NONE)).isNull();
      assertThat(recordFailure(terminal, 1, "FAILED")).isZero();
      assertThat(recordFailure(terminal, 1, "RETRYABLE")).isZero();
      assertThat(observeRecord(terminal)).isEqualTo(before);
      assertState(terminal, state, 1, game, game == null ? 0 : 1);
    }
    System.out.println("SPIKE RESULT missing/non-PROCESSING SUPPORTED");
  }

  private Seed seed() {
    return tx(
        "activation A",
        () -> {
          User user = User.create("host", null);
          RewardPreset reward = RewardPreset.create("spike", "local only");
          em.persist(user);
          em.persist(reward);
          em.flush();
          Seed seed = new Seed(UUID.randomUUID(), user.getId(), reward.getId(), UUID.randomUUID());
          assertThat(
                  jdbc.update(
                      """
                      insert into publication_spike_records(id,user_id,request_key,state,attempt,started_at)
                      values (?,?,?,'PROCESSING',1,statement_timestamp())
                      """,
                      seed.id(),
                      seed.user(),
                      seed.key()))
              .isEqualTo(1);
          return seed;
        });
  }

  private void makeEligible(Seed seed) {
    tx(
        "test-only 31-second age fixture",
        () ->
            jdbc.update(
                """
                update publication_spike_records set started_at=clock_timestamp()-interval '31 seconds'
                where id=? and state='PROCESSING' and attempt=1
                """,
                seed.id()));
  }

  private void takeover(Seed seed) {
    tx(
        "activation B",
        () -> {
          Map<String, Object> row = lockRecord(seed);
          assertThat(row.get("state")).isEqualTo("PROCESSING");
          assertThat(((Number) row.get("attempt")).longValue()).isEqualTo(1);
          assertThat(row.get("user_id")).isEqualTo(seed.user());
          assertThat(row.get("request_key")).isEqualTo(seed.key());
          assertThat(
                  jdbc.update(
                      """
                      update publication_spike_records set attempt=2,started_at=clock_timestamp()
                      where id=? and state='PROCESSING' and attempt=1
                        and started_at <= clock_timestamp()-interval '30 seconds'
                      """,
                      seed.id()))
              .isEqualTo(1);
          return null;
        });
  }

  private Long generate(Seed seed, long expected, long successExpected, Fault fault) {
    return tx(
        "generation " + expected + "/" + fault,
        () -> {
          Map<String, Object> row = lockRecord(seed);
          if (row.isEmpty()
              || !row.get("state").equals("PROCESSING")
              || ((Number) row.get("attempt")).longValue() != expected) {
            System.out.printf(
                "SPIKE FENCE no-op record=%s expected=%d observed=%s%n", seed.id(), expected, row);
            return null;
          }
          assertThat(row.get("user_id")).isEqualTo(seed.user());
          assertThat(row.get("request_key")).isEqualTo(seed.key());
          if (fault == Fault.LOCK) jdbc.execute("set local lock_timeout='250ms'");
          jdbc.queryForObject(
              "select id from users where id=? for update", Long.class, seed.user());
          if (fault == Fault.DOMAIN) {
            try {
              Game.create(
                  Room.create(
                      em.find(User.class, seed.user()), em.find(RewardPreset.class, seed.reward())),
                  1,
                  " ");
              throw new AssertionError("domain validation did not reject blank question");
            } catch (IllegalArgumentException expectedValidation) {
              assertBusiness(seed, 0);
              assertThat(finishFailure(seed, expected, "FAILED")).isEqualTo(1);
              return null;
            }
          }
          Long game =
              assembly.createRoomWithFirstGame(
                  seed.user(), seed.reward(), 1, "local spike", List.of("one", "two"), 0);
          em.flush();
          trace("after assembly flush");
          assertBusiness(seed, 1);
          if (fault == Fault.UNIQUE) {
            PublicationSpikeRecord duplicate =
                PublicationSpikeRecord.createProcessing(
                    UUID.randomUUID(), seed.user(), seed.key(), expected, NOW);
            try {
              em.persist(duplicate);
              em.flush();
              throw new AssertionError("fixture unique constraint did not fail");
            } catch (RuntimeException failure) {
              boolean rollbackOnly = currentSession().getTransaction().getRollbackOnly();
              System.out.printf(
                  "SPIKE JPA ERROR sqlstate=%s rollbackOnly=%s type=%s message=%s%n",
                  sqlState(failure),
                  rollbackOnly,
                  failure.getClass().getName(),
                  failure.getMessage());
              assertThat(sqlState(failure)).isEqualTo("23505");
              assertThat(rollbackOnly).isTrue();
              throw failure;
            }
          }
          int changed =
              jdbc.update(
                  """
                  update publication_spike_records set state='SUCCEEDED',game_id=?,
                    terminal_at=statement_timestamp(),
                    expires_at=(select r.opened_at+interval '24 hours 30 minutes'
                      from rooms r join games g on g.room_id=r.id where g.id=?)
                  where id=? and state='PROCESSING' and attempt=?
                  """,
                  game,
                  game,
                  seed.id(),
                  successExpected);
          System.out.printf(
              "SPIKE SUCCESS CAS record=%s fenceAttempt=%d casAttempt=%d rows=%d%n",
              seed.id(), expected, successExpected, changed);
          if (changed != 1) throw new IllegalStateException("SUCCESS_CAS_ZERO");
          return game;
        });
  }

  private void failAndObserveRollback(Seed seed, Fault fault) {
    AtomicBoolean caught = new AtomicBoolean();
    try {
      generate(seed, 1, 1, fault);
    } catch (RuntimeException failure) {
      assertThat(sqlState(failure)).isEqualTo("23505");
      caught.set(true);
      System.out.println(
          "SPIKE rollback confirmed: generation callback returned exceptionally, SQLSTATE 23505");
    }
    assertThat(caught).isTrue();
    assertState(seed, "PROCESSING", 1, null, 0);
  }

  private void lockFailureAndObserveRollback(Seed seed) throws Exception {
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService workers = Executors.newSingleThreadExecutor();
    Future<Void> holder =
        workers.submit(() ->
            tx(
                "User lock holder",
                () -> {
                  jdbc.queryForObject(
                      "select id from users where id=? for update", Long.class, seed.user());
                  locked.countDown();
                  await(release);
                  return null;
                }));
    try {
      await(locked);
      assertThatThrownBy(() -> generate(seed, 1, 1, Fault.LOCK))
          .satisfies(failure -> {
            System.out.printf(
                "SPIKE LOCK ERROR sqlstate=%s type=%s%n",
                sqlState(failure), failure.getClass().getName());
            assertThat(sqlState(failure)).isEqualTo("55P03");
          });
      assertState(seed, "PROCESSING", 1, null, 0);
      System.out.println("SPIKE rollback confirmed before separate RETRYABLE transaction");
    } finally {
      release.countDown();
      try {
        holder.get(15, TimeUnit.SECONDS);
      } finally {
        terminate(workers, holder);
      }
    }
  }

  private int recordFailure(Seed seed, long attempt, String state) {
    return tx("post-rollback " + state, () -> finishFailure(seed, attempt, state));
  }

  private int finishFailure(Seed seed, long attempt, String state) {
    assertThat(state).isIn("FAILED", "RETRYABLE");
    int changed =
        jdbc.update(
            """
            update publication_spike_records set state=?,terminal_at=statement_timestamp(),
              blocked_until=case when ?='FAILED' then statement_timestamp()+interval '30 minutes' end,
              expires_at=statement_timestamp()+case when ?='FAILED' then interval '1 hour' else interval '30 minutes' end
            where id=? and state='PROCESSING' and attempt=?
            """,
            state,
            state,
            state,
            seed.id(),
            attempt);
    System.out.printf(
        "SPIKE FAILURE CAS record=%s attempt=%d state=%s rows=%d%n",
        seed.id(), attempt, state, changed);
    return changed;
  }

  private Map<String, Object> lockRecord(Seed seed) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select * from publication_spike_records where id=? for update", seed.id());
    return rows.isEmpty() ? Map.of() : rows.getFirst();
  }

  private Map<String, Object> observeRecord(Seed seed) {
    return tx(
        "fresh record observer",
        () -> jdbc.queryForMap("select * from publication_spike_records where id=?", seed.id()));
  }

  private void assertState(Seed seed, String state, long attempt, Long game, int business) {
    tx(
        "fresh committed observer",
        () -> {
          Map<String, Object> row =
              jdbc.queryForMap("select * from publication_spike_records where id=?", seed.id());
          assertThat(row.get("state")).isEqualTo(state);
          assertThat(((Number) row.get("attempt")).longValue()).isEqualTo(attempt);
          assertThat(row.get("game_id")).isEqualTo(game);
          assertBusiness(seed, business);
          if (game != null) {
            assertThat(
                    jdbc.queryForObject(
                        """
                        select count(*) from games g join rooms r on r.id=g.room_id
                        join choices c on c.id=g.correct_choice_id and c.game_id=g.id
                        where g.id=? and r.host_user_id=? and r.opened_at is not null
                          and c.position=0 and c.text_snapshot='one'
                        """,
                        Integer.class,
                        game,
                        seed.user()))
                .isEqualTo(1);
          }
          System.out.printf(
              "SPIKE OBSERVER record=%s state=%s attempt=%d game=%s business=%d/%d/%d%n",
              seed.id(), state, attempt, game, business, business, business * 2);
          return null;
        });
  }

  private void assertBusiness(Seed seed, int rooms) {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rooms where host_user_id=?", Integer.class, seed.user()))
        .isEqualTo(rooms);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from games g join rooms r on r.id=g.room_id where r.host_user_id=?
                """,
                Integer.class,
                seed.user()))
        .isEqualTo(rooms);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from choices c join games g on g.id=c.game_id
                join rooms r on r.id=g.room_id where r.host_user_id=?
                """,
                Integer.class,
                seed.user()))
        .isEqualTo(rooms * 2);
  }

  private void assertRetention(Seed seed) {
    tx(
        "fresh expiry observer",
        () -> {
          assertThat(
                  jdbc.queryForObject(
                      """
                      select case when state='FAILED' then
                        blocked_until-terminal_at=interval '30 minutes'
                        and expires_at-terminal_at=interval '1 hour' and expires_at>=blocked_until
                      else blocked_until is null and expires_at-terminal_at=interval '30 minutes' end
                      from publication_spike_records where id=?
                      """,
                      Boolean.class,
                      seed.id()))
              .isTrue();
          return null;
        });
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
              assertThat(activeSessions.values()).doesNotContain(session);
              assertThat(activePids.values()).doesNotContain(pid);
              if (!activePids.isEmpty())
                System.out.printf(
                    "SPIKE DISTINCT overlapping=%s newActor=%s newPid=%d%n",
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
      System.out.printf("SPIKE TX COMMITTED actor=%s%n", actor);
      return result;
    } catch (RuntimeException | Error failure) {
      System.out.printf(
          "SPIKE TX ROLLED_BACK actor=%s type=%s%n", actor, failure.getClass().getName());
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
        "SPIKE TX actor=%s thread=%s session=%s jpaPid=%s jdbcPid=%s jpaTx=%s jdbcTx=%s isolation=%s autoCommit=false schema=%s%n",
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

  private static String sqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
    }
    return "NO_SQLSTATE";
  }

  private static void requireS1() {
    assertThat(s1AtomicityPassed)
        .as("S1 fence/atomicity gate; S2 must not execute if it fails")
        .isTrue();
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
    future.cancel(true);
    workers.shutdownNow();
    try {
      assertThat(workers.awaitTermination(15, TimeUnit.SECONDS)).as("worker watchdog").isTrue();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("worker shutdown interrupted", failure);
    }
  }

  private enum Fault {
    NONE,
    DOMAIN,
    UNIQUE,
    LOCK
  }

  private record Seed(UUID id, Long user, Long reward, UUID key) {}

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {
    @Bean
    Clock spikeClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}

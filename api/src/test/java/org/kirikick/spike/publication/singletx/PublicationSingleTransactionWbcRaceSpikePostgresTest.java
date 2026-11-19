package org.kirikick.spike.publication.singletx;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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

/** Local candidate only: game_id fences observed generations, not lifetime ID non-reuse. */
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
  PublicationSingleTransactionWbcRaceSpikePostgresTest.ClockConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class PublicationSingleTransactionWbcRaceSpikePostgresTest {
  private static final String TABLE = "publication_single_tx_records";
  private static final String SCHEMA = "pub_wbc_" + UUID.randomUUID().toString().replace("-", "");
  private static final String SERVER_IDENTITY =
      "select concat_ws('|',current_database(),inet_server_addr()::text,inet_server_port()::text,pg_postmaster_start_time()::text,current_setting('server_version_num'))";
  private static final Instant T0 = Instant.parse("2026-11-18T06:00:00Z");
  private static final Duration RETENTION = Duration.ofHours(24).plusMinutes(30);
  private static final Duration GRACE = Duration.ofSeconds(47);
  private static String jdbcUrl;
  private static String serverIdentity;
  private static Properties credentials;
  private static boolean workersDrained = true;
  private static final List<String> CASE_REPORTS = new CopyOnWriteArrayList<>();
  private static final long H0_START_NANOS = System.nanoTime();
  private static double h0Seconds;
  private static volatile boolean runStopped;

  @Autowired DataSource dataSource;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired EntityManager entityManager;
  @Autowired Flyway flyway;
  @Autowired GameAssemblyService assembly;
  @Autowired UserRepository users;
  @Autowired RewardPresetRepository rewards;
  @Autowired MutableClock clock;
  private volatile CaseContext activeCase;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws SQLException {
    System.out.println("WBC_H0_START");
    var container = PostgresTestDatabase.container();
    jdbcUrl = container.getJdbcUrl();
    System.out.println("WBC_OWNED_CONTAINER id=" + container.getContainerId());
    log(
        "H0_IMAGE id=%s digests=%s",
        container.getContainerInfo().getImageId(),
        container
            .getDockerClient()
            .inspectImageCmd(container.getContainerInfo().getImageId())
            .exec()
            .getRepoDigests());
    credentials = new Properties();
    credentials.setProperty("user", container.getUsername());
    credentials.setProperty("password", container.getPassword());
    credentials.setProperty("connectTimeout", "3");
    credentials.setProperty("socketTimeout", "25");
    URI endpoint = URI.create(jdbcUrl.substring("jdbc:".length()));
    assertThat(container.isRunning()).isTrue();
    assertThat(endpoint.getHost()).isIn("localhost", "127.0.0.1", "[::1]");
    assertThat(endpoint.getPort()).isEqualTo(container.getMappedPort(5432));
    assertThat(endpoint.getPath()).isEqualTo("/" + container.getDatabaseName());
    try (Connection connection = DriverManager.getConnection(jdbcUrl, credentials)) {
      JdbcTemplate o = on(connection);
      assertThat(o.queryForObject("select current_database()", String.class))
          .isEqualTo(container.getDatabaseName());
      assertThat(o.queryForObject("select inet_server_port()", Integer.class)).isEqualTo(5432);
      assertThat(
              o.queryForObject("select current_setting('server_version_num')::int", Integer.class))
          .isBetween(170000, 179999);
      assertThat(
              o.queryForObject(
                  "select exists(select 1 from pg_namespace where nspname=?)",
                  Boolean.class,
                  SCHEMA))
          .isFalse();
      serverIdentity = o.queryForObject(SERVER_IDENTITY, String.class);
      log(
          "PRE_FLYWAY endpoint=%s server=%s container=%s image=%s schemaAbsent=%s version=%s",
          endpoint,
          serverIdentity,
          container.getContainerId(),
          container.getContainerInfo().getImageId(),
          SCHEMA,
          o.queryForObject("select version()", String.class));
    }
    registry.add("spring.datasource.hikari.data-source-properties.connectTimeout", () -> "3");
    registry.add("spring.datasource.hikari.data-source-properties.socketTimeout", () -> "25");
    registry.add("spring.flyway.url", () -> jdbcUrl);
    registry.add("spring.flyway.user", container::getUsername);
    registry.add("spring.flyway.password", container::getPassword);
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.schemas", () -> SCHEMA);
    registry.add("spring.flyway.default-schema", () -> SCHEMA);
    registry.add(
        "spring.flyway.locations",
        () ->
            "classpath:db/migration,classpath:db/spike/n1,classpath:db/spike/publication-single-transaction");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    registry.add(
        "spring.datasource.hikari.connection-init-sql", () -> "set search_path to " + SCHEMA);
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> 3);
    registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    registry.add("spring.datasource.hikari.connection-timeout", () -> 3000);
    registry.add("spring.jpa.show-sql", () -> false);
  }

  @BeforeEach
  void harness() throws SQLException {
    assertThat(runStopped).as("BLOCKED: prior case did not prove cleanup").isFalse();
    assertThat(workersDrained).as("BLOCKED: preceding worker drain").isTrue();
    assertGraphOracle();
    assertWaitOracle();
    assertThat(Runtime.version().feature()).isEqualTo(21);
    assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
    assertThat(((JpaTransactionManager) transactionManager).getDataSource()).isSameAs(dataSource);
    assertThat(AopUtils.isAopProxy(assembly)).isTrue();
    assertThat(pool().getMaximumPoolSize()).isEqualTo(3);
    assertThat(pool().getJdbcUrl()).isEqualTo(jdbcUrl);
    assertThat(entityManager.getEntityManagerFactory().getProperties())
        .containsEntry("hibernate.hbm2ddl.auto", "validate");
    try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
      assertThat(on(connection).queryForObject(SERVER_IDENTITY, String.class))
          .isEqualTo(serverIdentity);
    }
    assertThat(flyway.info().pending()).isEmpty();
    flyway.validate();
    assertThat(flyway.info().applied())
        .filteredOn(m -> m.getVersion() != null)
        .extracting(m -> m.getVersion().getVersion())
        .containsExactly("1", "2", "9000", "9003");
    for (var migration : flyway.info().applied()) {
      if (migration.getVersion() != null) {
        assertThat(migration.getChecksum()).isNotNull();
        log("H0 migration=%s checksum=%s", migration.getVersion(), migration.getChecksum());
      }
    }
    try (Connection connection = observer()) {
      JdbcTemplate o = on(connection);
      List<Map<String, Object>> constraints =
          o.queryForList(
              "select conname,contype,condeferrable,pg_get_constraintdef(oid) definition from pg_constraint where conrelid=?::regclass order by conname",
              TABLE);
      log("H0 constraints=%s", constraints);
      List<Map<String, Object>> schemaConstraints =
          o.queryForList(
              "select conname,contype,condeferrable,pg_get_constraintdef(oid) definition from pg_constraint where connamespace=current_schema()::regnamespace order by conname");
      log("H0_ALL_CONSTRAINTS %s", schemaConstraints);
      assertThat(
              o.queryForList(
                  "select conname from pg_constraint where connamespace=current_schema()::regnamespace and contype in ('c','f')",
                  String.class))
          .containsExactlyInAnyOrder(
              "ck_users_status",
              "ck_oauth_identities_provider",
              "ck_signup_applications_provider",
              "ck_signup_applications_completion",
              "ck_publication_single_tx_completion",
              "fk_oauth_identities_user",
              "fk_signup_applications_completed_user",
              "fk_rooms_host_user",
              "fk_rooms_selected_reward_preset",
              "fk_games_room",
              "fk_choices_game",
              "fk_games_correct_choice",
              "publication_single_tx_records_user_id_fkey",
              "publication_single_tx_records_game_id_fkey");
      assertThat(
              o.queryForObject(
                  "select count(*) from pg_constraint where connamespace=current_schema()::regnamespace and condeferrable",
                  Integer.class))
          .isZero();

      assertThat(
              o.queryForObject(
                  "select count(*) from pg_constraint where conrelid=?::regclass and contype='c'",
                  Integer.class,
                  TABLE))
          .isPositive();
      for (String table :
          List.of(
              "users",
              "oauth_identities",
              "signup_applications",
              "reward_presets",
              "rooms",
              "games",
              "choices")) {
        assertThat(
                o.queryForObject(
                    "select count(*) from pg_constraint where conrelid=?::regclass and contype='p'",
                    Integer.class,
                    table))
            .as("PK %s", table)
            .isEqualTo(1);
      }
      assertThat(
              o.queryForList(
                  "select conname from pg_constraint where conrelid=?::regclass and contype='u' and not condeferrable order by conname",
                  String.class,
                  TABLE))
          .containsExactly("uk_single_tx_game", "uk_single_tx_user_key");
      assertThat(
              o.queryForList(
                  "select pg_get_constraintdef(oid) from pg_constraint where conrelid=?::regclass and contype='f' order by conname",
                  String.class,
                  TABLE))
          .containsExactly(
              "FOREIGN KEY (game_id) REFERENCES games(id)",
              "FOREIGN KEY (user_id) REFERENCES users(id)");
      assertThat(o.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);
    }
    assertThat(jdbc.queryForObject(SERVER_IDENTITY, String.class)).isEqualTo(serverIdentity);
    assertH0LeasesAndObserver();
    CaseContext h0 = new CaseContext("H0");
    tx(
        h0,
        actor(h0, "H0"),
        () -> {
          assertThat(entityManager.unwrap(Session.class).<Tx>doReturningWork(this::identity))
              .isEqualTo(identity());
          return null;
        });
    advance(T0);
    log("H0 SUPPORTED schema=%s max=3 grace=47s test-clock-only", SCHEMA);
    if (h0Seconds == 0) h0Seconds = (System.nanoTime() - H0_START_NANOS) / 1e9;
    assertThat(h0Seconds).isLessThan(180);
    System.out.println("WBC_H0_READY");
  }

  @AfterEach
  void end() throws Exception {
    if (activeCase == null) return;
    CaseContext context = activeCase;
    try {
      context.drainAndProve();
      if (context.verdict == null) {
        runStopped = true;
        return;
      }
      assertThat(context.firstFailure.get()).as("no unexpected worker failure").isNull();
      if (context.name.equals("A-O")) {
        assertThat(context.injectedFailure).isNotNull();
      }
      double elapsed = (System.nanoTime() - context.started) / 1e9;
      assertThat(elapsed).isLessThan(80);
      String item =
          String.format(
              Locale.ROOT,
              "{\"case\":\"%s\",\"verdict\":\"%s\",\"cleanup\":\"SUPPORTED\",\"drain\":true,\"work_seconds\":%.6f,\"cleanup_seconds\":%.6f,\"total_seconds\":%.6f,\"limits\":{\"work_seconds\":55,\"cleanup_seconds\":25,\"total_seconds\":80,\"watchdog_seconds\":90}}",
              context.name,
              context.verdict,
              context.workSeconds,
              (System.nanoTime() - context.cleanupStarted) / 1e9,
              elapsed);
      CASE_REPORTS.add(item);
      System.out.printf(
          "WBC_CASE_RESULT case=%s verdict=%s cleanup=SUPPORTED drain=true%n",
          context.name, context.verdict);
      if (CASE_REPORTS.size() == 6) {
        System.out.println(
            "WBC_RUN_CLEAN cases=6 drain=true scope=case-workers-transactions-pool-only");
        System.out.printf(
            Locale.ROOT,
            "WBC_RUN_REPORT {\"h0\":{\"started\":true,\"ready\":true,\"scope\":\"preparation-only\",\"work_seconds\":%.6f},\"cases\":[%s],\"cases_drained\":true}%n",
            h0Seconds,
            String.join(",", CASE_REPORTS));
      }

    } catch (Throwable failure) {
      runStopped = true;
      throw failure;
    } finally {
      activeCase = null;
    }
  }

  @Test
  @Order(1)
  @DisplayName("A-C: W delete hold with B/C PostgreSQL wait creates exactly one replacement")
  void aCommit() throws Exception {
    wFirst(false, "A-C");
  }

  @Test
  @Order(2)
  @DisplayName("A-R: W rollback restores G0 before a dynamically selected request wins")
  void aRollback() throws Exception {
    wFirst(true, "A-R");
  }

  private void wFirst(boolean rollback, String name) throws Exception {
    CaseContext context = startCase(name);
    Seed seed = seed(context, "old");
    Receipt g0 = seedExpiresAt(context, seed, T0);
    Snapshot before = snapshot(name + "-before");
    Ticket originalTicket = candidates(seed).getFirst();
    Actor w = actor(context, name + "-W");
    Actor b = actor(context, name + "-B");
    Actor c = actor(context, name + "-C");
    b.pauseAfterWrite = true;
    c.pauseAfterWrite = true;
    context.executor = Executors.newFixedThreadPool(3);
    try {
      Future<List<Long>> wf =
          submit(context, () -> sweep(context, w, List.of(originalTicket), true, rollback));
      await(context, w.parked, "W delete hold");
      assertThat(snapshot(name + "-W-uncommitted")).isEqualTo(before);
      Future<Outcome> bf =
          submit(
              context,
              () ->
                  request(
                      context, b, seed, "new", rollback ? Pause.AFTER_LOCK : Pause.NONE, false));
      Future<Outcome> cf =
          submit(
              context,
              () ->
                  request(
                      context, c, seed, "new", rollback ? Pause.AFTER_LOCK : Pause.NONE, false));
      waitForRooted(context, w, b, c);
      assertThat(b.currentPhase).isEqualTo("lock-1");
      assertThat(c.currentPhase).isEqualTo("lock-1");
      if (rollback) {
        w.resume.countDown();
        assertThat(resultFailure(context, wf)).isInstanceOf(InjectedRollback.class);
        await(context, context.afterLockPublished, "dynamic post-lock restoration hold");
        Actor restoredWinner = context.afterLock.peek();
        assertThat(restoredWinner).isNotNull();
        assertThat(snapshot(name + "-restored")).isEqualTo(before);
        waitForRooted(context, restoredWinner, restoredWinner == b ? c : b);
        restoredWinner.resume.countDown();
      } else {
        w.resume.countDown();
        assertThat(result(context, wf)).containsExactly(g0.game());
      }
      await(context, context.afterWritePublished, "dynamic winner after-write");
      Actor winner = context.afterWrite.peek();
      assertThat(winner).isNotNull();
      Actor loser = winner == b ? c : b;
      waitForRooted(context, winner, loser);
      winner.writeResume.countDown();
      Outcome first = result(context, bf);
      Outcome second = result(context, cf);
      assertThat(List.of(first.kind(), second.kind()))
          .containsExactlyInAnyOrder("created", "recovered");
      assertCommitted(b, c);
      assertThat(w.completion)
          .isEqualTo(
              rollback
                  ? TransactionSynchronization.STATUS_ROLLED_BACK
                  : TransactionSynchronization.STATUS_COMMITTED);
      assertThat(loser.business).isNull();
      assertThat(loser.claimRows).doesNotContain(1);
      assertThat(loser.recovered).isEqualTo(winner.receipt);
    } finally {
      context.drainAndProve();
    }
    assertReplacement(seed, g0, b.receipt != null ? b : c, before);
    caseResult(context, name, "SUPPORTED", "SUPPORTED");
  }

  @Test
  @Order(3)
  @DisplayName("B-C-F: B first lock, W SKIP LOCKED/Y delete, then stale/fresh fence")
  void bCommitAndFence() throws Exception {
    bFirstWbc(false, "B-C-F");
  }

  @Test
  @Order(4)
  @DisplayName("B-R: rollback IDs vanish and C creates after restored G0")
  void bRollback() throws Exception {
    bFirstWbc(true, "B-R");
  }

  private void bFirstWbc(boolean rollback, String name) throws Exception {
    CaseContext context = startCase(name);
    Seed seed = seed(context, "old");
    Receipt g0 = seedExpiresAt(context, seed, T0);
    Seed control = seed(context, "control");
    Receipt y0 = seedExpiresAt(context, control, T0);
    Ticket old = candidates(seed).getFirst();
    Ticket controlTicket = candidates(control).getFirst();
    Snapshot before = snapshot(name + "-before");
    Actor b = actor(context, name + "-B");
    Actor c = actor(context, name + "-C");
    Actor w = actor(context, name + "-W");
    b.pauseAfterWrite = true;
    context.executor = Executors.newFixedThreadPool(3);
    try {
      Future<Outcome> bf =
          submit(context, () -> request(context, b, seed, "new", Pause.AFTER_LOCK, rollback));
      await(context, b.parked, "B old lock hold");
      Future<Outcome> cf =
          submit(
              context,
              () ->
                  request(
                      context, c, seed, "new", rollback ? Pause.AFTER_LOCK : Pause.NONE, false));
      waitFor(context, b, c, "lock-1");
      Future<List<Long>> wf =
          submit(context, () -> sweep(context, w, List.of(old, controlTicket), true, false));
      await(context, w.parked, "W Y delete hold");
      assertThat(w.lockRows).containsExactly(0, 1);
      assertThat(w.deleted).containsExactly(y0.game());
      waitForGraph(context, b, List.of(w), c);
      assertThat(snapshot(name + "-W-Y-uncommitted")).isEqualTo(before);
      b.resume.countDown();
      await(context, context.afterWritePublished, "B after-write");
      Actor winner = context.afterWrite.peek();
      assertThat(winner).isSameAs(b);
      waitForGraph(context, b, List.of(w), c);
      assertThat(b.business).isNotNull();
      assertThat(b.receipt.game()).isEqualTo(b.business.game());
      log("B_AFTER_WRITE ids=%s receipt=%s W=%s C=%s", b.business, b.receipt, w.tx, c.tx);
      winner.writeResume.countDown();
      if (rollback) {
        assertThat(resultFailure(context, bf)).isInstanceOf(InjectedRollback.class);
        assertGeneratedAbsent(b.business);
        await(context, c.parked, "C restored G0 before delete");
        assertThat(snapshot(name + "-B-restored")).isEqualTo(before);
        assertThat(b.completion).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
        c.resume.countDown();
      } else {
        assertThat(result(context, bf).kind()).isEqualTo("created");
      }
      w.resume.countDown();
      assertThat(result(context, wf)).containsExactly(y0.game());
      assertThat(result(context, cf).kind()).isEqualTo(rollback ? "created" : "recovered");
      assertCommitted(c, w);
      if (!rollback) {
        assertCommitted(b);
        assertThat(c.business).isNull();
        assertThat(c.claimRows).doesNotContain(1);
        assertThat(c.recovered).isEqualTo(b.receipt);
      }
      assertThat(receipts(control)).isEmpty();
    } finally {
      if (context.futures.stream().allMatch(Future::isDone) && !context.abort.get())
        context.quiesceAndProve();
      else context.drainAndProve();
    }
    Actor committed = rollback ? c : b;
    Receipt g1 = assertReplacement(seed, g0, committed, before);
    if (rollback) assertGeneratedAbsent(b.business);
    if (!rollback) {
      Snapshot core = snapshot(name + "-core");
      advance(g1.expiry());
      assertThat(sweep(context, actor(context, name + "-old"), List.of(old), false, false))
          .isEmpty();
      assertThat(exactDelete(context, actor(context, name + "-old-exact-delete"), old, g1.expiry()))
          .isEmpty();
      assertThat(snapshot(name + "-stale")).isEqualTo(core);
      Ticket fresh = candidates(seed).getFirst();
      assertThat(fresh).isEqualTo(ticket(seed, g1));
      log("F_TICKETS old=%s fresh=%s expiry=%s now=%s", old, fresh, g1.expiry(), clock.instant());
      assertThat(sweep(context, actor(context, name + "-fresh"), List.of(fresh), false, false))
          .containsExactly(g1.game());
      assertThat(receipts(seed)).isEmpty();
      Snapshot afterFence = snapshot(name + "-fresh-deleted");
      assertThat(afterFence.rooms()).isEqualTo(core.rooms());
      assertThat(afterFence.games()).isEqualTo(core.games());
      assertThat(afterFence.choices()).isEqualTo(core.choices());
      try (Connection o = observer()) {
        assertBusiness(on(o), seed, g1);
      }
      context.drainAndProve();
    }
    context.drainAndProve();
    caseResult(context, name, "SUPPORTED", "SUPPORTED");
  }

  @Test
  @Order(5)
  @DisplayName("A-T: B timeout returns its lease while W/C remain held")
  void timeout() throws Exception {
    CaseContext context = startCase("A-T");
    Seed seed = seed(context, "old");
    Receipt g0 = seedExpiresAt(context, seed, T0);
    Snapshot before = snapshot("A-T-before");
    Ticket originalTicket = candidates(seed).getFirst();
    Actor w = actor(context, "A-T-W");
    Actor b = actor(context, "A-T-B");
    Actor c = actor(context, "A-T-C");
    b.lockTimeoutSeconds = 4;
    c.lockTimeoutSeconds = 12;
    context.executor = Executors.newFixedThreadPool(3);
    try {
      Future<List<Long>> wf =
          submit(context, () -> sweep(context, w, List.of(originalTicket), true, false));
      await(context, w.parked, "W hold");
      Future<Outcome> bf =
          submit(context, () -> request(context, b, seed, "new", Pause.NONE, false));
      Future<Outcome> cf =
          submit(context, () -> request(context, c, seed, "new", Pause.NONE, false));
      waitForRooted(context, w, b, c);
      assertThat(b.currentPhase).isEqualTo("lock-1");
      assertThat(c.currentPhase).isEqualTo("lock-1");
      Throwable timeout = resultFailure(context, bf);
      assertThat(sqlState(timeout)).isEqualTo("55P03");
      assertThat(b.errorPhase).isEqualTo("lock-1");
      assertThat(b.claimRows).isEmpty();
      assertThat(b.business).isNull();
      assertThat(b.completion).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
      probeFreedSlot(context, w, c, b);
      w.resume.countDown();
      assertThat(result(context, wf)).containsExactly(g0.game());
      assertThat(result(context, cf).kind()).isEqualTo("created");
      assertCommitted(w, c);
      assertReplacement(seed, g0, c, before);
      Actor retry = actor(context, "A-T-retry");
      assertThat(request(context, retry, seed, "new", Pause.NONE, false).kind())
          .isEqualTo("recovered");
      assertCommitted(retry);
      assertThat(retry.business).isNull();
      assertThat(retry.recovered).isEqualTo(c.receipt);
    } finally {
      context.drainAndProve();
    }
    caseResult(context, "A-T", "SUPPORTED", "SUPPORTED");
  }

  @Test
  @Order(6)
  @DisplayName("A-O: observer failure is inconclusive but abort cleanup is proven")
  void observerFailure() throws Exception {
    CaseContext context = startCase("A-O");
    Seed seed = seed(context, "old");
    Receipt g0 = seedExpiresAt(context, seed, T0);
    Snapshot before = snapshot("A-O-before");
    Ticket originalTicket = candidates(seed).getFirst();
    Actor w = actor(context, "A-O-W");
    Actor b = actor(context, "A-O-B");
    Actor c = actor(context, "A-O-C");
    context.executor = Executors.newFixedThreadPool(3);
    try {
      Future<List<Long>> wf =
          submit(context, () -> sweep(context, w, List.of(originalTicket), true, true));
      await(context, w.parked, "W delete hold");
      submit(context, () -> request(context, b, seed, "new", Pause.NONE, false));
      submit(context, () -> request(context, c, seed, "new", Pause.NONE, false));
      awaitSqlEntry(context, b, c);
      context.injectedFailure =
          new SQLException("injected observer failure before mandatory graph");
      log("INJECTED_OBSERVER_FAILURE cause=%s", context.injectedFailure);
      context.abortAndRelease();
    } finally {
      context.abortAndRelease();
      context.drainAndProve();
    }
    assertThat(context.injectedFailure)
        .hasMessage("injected observer failure before mandatory graph");
    assertThat(snapshot("A-O-after")).isEqualTo(before);
    for (Actor a : List.of(w, b, c)) {
      assertThat(a.completion).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
      assertThat(a.business).isNull();
      assertThat(a.claimRows).isEmpty();
    }
    caseResult(context, "A-O", "INCONCLUSIVE", "SUPPORTED");
  }

  private static void caseResult(CaseContext context, String name, String verdict, String cleanup) {
    assertThat(context.cleanupSupported).isTrue();
    assertThat(context.workExhausted.get()).isFalse();
    assertThat(runStopped).isFalse();
    assertThat(context.name).isEqualTo(name);
    assertThat(cleanup).isEqualTo("SUPPORTED");
    context.verdict = verdict;
  }

  private void assertCommitted(Actor... actors) {
    for (Actor actor : actors)
      assertThat(actor.completion)
          .as("COMMITTED %s", actor.name)
          .isEqualTo(TransactionSynchronization.STATUS_COMMITTED);
  }

  private List<Long> exactDelete(CaseContext context, Actor actor, Ticket ticket, Instant now) {
    return tx(
        context,
        actor,
        () -> {
          List<Long> rows =
              jdbc.queryForList(
                  actor.tag
                      + "/* phase=exact-stale-delete */ delete from "
                      + TABLE
                      + " where user_id=? and request_key=? and game_id=? and expires_at<=? returning game_id",
                  Long.class,
                  ticket.user(),
                  ticket.key(),
                  ticket.game(),
                  odt(now));
          log("EXACT_DELETE actor=%s ticket=%s now=%s returning=%s", actor.name, ticket, now, rows);
          return rows;
        });
  }

  private Outcome request(
      CaseContext context, Actor actor, Seed seed, String input, Pause pause, boolean rollback) {
    return tx(
        context,
        actor,
        () -> {
          Receipt current = lock(context, actor, seed, "lock-1");
          context.checkAbort("after lock-1");
          if (pause == Pause.AFTER_LOCK && current != null) {
            context.afterLock.add(actor);
            context.afterLockPublished.countDown();
            actor.pause();
          }
          context.checkAbort("after lock pause");
          if (current == null) current = lock(context, actor, seed, "lock-2");
          if (pause == Pause.BEFORE_CLAIM) actor.pause();
          for (int attempt = 0; attempt < 3; attempt++) {
            if (current != null) {
              assertThat(current.game()).isPositive();
              assertThat(current.expiry()).isNotNull();
              Instant now = clock.instant();
              log("JUDGE actor=%s current=%s now=%s", actor.name, current, now);
              if (now.isBefore(current.expiry())) {
                actor.recovered = current;
                return new Outcome(
                    current.text().equals(input) ? "recovered" : "mismatch", current.game());
              }
              context.checkAbort("before delete");
              phase(actor, "delete");
              List<Long> deleted =
                  jdbc.queryForList(
                      actor.tag
                          + "/* phase=delete */ delete from "
                          + TABLE
                          + " where user_id=? and request_key=? and game_id=? and expires_at<=? returning game_id",
                      Long.class,
                      seed.user(),
                      seed.key(),
                      current.game(),
                      odt(now));
              assertThat(deleted).containsExactly(current.game());
              actor.deleted.addAll(deleted);
              log("DELETE actor=%s ids=%s tx=%s", actor.name, deleted, actor.tx);
            }
            context.checkAbort("before claim");
            phase(actor, "claim");
            List<Long> claimed =
                jdbc.queryForList(
                    actor.tag
                        + "/* phase=claim */ insert into "
                        + TABLE
                        + " (user_id,request_key,input_text) values (?,?,?) on conflict on constraint uk_single_tx_user_key do nothing returning user_id",
                    Long.class,
                    seed.user(),
                    seed.key(),
                    input);
            actor.claimRows.add(claimed.size());
            log("CLAIM actor=%s rows=%s tx=%s", actor.name, claimed, actor.tx);
            if (claimed.isEmpty()) {
              current = lock(context, actor, seed, "conflict-recheck");
              continue;
            }
            assertThat(claimed).containsExactly(seed.user());
            phase(actor, "assembly");
            Session session = entityManager.unwrap(Session.class);
            assertThat(entityManager.isJoinedToTransaction()).isTrue();
            assertThat(session.<Tx>doReturningWork(this::identity)).isEqualTo(actor.tx);
            context.checkAbort("before assembly");
            long game =
                assembly.createRoomWithFirstGame(
                    seed.user(), seed.reward(), 1, input, List.of("one", "two"), 0);
            entityManager.flush();
            assertThat(session.<Tx>doReturningWork(this::identity)).isEqualTo(actor.tx);
            actor.business = business(jdbc, game);
            Instant expiry = expiry(actor.business.opened());
            assertThat(expiry).isAfter(clock.instant());
            actor.receipt = new Receipt(input, game, expiry);
            assertBusiness(jdbc, seed, actor.receipt);
            context.checkAbort("before success");
            phase(actor, "success");
            assertThat(
                    jdbc.update(
                        actor.tag
                            + "/* phase=success */ update "
                            + TABLE
                            + " set game_id=?,expires_at=? where user_id=? and request_key=? and game_id is null and expires_at is null",
                        game,
                        odt(expiry),
                        seed.user(),
                        seed.key()))
                .isEqualTo(1);
            phase(actor, "flushed");
            if (actor.pauseAfterWrite) {
              context.afterWrite.add(actor);
              context.afterWritePublished.countDown();
            }
            log(
                "FLUSH actor=%s business=%s receipt=%s sharedJPA/JDBC=%s",
                actor.name, actor.business, actor.receipt, actor.tx);
            if (pause == Pause.AFTER_WRITE || actor.pauseAfterWrite) {
              actor.written.countDown();
              await(context, actor.writeResume, "after-write release");
            }
            context.checkAbort("after after-write hold");
            if (rollback) throw new InjectedRollback();
            return new Outcome("created", game);
          }
          throw new AssertionError("INCONCLUSIVE: bounded claim retry exhausted");
        });
  }

  private List<Long> sweep(
      CaseContext context, Actor actor, List<Ticket> tickets, boolean pause, boolean rollback) {
    return tx(
        context,
        actor,
        () -> {
          for (Ticket ticket : tickets) {
            context.checkAbort("before sweep ticket");
            phase(actor, "skip-lock");
            List<Receipt> rows =
                jdbc.query(
                    actor.tag
                        + "/* phase=skip-lock */ select input_text,game_id,expires_at from "
                        + TABLE
                        + " where user_id=? and request_key=? and game_id=? for update skip locked",
                    (rs, n) ->
                        receipt(
                            rs.getString(1), rs.getLong(2), rs.getObject(3, OffsetDateTime.class)),
                    ticket.user(),
                    ticket.key(),
                    ticket.game());
            actor.lockRows.add(rows.size());
            Instant now = clock.instant();
            log(
                "W_LOCK actor=%s ticket=%s rows=%s postlockNow=%s tx=%s",
                actor.name, ticket, rows, now, actor.tx);
            if (rows.isEmpty() || now.isBefore(rows.getFirst().expiry())) continue;
            context.checkAbort("before delete");
            phase(actor, "delete");
            List<Long> deleted =
                jdbc.queryForList(
                    actor.tag
                        + "/* phase=delete */ delete from "
                        + TABLE
                        + " where user_id=? and request_key=? and game_id=? and expires_at<=? returning game_id",
                    Long.class,
                    ticket.user(),
                    ticket.key(),
                    ticket.game(),
                    odt(now));
            assertThat(deleted).containsExactly(ticket.game());
            actor.deleted.addAll(deleted);
            assertThat(
                    jdbc.queryForObject(
                        "select count(*) from " + TABLE + " where user_id=? and request_key=?",
                        Integer.class,
                        ticket.user(),
                        ticket.key()))
                .isZero();
            log("W_DELETE actor=%s ids=%s tx=%s", actor.name, deleted, actor.tx);
          }
          if (pause) actor.pause();
          context.checkAbort("after sweep pause");
          if (rollback) throw new InjectedRollback();
          return List.copyOf(actor.deleted);
        });
  }

  private Receipt lock(CaseContext context, Actor actor, Seed seed, String stage) {
    phase(actor, stage);
    List<Receipt> rows =
        jdbc.query(
            actor.tag
                + "/* phase="
                + stage
                + " */ select input_text,game_id,expires_at from "
                + TABLE
                + " where user_id=? and request_key=? for update",
            (rs, n) ->
                receipt(rs.getString(1), rs.getLong(2), rs.getObject(3, OffsetDateTime.class)),
            seed.user(),
            seed.key());
    actor.lockRows.add(rows.size());
    rows.forEach(row -> actor.lockGames.add(row.game()));
    log("LOCK_RETURN actor=%s phase=%s rows=%s tx=%s", actor.name, stage, rows, actor.tx);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private List<Ticket> candidates(Seed seed) throws SQLException {
    try (Connection connection = observer()) {
      Instant now = clock.instant();
      List<Ticket> tickets =
          on(connection)
              .query(
                  "select user_id,request_key,game_id from "
                      + TABLE
                      + " where user_id=? and request_key=? and expires_at<=?",
                  (rs, n) -> new Ticket(rs.getLong(1), rs.getObject(2, UUID.class), rs.getLong(3)),
                  seed.user(),
                  seed.key(),
                  odt(now));
      log("SCAN now=%s tickets=%s", now, tickets);
      return tickets;
    }
  }

  private Seed seed(CaseContext context, String text) {
    return tx(
        context,
        actor(context, "seed-user"),
        () ->
            new Seed(
                users.save(User.create("wbc", null)).getId(),
                rewards.save(RewardPreset.create("expiry", "test-only")).getId(),
                UUID.randomUUID(),
                text));
  }

  private Receipt seedExpiresAt(CaseContext context, Seed seed, Instant expires) {
    advance(expires.minus(RETENTION).minus(GRACE));
    Actor actor = actor(context, "seed-success");
    Outcome created = request(context, actor, seed, seed.text(), Pause.NONE, false);
    assertThat(created.kind()).isEqualTo("created");
    assertThat(actor.receipt.expiry()).isEqualTo(expires);
    advance(expires);
    return actor.receipt;
  }

  private Receipt assertReplacement(Seed seed, Receipt old, Actor b, Snapshot before)
      throws SQLException {
    assertThat(b.completion).isEqualTo(TransactionSynchronization.STATUS_COMMITTED);
    Receipt current = receipts(seed).getFirst();
    assertThat(b.business.game()).isEqualTo(current.game());
    assertThat(b.business.opened()).isEqualTo(T0);
    assertThat(receipts(seed)).containsExactly(b.receipt);
    assertThat(current.game()).isNotEqualTo(old.game());
    assertThat(current.expiry()).isAfter(T0);
    try (Connection connection = observer()) {
      assertBusiness(on(connection), seed, current);
      assertThat(business(on(connection), current.game())).isEqualTo(b.business);
    }
    assertBusinessDelta(before, snapshot("replacement-" + b.name), 1);
    return current;
  }

  private void assertBusiness(JdbcTemplate db, Seed seed, Receipt receipt) {
    Business b = business(db, receipt.game());
    assertThat(receipt.expiry()).isEqualTo(expiry(b.opened()));
    assertThat(db.queryForList("select host_user_id from rooms where id=?", Long.class, b.room()))
        .containsExactly(seed.user());
    assertThat(
            db.queryForList(
                "select selected_reward_preset_id from rooms where id=?", Long.class, b.room()))
        .containsExactly(seed.reward());
    assertThat(
            db.queryForObject("select count(*) from users where id=?", Integer.class, seed.user()))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select count(*) from reward_presets where id=?", Integer.class, seed.reward()))
        .isEqualTo(1);
    assertThat(db.queryForList("select id from games where room_id=?", Long.class, b.room()))
        .containsExactly(b.game());
    assertThat(db.queryForObject("select ordinal from games where id=?", Integer.class, b.game()))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select question_text_snapshot from games where id=?", String.class, b.game()))
        .isEqualTo(receipt.text());
    assertThat(
            db.queryForList(
                "select position from choices where game_id=? order by position",
                Integer.class,
                b.game()))
        .containsExactly(0, 1);
    assertThat(
            db.queryForList(
                "select text_snapshot from choices where game_id=? order by position",
                String.class,
                b.game()))
        .containsExactly("one", "two");
    assertThat(
            db.queryForList(
                "select game_id from choices where id in (?,?) order by position",
                Long.class,
                b.choices().get(0),
                b.choices().get(1)))
        .containsExactly(b.game(), b.game());
    log(
        "EXACT_BUSINESS user=%s reward=%s receipt=%s business=%s",
        seed.user(), seed.reward(), receipt, b);
  }

  private List<Receipt> receipts(Seed seed) throws SQLException {
    try (Connection connection = observer()) {
      return on(connection)
          .query(
              "select input_text,game_id,expires_at from "
                  + TABLE
                  + " where user_id=? and request_key=?",
              (rs, n) ->
                  receipt(rs.getString(1), rs.getLong(2), rs.getObject(3, OffsetDateTime.class)),
              seed.user(),
              seed.key());
    }
  }

  private Snapshot snapshot(String label) throws SQLException {
    try (Connection connection = observer()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setReadOnly(true);
      connection.setAutoCommit(false);
      JdbcTemplate o = on(connection);
      Snapshot snapshot =
          new Snapshot(
              o.queryForList("select * from rooms order by id"),
              o.queryForList("select * from games order by id"),
              o.queryForList("select * from choices order by id"),
              o.queryForList("select * from " + TABLE + " order by user_id,request_key"));
      assertThat(
              o.queryForObject(
                  "select count(*) from games g left join choices c on c.id=g.correct_choice_id where c.id is null or c.game_id<>g.id",
                  Integer.class))
          .isZero();
      assertThat(
              o.queryForObject(
                  "select count(*) from games g where (select count(*) from choices c where c.game_id=g.id)<>2",
                  Integer.class))
          .isZero();
      assertThat(
              o.queryForObject(
                  "select count(*) from rooms r where (select count(*) from games g where g.room_id=r.id)<>1",
                  Integer.class))
          .isZero();
      connection.commit();
      log("SNAPSHOT label=%s data=%s", label, snapshot);
      return snapshot;
    }
  }

  private void assertBusinessDelta(Snapshot before, Snapshot after, int created) {
    assertThat(after.rooms()).containsAll(before.rooms()).hasSize(before.rooms().size() + created);
    assertThat(after.games()).containsAll(before.games()).hasSize(before.games().size() + created);
    assertThat(after.choices())
        .containsAll(before.choices())
        .hasSize(before.choices().size() + created * 2);
  }

  private Business business(JdbcTemplate db, long game) {
    Business b =
        db.queryForObject(
            "select r.id,r.opened_at,g.correct_choice_id from rooms r join games g on g.room_id=r.id where g.id=?",
            (rs, n) ->
                new Business(
                    rs.getLong(1),
                    game,
                    rs.getObject(2, OffsetDateTime.class).toInstant(),
                    rs.getLong(3),
                    db.queryForList(
                        "select id from choices where game_id=? order by position",
                        Long.class,
                        game)),
            game);
    assertThat(b.choices()).hasSize(2);
    assertThat(b.correct()).isEqualTo(b.choices().getFirst());
    return b;
  }

  private void assertGeneratedAbsent(Business business) throws SQLException {
    assertThat(business).isNotNull();
    try (Connection connection = observer()) {
      JdbcTemplate o = on(connection);
      assertThat(
              o.queryForObject(
                  "select count(*) from rooms where id=?", Integer.class, business.room()))
          .isZero();
      assertThat(
              o.queryForObject(
                  "select count(*) from games where id=?", Integer.class, business.game()))
          .isZero();
      for (long choice : business.choices())
        assertThat(
                o.queryForObject("select count(*) from choices where id=?", Integer.class, choice))
            .isZero();
    }
    log("ROLLBACK_IDS_ABSENT business=%s", business);
  }

  private void waitForRooted(CaseContext context, Actor root, Actor... waiters)
      throws SQLException {
    waitForGraph(context, root, List.of(), waiters);
  }

  private void waitForGraph(CaseContext context, Actor root, List<Actor> live, Actor... waiters)
      throws SQLException {
    long stop =
        System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(context.waitMillis(2_000, "mandatory graph"));
    int consecutive = 0;
    while (System.nanoTime() < stop) {
      context.checkAbort("mandatory graph");
      GraphSample sample = graphSample(context, root, live, stop, waiters);
      if (sample.relevant(root, live, waiters)) consecutive++;
      else consecutive = 0;
      if (consecutive == 2) return;
      sleep(context, 10, "mandatory graph poll");
    }
    throw new AssertionError("INCONCLUSIVE: two consecutive relevant PostgreSQL graphs missing");
  }

  private GraphSample graphSample(
      CaseContext context, Actor root, List<Actor> live, long stop, Actor... waiters)
      throws SQLException {
    List<Actor> all = new ArrayList<>(List.of(root));
    all.addAll(live);
    all.addAll(Arrays.asList(waiters));
    if (all.stream().anyMatch(a -> a.tx == null)) return GraphSample.missing();
    long before = System.nanoTime();
    try (Connection connection = observer(stop)) {
      JdbcTemplate o = boundedObserver(connection, stop);
      Array ids =
          connection.createArrayOf(
              "int4", all.stream().map(a -> a.tx.pid()).toArray(Integer[]::new));
      try {
        List<Map<String, Object>> activity =
            o.queryForList(
                "select pid,state,query,query_start,xact_start,backend_xid::text,wait_event_type,wait_event,pg_blocking_pids(pid)::text blockers from pg_stat_activity where pid=any(?) order by pid",
                ids);
        List<Map<String, Object>> locks =
            boundedObserver(connection, stop)
                .queryForList(
                    "select pid,locktype,mode,relation::regclass::text relation,tuple,transactionid::text,granted from pg_locks where pid=any(?) order by pid,locktype,granted",
                    ids);
        Map<Integer, List<Integer>> blockers = new HashMap<>();
        for (Map<String, Object> row : activity) {
          int pid = ((Number) row.get("pid")).intValue();
          Array values =
              boundedObserver(connection, stop)
                  .queryForObject("select pg_blocking_pids(?)", Array.class, pid);
          try {
            blockers.put(pid, Arrays.asList((Integer[]) values.getArray()));
          } finally {
            values.free();
          }
        }
        GraphSample sample = new GraphSample(activity, locks, blockers, before, System.nanoTime());
        log(
            "O_GRAPH case=%s root=%s live=%s waiters=%s sample=%s",
            context.name,
            root.tx,
            live.stream().map(a -> a.tx).toList(),
            Arrays.stream(waiters).map(a -> a.tx).toList(),
            sample);
        return sample;
      } finally {
        ids.free();
      }
    }
  }

  private void waitFor(CaseContext context, Actor owner, Actor waiter, String stage)
      throws SQLException {
    waitForRooted(context, owner, waiter);
    assertThat(waiter.currentPhase).isEqualTo(stage);
  }

  private Actor actor(CaseContext context, String name) {
    Actor actor = new Actor(context, name);
    context.actors.add(actor);
    return actor;
  }

  private void phase(Actor actor, String phase) {
    actor.context.checkAbort("before " + phase);
    actor.currentPhase = phase;
    long left = actor.context.waitMillis(20_000, "SQL " + phase);
    jdbc.execute("set local statement_timeout='" + left + "ms'");
    jdbc.execute(
        "set local lock_timeout='" + Math.min(left, actor.lockTimeoutSeconds * 1000L) + "ms'");
    jdbc.execute(
        (ConnectionCallback<Void>)
            connection -> {
              connection.setNetworkTimeout(
                  Runnable::run,
                  (int) Math.min(25_000, actor.context.waitMillis(25_000, "socket " + phase)));
              return null;
            });
    log("PHASE actor=%s phase=%s mono=%d", actor.name, phase, System.nanoTime());
  }

  private <T> T tx(CaseContext context, Actor actor, Supplier<T> work) {
    context.checkAbort("transaction start");
    assertThat(context.waitMillis(3_000, "pool checkout budget")).isEqualTo(3_000);
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setTimeout(
        (int) Math.max(1, Math.min(40, context.waitMillis(40_000, "transaction timeout") / 1000)));
    try {
      T value =
          template.execute(status -> {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                  @Override
                  public void afterCompletion(int completion) {
                    actor.ended = System.nanoTime();
                    actor.completion = completion;
                    log(
                        "AFTER_COMPLETION actor=%s tx=%s status=%d mono=%d",
                        actor.name, actor.tx, completion, actor.ended);
                  }
                });
            context.checkAbort("before transaction SQL");
            jdbc.execute("set local statement_timeout='20s'");
            jdbc.execute("set local lock_timeout='" + actor.lockTimeoutSeconds + "s'");
            actor.tx = identity();
            actor.started = System.nanoTime();
            log(
                "TX actor=%s tag=%s tx=%s autocommit=false isolation=read-committed schema=%s",
                actor.name, actor.tag, actor.tx, SCHEMA);
            T result = work.get();
            context.checkAbort("transaction return");
            return result;
          });
      log(
          "TEMPLATE_RETURN actor=%s completion=%d tx=%s mono=%d",
          actor.name, actor.completion, actor.tx, System.nanoTime());
      return value;
    } catch (RuntimeException | Error error) {
      actor.errorPhase = actor.currentPhase;
      if (!context.expectedFailure(error)) context.fail(error);
      for (Throwable cause = error; cause != null; cause = cause.getCause()) {
        if (cause instanceof SQLException sql)
          log(
              "SQL_ERROR actor=%s state=%s sql=%s",
              actor.name, sql.getSQLState(), sql.getMessage());
      }
      log(
          "TEMPLATE_THROW actor=%s type=%s completion=%d tx=%s",
          actor.name, error.getClass().getSimpleName(), actor.completion, actor.tx);
      throw error;
    }
  }

  private Tx identity() {
    return jdbc.execute((ConnectionCallback<Tx>) this::identity);
  }

  private Tx identity(Connection connection) throws SQLException {
    assertThat(connection.getAutoCommit()).isFalse();
    try (var statement = connection.createStatement();
        var row =
            statement.executeQuery(
                "select pg_backend_pid(),pg_current_xact_id()::text,current_setting('transaction_isolation'),current_schema()")) {
      assertThat(row.next()).isTrue();
      assertThat(row.getString(3)).isEqualTo("read committed");
      assertThat(row.getString(4)).isEqualTo(SCHEMA);
      return new Tx(row.getInt(1), row.getString(2));
    }
  }

  private Connection observer() throws SQLException {
    return observer(Long.MAX_VALUE);
  }

  private Connection observer(long localDeadline) throws SQLException {
    CaseContext c = activeCase;
    long millis =
        c == null
            ? 3000
            : (c.cleanupDeadline != 0
                ? c.cleanupMillis(c.cleanupDeadline)
                : c.waitMillis(3000, "observer connect"));
    millis = Math.min(millis, localRemaining(localDeadline));
    Properties properties = new Properties();
    properties.putAll(credentials);
    if (millis < 1000)
      throw new AssertionError("INCONCLUSIVE: insufficient whole-second JDBC connect budget");
    properties.setProperty("connectTimeout", Long.toString(Math.min(3, millis / 1000)));
    properties.setProperty(
        "socketTimeout", Long.toString(Math.max(1, Math.min(25, (millis + 999) / 1000))));
    Connection connection = DriverManager.getConnection(jdbcUrl, properties);
    try {
      long remaining =
          c == null
              ? 3000
              : (c.cleanupDeadline != 0
                  ? c.cleanupMillis(c.cleanupDeadline)
                  : c.waitMillis(3000, "observer SQL"));
      remaining = Math.min(remaining, localRemaining(localDeadline));
      connection.setNetworkTimeout(Runnable::run, (int) Math.min(3000, remaining));
      JdbcTemplate o = on(connection);
      o.execute("set statement_timeout='" + Math.min(3000, remaining) + "ms'");
      o.execute("set search_path to " + SCHEMA);
      assertThat(o.queryForObject(SERVER_IDENTITY, String.class)).isEqualTo(serverIdentity);
      assertThat(connection.getAutoCommit()).isTrue();
      assertThat(connection.getTransactionIsolation())
          .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
      log(
          "OBSERVER pid=%s xid=%s RC=true autocommit=true schema=%s",
          o.queryForObject("select pg_backend_pid()", Integer.class),
          o.queryForObject(
              "select backend_xid::text from pg_stat_activity where pid=pg_backend_pid()",
              String.class),
          SCHEMA);
      return connection;
    } catch (RuntimeException | SQLException | Error error) {
      connection.close();
      throw error;
    }
  }

  private long localRemaining(long deadline) {
    long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    if (left <= 0) throw new AssertionError("INCONCLUSIVE: local observer deadline exhausted");
    return left;
  }

  private JdbcTemplate boundedObserver(Connection connection, long deadline) throws SQLException {
    long left = Math.min(3_000, localRemaining(deadline));
    if (activeCase != null)
      left =
          Math.min(
              left,
              activeCase.cleanupDeadline != 0
                  ? activeCase.cleanupMillis(activeCase.cleanupDeadline)
                  : activeCase.waitMillis(left, "observer statement"));
    connection.setNetworkTimeout(Runnable::run, (int) left);
    JdbcTemplate db = on(connection);
    db.execute("set statement_timeout='" + left + "ms'");
    return db;
  }

  private static JdbcTemplate on(Connection connection) {
    return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
  }

  private void assertH0LeasesAndObserver() throws SQLException {
    try (Connection one = dataSource.getConnection();
        Connection two = dataSource.getConnection();
        Connection three = dataSource.getConnection();
        Connection o = observer()) {
      one.setAutoCommit(false);
      two.setAutoCommit(false);
      three.setAutoCommit(false);
      Tx first = identity(one);
      Tx second = identity(two);
      Tx third = identity(three);
      assertThat(pool().getHikariPoolMXBean().getActiveConnections()).isEqualTo(3);
      assertThat(pool().getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
      assertThat(pool().getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
      assertThat(List.of(first.pid(), second.pid(), third.pid())).doesNotHaveDuplicates();
      assertThat(List.of(first.xid(), second.xid(), third.xid())).doesNotHaveDuplicates();
      assertThat(one.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
      assertThat(o.getAutoCommit()).isTrue();
      assertThat(o.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
      on(one).queryForObject("/* WBC-H0-visible */ select 1", Integer.class);
      assertThat(
              on(o)
                  .queryForObject(
                      "select query like '%WBC-H0-visible%' from pg_stat_activity where pid=?",
                      Boolean.class, first.pid()))
          .isTrue();
      on(o).queryForObject("select current_schema()", String.class);
      log(
          "H0_LEASES pids=%s xids=%s observer=%s",
          List.of(first.pid(), second.pid(), third.pid()),
          List.of(first.xid(), second.xid(), third.xid()),
          on(o).queryForObject("select pg_backend_pid()", Integer.class));
    }
    assertIdle();
  }

  private HikariDataSource pool() {
    return (HikariDataSource) dataSource;
  }

  private void assertIdle() {
    assertThat(pool().getHikariPoolMXBean().getActiveConnections()).isZero();
    assertThat(pool().getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
  }

  private void advance(Instant now) {
    assertIdle();
    clock.set(now);
    log("CLOCK injected=%s no-active-actors", now);
  }

  private static Receipt receipt(String text, long game, OffsetDateTime expiry) {
    return new Receipt(text, game, expiry == null ? null : expiry.toInstant());
  }

  private static Ticket ticket(Seed seed, Receipt receipt) {
    return new Ticket(seed.user(), seed.key(), receipt.game());
  }

  private static Instant expiry(Instant opened) {
    return opened.plus(RETENTION).plus(GRACE);
  }

  private static OffsetDateTime odt(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static void log(String format, Object... args) {
    System.out.println("WBC " + String.format(format, args));
  }

  private record Seed(long user, long reward, UUID key, String text) {}

  private record Receipt(String text, long game, Instant expiry) {}

  private record Ticket(long user, UUID key, long game) {}

  private record Business(long room, long game, Instant opened, long correct, List<Long> choices) {}

  private record Outcome(String kind, long game) {}

  private record Tx(int pid, String xid) {}

  private record GraphSample(
      List<Map<String, Object>> activity,
      List<Map<String, Object>> locks,
      Map<Integer, List<Integer>> blockers,
      long before,
      long after) {
    static GraphSample missing() {
      return new GraphSample(List.of(), List.of(), Map.of(), 0, 0);
    }

    boolean relevant(Actor root, List<Actor> live, Actor... waiters) {
      List<Actor> all = new ArrayList<>(List.of(root));
      all.addAll(live);
      all.addAll(Arrays.asList(waiters));
      if (before == 0
          || all.stream().anyMatch(a -> a.tx == null || a.started >= before || a.ended != 0))
        return false;
      if (all.stream()
          .anyMatch(a ->
              activity.stream()
                  .noneMatch(row ->
                      ((Number) row.get("pid")).intValue() == a.tx.pid()
                          && a.tx.xid().equals(row.get("backend_xid"))
                          && row.get("xact_start") != null))) return false;
      Set<String> xids = new HashSet<>();
      all.forEach(a -> xids.add(a.tx.xid()));
      for (Actor waiter : waiters) {
        Map<String, Object> row =
            activity.stream()
                .filter(r -> ((Number) r.get("pid")).intValue() == waiter.tx.pid())
                .findFirst()
                .orElse(Map.of());
        if (!relevantWait(row, locks, waiter.tx.pid(), waiter.tag, xids)) return false;
      }
      return acceptsRootedGraph(
          blockers, root.tx.pid(), Arrays.stream(waiters).mapToInt(a -> a.tx.pid()).toArray());
    }
  }

  private static boolean relevantWait(
      Map<String, Object> row,
      List<Map<String, Object>> locks,
      int pid,
      String tag,
      Set<String> xids) {
    String query = String.valueOf(row.get("query"));
    boolean phase =
        List.of("lock-1", "lock-2", "conflict-recheck", "claim").stream()
            .anyMatch(p -> query.startsWith(tag + "/* phase=" + p + " */"));
    return "Lock".equals(row.get("wait_event_type"))
        && phase
        && query.contains(TABLE)
        && locks.stream()
            .anyMatch(l ->
                ((Number) l.get("pid")).intValue() == pid
                    && Boolean.FALSE.equals(l.get("granted"))
                    && (("transactionid".equals(l.get("locktype"))
                            && xids.contains(l.get("transactionid")))
                        || ("tuple".equals(l.get("locktype"))
                            && String.valueOf(l.get("relation")).endsWith(TABLE))));
  }

  private record Snapshot(
      List<Map<String, Object>> rooms,
      List<Map<String, Object>> games,
      List<Map<String, Object>> choices,
      List<Map<String, Object>> receipts) {}

  private enum Pause {
    NONE,
    AFTER_LOCK,
    BEFORE_CLAIM,
    AFTER_WRITE
  }

  private CaseContext startCase(String name) {
    if (runStopped) throw new AssertionError("BLOCKED: prior case exhausted work or cleanup");
    CaseContext context = new CaseContext(name);
    activeCase = context;
    return context;
  }

  private <T> Future<T> submit(CaseContext context, Callable<T> task) {
    context.checkAbort("submit");
    Future<T> future =
        context.executor.submit(() -> {
          try {
            context.checkAbort("worker start");
            return task.call();
          } catch (Throwable error) {
            if (!context.expectedFailure(error)) context.fail(error);
            throw error;
          }
        });
    context.futures.add(future);
    return future;
  }

  private void await(CaseContext context, CountDownLatch latch, String stage) {
    try {
      assertThat(latch.await(context.waitMillis(15_000, stage), TimeUnit.MILLISECONDS))
          .as("INCONCLUSIVE: %s", stage)
          .isTrue();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }

  private <T> T result(CaseContext context, Future<T> future) throws Exception {
    return future.get(context.waitMillis(12_000, "future"), TimeUnit.MILLISECONDS);
  }

  private Throwable resultFailure(CaseContext context, Future<?> future) throws Exception {
    try {
      future.get(context.waitMillis(12_000, "failed future"), TimeUnit.MILLISECONDS);
      throw new AssertionError("expected worker failure");
    } catch (ExecutionException error) {
      return error.getCause();
    }
  }

  private void awaitSqlEntry(CaseContext context, Actor... actors) throws SQLException {
    long stop =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(context.waitMillis(4_000, "sql entry"));
    try (Connection connection = observer()) {
      JdbcTemplate o = on(connection);
      while (System.nanoTime() < stop) {
        if (Arrays.stream(actors).allMatch(actor -> actor.tx != null)
            && o.queryForObject(
                    "select count(*) from pg_stat_activity where pid = any(?) and query like '%phase=lock-1%'",
                    Integer.class,
                    connection.createArrayOf(
                        "int4", Arrays.stream(actors).map(a -> a.tx.pid()).toArray(Integer[]::new)))
                == actors.length) return;
        sleep(context, 10, "sql entry poll");
      }
    }
    throw new AssertionError("INCONCLUSIVE: B/C target SQL did not enter PostgreSQL");
  }

  private void probeFreedSlot(CaseContext context, Actor w, Actor c, Actor b) throws SQLException {
    assertThat(b.completion).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
    assertThat(pool().getHikariPoolMXBean().getActiveConnections()).isEqualTo(2);
    assertThat(pool().getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
    waitForRooted(context, w, c);
    try (Connection probe = dataSource.getConnection()) {
      assertThat(pool().getHikariPoolMXBean().getActiveConnections()).isEqualTo(3);
      assertThat(pool().getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
      JdbcTemplate p = on(probe);
      int pid = p.queryForObject("select pg_backend_pid()", Integer.class);
      assertThat(pid).isNotEqualTo(w.tx.pid()).isNotEqualTo(c.tx.pid());
      assertThat(p.queryForObject("show lock_timeout", String.class)).isEqualTo("0");
      probe.setAutoCommit(false);
      Tx fresh = identity(probe);
      assertThat(fresh.xid()).isNotEqualTo(b.tx.xid());
      p.queryForObject("select 1", Integer.class);
      try (Connection o = observer()) {
        assertThat(
                on(o)
                    .queryForObject(
                        "select count(*) from pg_stat_activity where (pid=? and backend_xid::text=?) or (pid=? and backend_xid::text=?)",
                        Integer.class,
                        w.tx.pid(),
                        w.tx.xid(),
                        c.tx.pid(),
                        c.tx.xid()))
            .isEqualTo(2);
        assertThat(
                on(o)
                    .queryForObject(
                        "select not exists(select 1 from pg_stat_activity where pid=? and backend_xid::text=?)",
                        Boolean.class,
                        b.tx.pid(),
                        b.tx.xid()))
            .isTrue();
      }
      probe.rollback();
      log("LEASE_PROBE W=%s C=%s oldB=%s new=%s active=3 pending=0", w.tx, c.tx, b.tx, fresh);
    }
    assertThat(pool().getHikariPoolMXBean().getActiveConnections()).isEqualTo(2);
  }

  private static String sqlState(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause())
      if (cause instanceof SQLException sql) return sql.getSQLState();
    return null;
  }

  private void sleep(CaseContext context, long millis, String stage) {
    try {
      Thread.sleep(Math.min(millis, context.waitMillis(millis, stage)));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }

  private final class CaseContext {
    final String name;
    final long started = System.nanoTime();
    final long workDeadline = started + TimeUnit.SECONDS.toNanos(55);
    final long cleanupEnd = started + TimeUnit.SECONDS.toNanos(80);
    final AtomicBoolean abort = new AtomicBoolean();
    final AtomicBoolean workExhausted = new AtomicBoolean();
    final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    final List<Actor> actors = new CopyOnWriteArrayList<>();
    final List<Future<?>> futures = new CopyOnWriteArrayList<>();
    final ConcurrentLinkedQueue<Actor> afterLock = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<Actor> afterWrite = new ConcurrentLinkedQueue<>();
    final CountDownLatch afterLockPublished = new CountDownLatch(1);
    final CountDownLatch afterWritePublished = new CountDownLatch(1);
    volatile ExecutorService executor;
    volatile boolean cleanupSupported;
    volatile String verdict;
    volatile Throwable injectedFailure;
    volatile long cleanupDeadline;
    volatile long cleanupStarted;
    volatile double workSeconds;

    CaseContext(String name) {
      this.name = name;
    }

    long waitMillis(long maximum, String stage) {
      long remaining =
          TimeUnit.NANOSECONDS.toMillis(
              (abort.get() ? cleanupEnd : workDeadline) - System.nanoTime());
      if (remaining <= 0) {
        if (!abort.get()) {
          workExhausted.set(true);
          runStopped = true;
          abortAndRelease();
        }
        throw new AssertionError("INCONCLUSIVE: " + name + " deadline exhausted at " + stage);
      }
      return Math.max(1, Math.min(maximum, remaining));
    }

    void checkAbort(String stage) {
      if (abort.get()) throw new AssertionError("INCONCLUSIVE: " + name + " aborted at " + stage);
      waitMillis(1, stage);
    }

    boolean expectedFailure(Throwable error) {
      if (error instanceof InjectedRollback && (name.equals("A-R") || name.equals("B-R")))
        return true;
      if (name.equals("A-T") && "55P03".equals(sqlState(error))) return true;
      return name.equals("A-O") && abort.get();
    }

    void fail(Throwable error) {
      firstFailure.compareAndSet(null, error);
      abortAndRelease();
    }

    void abortAndRelease() {
      abort.set(true);
      for (Actor actor : actors) {
        actor.resume.countDown();
        actor.writeResume.countDown();
        actor.parked.countDown();
        actor.written.countDown();
      }
      afterLockPublished.countDown();
      afterWritePublished.countDown();
    }

    void quiesceAndProve() throws Exception {
      assertThat(futures).allMatch(Future::isDone);
      if (executor != null) {
        executor.shutdown();
        assertThat(
                executor.awaitTermination(
                    waitMillis(10_000, "core quiescence"), TimeUnit.MILLISECONDS))
            .isTrue();
      }
      try (Connection o = observer()) {
        for (Actor a : actors)
          if (a.tx != null) {
            assertThat(a.completion).isNotEqualTo(TransactionSynchronization.STATUS_UNKNOWN);
            assertThat(
                    on(o)
                        .queryForObject(
                            "select not exists(select 1 from pg_stat_activity where pid=? and xact_start is not null)",
                            Boolean.class,
                            a.tx.pid()))
                .isTrue();
          }
      }
      assertIdle();
      workersDrained = true;
      log("CORE_QUIESCENT case=%s workElapsed=%s", name, (System.nanoTime() - started) / 1e9);
    }

    void drainAndProve() throws Exception {
      if (cleanupDeadline == 0) {
        cleanupStarted = System.nanoTime();
        workSeconds = (cleanupStarted - started) / 1e9;
        if (workSeconds > 55) {
          workExhausted.set(true);
          runStopped = true;
          abortAndRelease();
        }
        cleanupDeadline = Math.min(cleanupEnd, System.nanoTime() + TimeUnit.SECONDS.toNanos(25));
      }
      try {
        if (futures.stream().anyMatch(f -> !f.isDone())) abortAndRelease();
        if (executor != null) {
          executor.shutdown();
          long normal = Math.min(cleanupDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
          if (!executor.awaitTermination(cleanupMillis(normal), TimeUnit.MILLISECONDS)) {
            abortAndRelease();
            executor.shutdownNow();
            long forced =
                Math.min(cleanupDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            assertThat(executor.awaitTermination(cleanupMillis(forced), TimeUnit.MILLISECONDS))
                .as("worker drain")
                .isTrue();
          }
        }
        assertThat(futures).allMatch(Future::isDone, "all actor futures completed");
        long prior = cleanupDeadline;
        cleanupDeadline =
            Math.min(cleanupDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        try (Connection o = observer()) {
          JdbcTemplate db = on(o);
          for (Actor actor : actors)
            if (actor.tx != null) {
              assertThat(actor.completion)
                  .as("completion %s", actor.name)
                  .isNotEqualTo(TransactionSynchronization.STATUS_UNKNOWN);
              assertThat(
                      db.queryForObject(
                          "select not exists(select 1 from pg_stat_activity where pid=? and xact_start is not null)",
                          Boolean.class,
                          actor.tx.pid()))
                  .as("old tx %s", actor.name)
                  .isTrue();
            }
        } finally {
          cleanupDeadline = prior;
        }
        cleanupMillis(cleanupDeadline);
        assertIdle();
        cleanupSupported = true;
        workersDrained = true;
        log(
            "DRAIN case=%s elapsed=%.6f active=0 pending=0 oldXacts=0 observer=closed",
            name, (System.nanoTime() - started) / 1e9);
      } catch (Throwable failure) {
        runStopped = true;
        cleanupSupported = false;
        abortAndRelease();
        throw failure;
      }
    }

    long cleanupMillis(long deadline) {
      long value = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
      if (value <= 0) throw new AssertionError("INCONCLUSIVE: cleanup deadline exhausted");
      return value;
    }
  }

  private static void assertGraphOracle() {
    assertThat(acceptsRootedGraph(Map.of(2, List.of(1), 3, List.of(2)), 1, 2, 3)).isTrue();
    assertThat(acceptsRootedGraph(Map.of(2, List.of(1)), 1, 2)).isTrue();
    assertThat(acceptsRootedGraph(Map.of(2, List.of()), 1, 2)).isFalse();
    assertThat(acceptsRootedGraph(Map.of(), 1, 2)).isFalse();
  }

  private static void assertWaitOracle() {
    String tag = "/* synthetic */ ";
    Map<String, Object> row =
        Map.of(
            "query", tag + "/* phase=lock-1 */ select * from " + TABLE, "wait_event_type", "Lock");
    Map<String, Object> lock =
        Map.of("pid", 2, "granted", false, "locktype", "transactionid", "transactionid", "1");
    assertThat(relevantWait(row, List.of(lock), 2, tag, Set.of("1"))).isTrue();
    assertThat(relevantWait(row, List.of(lock), 2, "wrong", Set.of("1"))).isFalse();
    assertThat(relevantWait(row, List.of(), 2, tag, Set.of("1"))).isFalse();
    assertThat(relevantWait(row, List.of(lock), 2, tag, Set.of("9"))).isFalse();
    assertThat(relevantWait(Map.of(), List.of(lock), 2, tag, Set.of("1"))).isFalse();
  }

  private static boolean acceptsRootedGraph(
      Map<Integer, List<Integer>> blockers, int root, int... waiters) {
    for (int waiter : waiters) {
      Set<Integer> seen = new HashSet<>();
      ArrayDeque<Integer> todo = new ArrayDeque<>();
      todo.add(waiter);
      while (!todo.isEmpty()) {
        int next = todo.remove();
        if (next == root) {
          seen.add(root);
          break;
        }
        if (seen.add(next)) todo.addAll(blockers.getOrDefault(next, List.of()));
      }
      if (!seen.contains(root)) return false;
    }
    return true;
  }

  private final class Actor {
    final CaseContext context;
    final String name;
    final String tag;
    final CountDownLatch parked = new CountDownLatch(1);
    final CountDownLatch resume = new CountDownLatch(1);
    final CountDownLatch written = new CountDownLatch(1);
    final CountDownLatch writeResume = new CountDownLatch(1);
    final List<Integer> lockRows = new ArrayList<>();
    final List<Long> lockGames = new ArrayList<>();
    final List<Integer> claimRows = new ArrayList<>();
    final List<Long> deleted = new ArrayList<>();
    volatile Tx tx;
    volatile Business business;
    volatile Receipt receipt;
    volatile Receipt recovered;
    volatile String currentPhase;
    volatile String errorPhase;
    volatile int completion = TransactionSynchronization.STATUS_UNKNOWN;
    volatile long started;
    volatile long ended;
    boolean pauseAfterWrite;
    int lockTimeoutSeconds = 12;

    Actor(CaseContext context, String name) {
      this.context = context;
      this.name = name;
      this.tag =
          "/* WBC case=" + context.name + " actor=" + name + " id=" + UUID.randomUUID() + " */ ";
    }

    void pause() {
      parked.countDown();
      await(context, resume, "actor pause");
    }
  }

  private static final class InjectedRollback extends RuntimeException {}

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {
    @Bean
    MutableClock mutableClock() {
      return new MutableClock(T0);
    }
  }

  static final class MutableClock extends Clock {
    private volatile Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void set(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC-only test clock");
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}

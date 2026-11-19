package org.kirikick.spike.publication.singletx;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
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
  PublicationSingleTransactionExpirySweepSpikePostgresTest.ClockConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(value = 45, unit = TimeUnit.SECONDS)
class PublicationSingleTransactionExpirySweepSpikePostgresTest {
  private static final String TABLE = "publication_single_tx_records";
  private static final String SCHEMA =
      "pub_expiry_" + UUID.randomUUID().toString().replace("-", "");
  private static final String SERVER_IDENTITY =
      "select concat_ws('|',current_database(),inet_server_addr()::text,inet_server_port()::text,pg_postmaster_start_time()::text,current_setting('server_version_num'))";
  private static final Instant T0 = Instant.parse("2026-11-18T06:00:00Z");
  private static final Duration RETENTION = Duration.ofHours(24).plusMinutes(30);
  private static final Duration GRACE = Duration.ofSeconds(47);
  private static String jdbcUrl;
  private static String serverIdentity;
  private static Properties credentials;
  private static boolean workersDrained = true;

  @Autowired DataSource dataSource;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired EntityManager entityManager;
  @Autowired Flyway flyway;
  @Autowired GameAssemblyService assembly;
  @Autowired UserRepository users;
  @Autowired RewardPresetRepository rewards;
  @Autowired MutableClock clock;
  private final List<Actor> actors = new CopyOnWriteArrayList<>();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws SQLException {
    var container = PostgresTestDatabase.container();
    jdbcUrl = container.getJdbcUrl();
    credentials = new Properties();
    credentials.setProperty("user", container.getUsername());
    credentials.setProperty("password", container.getPassword());
    credentials.setProperty("connectTimeout", "3");
    credentials.setProperty("socketTimeout", "15");
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

  @BeforeEach
  void harness() throws SQLException {
    assertThat(workersDrained).as("BLOCKED: preceding worker drain").isTrue();
    assertThat(Runtime.version().feature()).isEqualTo(21);
    assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
    assertThat(((JpaTransactionManager) transactionManager).getDataSource()).isSameAs(dataSource);
    assertThat(AopUtils.isAopProxy(assembly)).isTrue();
    assertThat(pool().getMaximumPoolSize()).isEqualTo(2);
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
    tx(
        actor("H0"),
        () -> {
          assertThat(entityManager.unwrap(Session.class).<Tx>doReturningWork(this::identity))
              .isEqualTo(identity());
          return null;
        });
    advance(T0);
    log("H0 SUPPORTED schema=%s max=2 grace=47s test-clock-only", SCHEMA);
  }

  @AfterEach
  void end() throws SQLException {
    workersDrained = false;
    assertIdle();
    try (Connection connection = observer()) {
      for (Actor actor : actors) {
        if (actor.tx != null) {
          assertThat(actor.completion)
              .as("completed %s", actor.name)
              .isNotEqualTo(TransactionSynchronization.STATUS_UNKNOWN);
          assertThat(
                  on(connection)
                      .queryForObject(
                          "select not exists(select 1 from pg_stat_activity where pid=? and xact_start is not null)",
                          Boolean.class,
                          actor.tx.pid()))
              .isTrue();
        }
      }
    }
    workersDrained = true;
    log("DRAIN SUPPORTED active=0 pending=0 actorXacts=0 observer=closed");
  }

  @Test
  @DisplayName("E0: 저장 만료 직전·정각은 주입 시각으로 구분하고 미만료 업무는 바꾸지 않는다")
  void expiryBoundary() throws Exception {
    Seed live = seed("old");
    Receipt old = seedExpiresAt(live, T0.plus(1, ChronoUnit.MICROS));
    advance(T0);
    Snapshot before = snapshot("E0-live-before");
    assertThat(request(actor("E0-same"), live, "old", Pause.NONE, false))
        .isEqualTo(new Outcome("recovered", old.game()));
    assertThat(request(actor("E0-mismatch"), live, "new", Pause.NONE, false))
        .isEqualTo(new Outcome("mismatch", old.game()));
    assertThat(candidates(live)).isEmpty();
    assertThat(sweep(actor("E0-synthetic"), List.of(ticket(live, old)), false, false)).isEmpty();
    assertThat(snapshot("E0-live-after")).isEqualTo(before);

    Seed expired = seed("old");
    Receipt expiring = seedExpiresAt(expired, T0);
    Snapshot expiredBefore = snapshot("E0-exact-before");
    Actor b = actor("E0-exact-B");
    Outcome result = request(b, expired, "new", Pause.NONE, false);
    assertThat(result.kind()).isEqualTo("created");
    assertThat(b.deleted).containsExactly(expiring.game());
    assertReplacement(expired, expiring, b, expiredBefore);

    Seed wOnly = seed("w");
    Receipt wOld = seedExpiresAt(wOnly, T0);
    Snapshot wBefore = snapshot("E0-W-before");
    assertThat(sweep(actor("E0-exact-W"), candidates(wOnly), false, false))
        .containsExactly(wOld.game());
    Snapshot wAfter = snapshot("E0-W-after");
    assertBusinessDelta(wBefore, wAfter, 0);
    assertThat(receipts(wOnly)).isEmpty();
    log("CASE E0 SUPPORTED boundary=stored-expiry-minus-1us/exact");
  }

  @Test
  @DisplayName("S1-C/G: B 잠금 중 W는 X만 건너뛰고 만료된 새 세대도 옛 티켓으로 삭제하지 않는다")
  void bFirstCommitAndStaleGeneration() throws Exception {
    bFirst(false);
  }

  @Test
  @DisplayName("S1-R: B의 교체·업무 flush 뒤 롤백은 G0와 원본 업무를 복원하고 W의 Y 커밋은 보존한다")
  void bFirstRollback() throws Exception {
    bFirst(true);
  }

  private void bFirst(boolean rollback) throws Exception {
    String name = rollback ? "S1-R" : "S1-C";
    Seed x = seed("old");
    Receipt x0 = seedExpiresAt(x, T0);
    Seed y = seed("y");
    Receipt y0 = seedExpiresAt(y, T0);
    Ticket oldTicket = candidates(x).getFirst();
    List<Ticket> batch = List.of(oldTicket, candidates(y).getFirst());
    Snapshot before = snapshot(name + "-before");
    Actor b = actor(name + "-B");
    b.pauseAfterWrite = true;
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<Outcome> bf = workers.submit(() -> request(b, x, "new", Pause.AFTER_LOCK, rollback));
      await(b.parked);
      Actor w = actor(name + "-W-batch");
      assertThat(sweep(w, batch, false, false)).containsExactly(y0.game());
      assertThat(w.lockRows).containsExactly(0, 1);
      Snapshot locked = snapshot(name + "-B-locked-W-committed");
      assertThat(receipts(x)).containsExactly(x0);
      assertThat(receipts(y)).isEmpty();
      assertBusinessDelta(before, locked, 0);
      assertThat(b.completion).isEqualTo(TransactionSynchronization.STATUS_UNKNOWN);
      b.resume.countDown();
      await(b.written);
      assertThat(sweep(actor(name + "-W-during-flush"), List.of(oldTicket), false, false))
          .isEmpty();
      assertThat(snapshot(name + "-uncommitted-business-invisible")).isEqualTo(locked);
      assertThat(b.completion).isEqualTo(TransactionSynchronization.STATUS_UNKNOWN);
      b.writeResume.countDown();
      if (rollback) assertThat(resultFailure(bf)).isInstanceOf(InjectedRollback.class);
      else assertThat(result(bf).kind()).isEqualTo("created");
    } finally {
      drain(workers, b);
    }
    if (rollback) {
      assertThat(b.completion).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
      assertThat(receipts(x)).containsExactly(x0);
      assertThat(receipts(y)).isEmpty();
      assertBusinessDelta(before, snapshot(name + "-rollback-original"), 0);
      assertGeneratedAbsent(b.business);
      assertThat(sweep(actor(name + "-W-fresh"), candidates(x), false, false))
          .containsExactly(x0.game());
      assertBusinessDelta(before, snapshot(name + "-final"), 0);
      log("CASE S1-R SUPPORTED");
      return;
    }
    Receipt x1 = assertReplacement(x, x0, b, before);
    Snapshot committed = snapshot("S1-C-committed");
    assertThat(candidates(x)).isEmpty();
    assertThat(sweep(actor("S1-C-W-stale-at-T0"), List.of(oldTicket), false, false)).isEmpty();
    assertThat(snapshot("S1-C-protected-at-T0")).isEqualTo(committed);
    log("CASE S1-C SUPPORTED");

    advance(x1.expiry());
    Ticket fresh = candidates(x).getFirst();
    assertThat(fresh.game()).isEqualTo(x1.game());
    assertThat(sweep(actor("S1-G-W-old"), List.of(oldTicket), false, false)).isEmpty();
    assertThat(snapshot("S1-G-expired-G1-old-ticket")).isEqualTo(committed);
    assertThat(sweep(actor("S1-G-W-fresh"), List.of(fresh), false, false))
        .containsExactly(x1.game());
    assertThat(receipts(x)).isEmpty();
    assertBusinessDelta(committed, snapshot("S1-G-fresh-deleted-receipt-only"), 0);
    log(
        "CASE S1-G SUPPORTED G0=%d G1=%d G1-expired=%s no-identity-lifetime-claim",
        x0.game(), x1.game(), clock.instant());
  }

  @Test
  @DisplayName("S2-C: W 커밋 뒤 B의 첫 락과 다음 락은 부재이고 새 업무 한 건만 생성한다")
  void sweeperFirstCommit() throws Exception {
    sweeperFirst(false);
  }

  @Test
  @DisplayName("S2-R: W 롤백 뒤 B가 반환받은 G0를 삭제 전에 독립 관측하고 교체한다")
  void sweeperFirstRollback() throws Exception {
    sweeperFirst(true);
  }

  private void sweeperFirst(boolean rollback) throws Exception {
    String name = rollback ? "S2-R" : "S2-C";
    Seed seed = seed("old");
    Receipt g0 = seedExpiresAt(seed, T0);
    Snapshot before = snapshot(name + "-before");
    Actor w = actor(name + "-W");
    Actor b = actor(name + "-B");
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<List<Long>> wf =
          workers.submit(() -> sweep(w, List.of(ticket(seed, g0)), true, rollback));
      await(w.parked);
      assertThat(w.deleted).containsExactly(g0.game());
      Future<Outcome> bf =
          workers.submit(
              () -> request(b, seed, "new", rollback ? Pause.AFTER_LOCK : Pause.NONE, false));
      waitFor(w, b, "lock-1");
      w.resume.countDown();
      if (rollback) {
        assertThat(resultFailure(wf)).isInstanceOf(InjectedRollback.class);
        await(b.parked);
        assertThat(b.lockGames).containsExactly(g0.game());
        assertThat(snapshot(name + "-restored-before-B-delete")).isEqualTo(before);
        b.resume.countDown();
      } else assertThat(result(wf)).containsExactly(g0.game());
      assertThat(result(bf).kind()).isEqualTo("created");
    } finally {
      drain(workers, w, b);
    }
    if (rollback) assertThat(b.lockRows).containsExactly(1);
    else assertThat(b.lockRows).containsExactly(0, 0);
    assertThat(b.deleted).containsExactlyElementsOf(rollback ? List.of(g0.game()) : List.of());
    assertThat(b.claimRows).containsExactly(1);
    assertReplacement(seed, g0, b, before);
    log("CASE %s SUPPORTED", name);
  }

  @Test
  @DisplayName("S2-U: W 커밋·슬롯 반환 뒤 B/C가 빈 키를 경쟁하고 C 한 세대만 생성한다")
  void postSweepUniqueRace() throws Exception {
    Seed seed = seed("old");
    Receipt g0 = seedExpiresAt(seed, T0);
    Snapshot before = snapshot("S2-U-before");
    Actor w = actor("S2-U-W");
    Actor b = actor("S2-U-B");
    Actor c = actor("S2-U-C");
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<List<Long>> wf =
          workers.submit(() -> sweep(w, List.of(ticket(seed, g0)), true, false));
      await(w.parked);
      Future<Outcome> bf = workers.submit(() -> request(b, seed, "new", Pause.BEFORE_CLAIM, false));
      waitFor(w, b, "lock-1");
      w.resume.countDown();
      assertThat(result(wf)).containsExactly(g0.game());
      await(b.parked);
      assertThat(b.lockRows).containsExactly(0, 0);
      assertThat(w.completion).isEqualTo(TransactionSynchronization.STATUS_COMMITTED);
      Future<Outcome> cf = workers.submit(() -> request(c, seed, "new", Pause.AFTER_WRITE, false));
      await(c.parked);
      assertThat(c.started).isGreaterThan(w.ended);
      b.resume.countDown();
      waitFor(c, b, "claim");
      c.resume.countDown();
      Outcome winner = result(cf);
      Outcome loser = result(bf);
      assertThat(winner.kind()).isEqualTo("created");
      assertThat(loser).isEqualTo(new Outcome("recovered", winner.game()));
    } finally {
      drain(workers, w, b, c);
    }
    assertThat(b.business).isNull();
    assertThat(b.claimRows).containsExactly(0);
    assertThat(b.deleted).isEmpty();
    assertThat(b.lockGames).containsExactly(c.business.game());
    assertThat(c.claimRows).containsExactly(1);
    assertReplacement(seed, g0, c, before);
    log("CASE S2-U SUPPORTED post-W-only; simultaneous-W/B/C-not-tested");
  }

  private Outcome request(Actor actor, Seed seed, String input, Pause pause, boolean rollback) {
    return tx(
        actor,
        () -> {
          Receipt current = lock(actor, seed, "lock-1");
          if (pause == Pause.AFTER_LOCK) actor.pause();
          if (current == null) current = lock(actor, seed, "lock-2");
          if (pause == Pause.BEFORE_CLAIM) actor.pause();
          for (int attempt = 0; attempt < 3; attempt++) {
            if (current != null) {
              assertThat(current.game()).isPositive();
              assertThat(current.expiry()).isNotNull();
              Instant now = clock.instant();
              log("JUDGE actor=%s current=%s now=%s", actor.name, current, now);
              if (now.isBefore(current.expiry()))
                return new Outcome(
                    current.text().equals(input) ? "recovered" : "mismatch", current.game());
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
              current = lock(actor, seed, "conflict-recheck");
              continue;
            }
            assertThat(claimed).containsExactly(seed.user());
            phase(actor, "assembly");
            Session session = entityManager.unwrap(Session.class);
            assertThat(entityManager.isJoinedToTransaction()).isTrue();
            assertThat(session.<Tx>doReturningWork(this::identity)).isEqualTo(actor.tx);
            long game =
                assembly.createRoomWithFirstGame(
                    seed.user(), seed.reward(), 1, input, List.of("one", "two"), 0);
            entityManager.flush();
            assertThat(session.<Tx>doReturningWork(this::identity)).isEqualTo(actor.tx);
            actor.business = business(jdbc, game);
            Instant expiry = expiry(actor.business.opened());
            assertThat(expiry).isAfter(clock.instant());
            actor.receipt = new Receipt(input, game, expiry);
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
            log(
                "FLUSH actor=%s business=%s receipt=%s sharedJPA/JDBC=%s",
                actor.name, actor.business, actor.receipt, actor.tx);
            if (pause == Pause.AFTER_WRITE) actor.pause();
            if (actor.pauseAfterWrite) {
              actor.written.countDown();
              await(actor.writeResume);
            }
            if (rollback) throw new InjectedRollback();
            return new Outcome("created", game);
          }
          throw new AssertionError("INCONCLUSIVE: bounded claim retry exhausted");
        });
  }

  private List<Long> sweep(Actor actor, List<Ticket> tickets, boolean pause, boolean rollback) {
    return tx(
        actor,
        () -> {
          for (Ticket ticket : tickets) {
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
            log("W_DELETE actor=%s ids=%s tx=%s", actor.name, deleted, actor.tx);
          }
          if (pause) actor.pause();
          if (rollback) throw new InjectedRollback();
          return List.copyOf(actor.deleted);
        });
  }

  private Receipt lock(Actor actor, Seed seed, String stage) {
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

  private Seed seed(String text) {
    return tx(
        actor("seed-user"),
        () ->
            new Seed(
                users.save(User.create("expiryhost", null)).getId(),
                rewards.save(RewardPreset.create("expiry", "test-only")).getId(),
                UUID.randomUUID(),
                text));
  }

  private Receipt seedExpiresAt(Seed seed, Instant expires) {
    advance(expires.minus(RETENTION).minus(GRACE));
    Actor actor = actor("seed-success");
    Outcome created = request(actor, seed, seed.text(), Pause.NONE, false);
    assertThat(created.kind()).isEqualTo("created");
    assertThat(actor.receipt.expiry()).isEqualTo(expires);
    advance(expires);
    return actor.receipt;
  }

  private Receipt assertReplacement(Seed seed, Receipt old, Actor b, Snapshot before)
      throws SQLException {
    assertThat(b.completion).isEqualTo(TransactionSynchronization.STATUS_COMMITTED);
    Receipt current = receipts(seed).getFirst();
    assertThat(receipts(seed)).containsExactly(b.receipt);
    assertThat(current.game()).isNotEqualTo(old.game());
    assertThat(current.expiry()).isAfter(T0);
    try (Connection connection = observer()) {
      assertThat(current.expiry())
          .isEqualTo(expiry(business(on(connection), current.game()).opened()));
    }
    assertBusinessDelta(before, snapshot("replacement-" + b.name), 1);
    return current;
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

  private void waitFor(Actor owner, Actor waiter, String stage)
      throws SQLException, InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
    try (Connection connection = observer()) {
      JdbcTemplate o = on(connection);
      while (System.nanoTime() < deadline) {
        if (owner.tx != null && waiter.tx != null) {
          assertThat(waiter.tx.pid()).isNotEqualTo(owner.tx.pid());
          assertThat(waiter.tx.xid()).isNotEqualTo(owner.tx.xid());
          List<Map<String, Object>> samples =
              o.queryForList(
                  "select b.query,b.wait_event_type,b.wait_event,a.backend_xid::text owner_xid,b.backend_xid::text waiter_xid,l.transactionid::text waiting_xid,l.locktype,pg_blocking_pids(b.pid)::text blockers from pg_stat_activity a,pg_stat_activity b,pg_locks l where a.pid=? and b.pid=? and l.pid=b.pid and not l.granted and a.pid=any(pg_blocking_pids(b.pid))",
                  owner.tx.pid(),
                  waiter.tx.pid());
          for (var sample : samples) {
            if ("Lock".equals(sample.get("wait_event_type"))
                && "transactionid".equals(sample.get("locktype"))
                && owner.tx.xid().equals(sample.get("owner_xid"))
                && owner.tx.xid().equals(sample.get("waiting_xid"))
                && waiter.tx.xid().equals(sample.get("waiter_xid"))
                && ((String) sample.get("query"))
                    .startsWith(waiter.tag + "/* phase=" + stage + " */")) {
              log(
                  "O_WAIT owner=%s waiter=%s phase=%s sample=%s mono=%d",
                  owner.tx, waiter.tx, stage, sample, System.nanoTime());
              return;
            }
          }
        }
        Thread.sleep(10);
      }
    }
    throw new AssertionError("INCONCLUSIVE: tagged PostgreSQL xid lock wait missing " + stage);
  }

  private Actor actor(String name) {
    Actor actor = new Actor(name);
    actors.add(actor);
    return actor;
  }

  private void phase(Actor actor, String phase) {
    assertThat(identity()).isEqualTo(actor.tx);
    log(
        "PHASE actor=%s phase=%s tx=%s logical=%s mono=%d",
        actor.name, phase, actor.tx, clock.instant(), System.nanoTime());
  }

  private <T> T tx(Actor actor, Supplier<T> work) {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setTimeout(12);
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
            jdbc.execute("set local statement_timeout='7s'");
            jdbc.execute("set local lock_timeout='6s'");
            actor.tx = identity();
            actor.started = System.nanoTime();
            log(
                "TX actor=%s tag=%s tx=%s autocommit=false isolation=read-committed schema=%s",
                actor.name, actor.tag, actor.tx, SCHEMA);
            return work.get();
          });
      log(
          "TEMPLATE_RETURN actor=%s completion=%d tx=%s mono=%d",
          actor.name, actor.completion, actor.tx, System.nanoTime());
      return value;
    } catch (RuntimeException | Error error) {
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
    Connection connection = DriverManager.getConnection(jdbcUrl, credentials);
    try {
      JdbcTemplate o = on(connection);
      o.execute("set statement_timeout='3s'");
      o.execute("set search_path to " + SCHEMA);
      assertThat(o.queryForObject(SERVER_IDENTITY, String.class)).isEqualTo(serverIdentity);
      assertThat(connection.getAutoCommit()).isTrue();
      log(
          "OBSERVER pid=%s direct=true schema=%s",
          o.queryForObject("select pg_backend_pid()", Integer.class), SCHEMA);
      return connection;
    } catch (RuntimeException | Error error) {
      connection.close();
      throw error;
    }
  }

  private static JdbcTemplate on(Connection connection) {
    return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
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

  private void drain(ExecutorService workers, Actor... staged) throws InterruptedException {
    workersDrained = false;
    for (Actor actor : staged) {
      actor.resume.countDown();
      actor.writeResume.countDown();
    }
    workers.shutdown();
    if (!workers.awaitTermination(12, TimeUnit.SECONDS)) {
      workers.shutdownNow();
      assertThat(workers.awaitTermination(4, TimeUnit.SECONDS))
          .as("INCONCLUSIVE: worker drain")
          .isTrue();
    }
    assertIdle();
    workersDrained = true;
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).as("INCONCLUSIVE: barrier deadline").isTrue();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }

  private static <T> T result(Future<T> future) throws Exception {
    T value = future.get(12, TimeUnit.SECONDS);
    log("FUTURE_RETURN value=%s mono=%d", value, System.nanoTime());
    return value;
  }

  private static Throwable resultFailure(Future<?> future) throws Exception {
    try {
      future.get(12, TimeUnit.SECONDS);
      throw new AssertionError("expected rollback");
    } catch (ExecutionException error) {
      log(
          "FUTURE_THROW type=%s mono=%d",
          error.getCause().getClass().getSimpleName(), System.nanoTime());
      return error.getCause();
    }
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
    System.out.println("EXPIRY " + String.format(format, args));
  }

  private record Seed(long user, long reward, UUID key, String text) {}

  private record Receipt(String text, long game, Instant expiry) {}

  private record Ticket(long user, UUID key, long game) {}

  private record Business(long room, long game, Instant opened, long correct, List<Long> choices) {}

  private record Outcome(String kind, long game) {}

  private record Tx(int pid, String xid) {}

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

  private static final class Actor {
    final String name;
    final String tag = "/* expiry " + UUID.randomUUID() + " */ ";
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
    volatile int completion = TransactionSynchronization.STATUS_UNKNOWN;
    volatile long started;
    volatile long ended;
    boolean pauseAfterWrite;

    Actor(String name) {
      this.name = name;
    }

    void pause() {
      parked.countDown();
      await(resume);
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

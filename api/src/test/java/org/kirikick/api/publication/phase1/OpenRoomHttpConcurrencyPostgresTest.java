package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.SignupApplication;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.game.domain.Choice;
import org.kirikick.api.game.domain.Game;
import org.kirikick.api.question.domain.QuestionPreset;
import org.kirikick.api.question.domain.QuestionPresetChoice;
import org.kirikick.api.reward.domain.RewardPreset;
import org.kirikick.api.room.domain.Room;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Actual HTTP concurrency coverage; the PostgreSQL advisory lock is a test-only scheduling point.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(OpenRoomHttpConcurrencyPostgresTest.DomainEntities.class)
class OpenRoomHttpConcurrencyPostgresTest {
  private static final long CLAIM_GATE = 775_075L;

  @LocalServerPort private int port;
  @Autowired private ObjectMapper json;
  @Autowired private JdbcClient jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private UserRepository users;
  @Autowired private LoginSessionService logins;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    registry.add("spring.datasource.url", OpenRoomHttpConcurrencyPostgresTest::nk75JdbcUrl);
    registry.add("spring.datasource.username", PostgresTestDatabase.container()::getUsername);
    registry.add("spring.datasource.password", PostgresTestDatabase.container()::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.default-schema", () -> "nk75_http_concurrency");
    registry.add("publication.grace", () -> "PT5M");
  }

  @BeforeEach
  void requirePostgres() {
    assertThat(jdbc.sql("select version()").query(String.class).single()).contains("PostgreSQL 17");
  }

  @Test
  @DisplayName("C-01/C-02: 실제 HTTP 같은 키 경합은 DB 잠금 뒤 하나의 공개와 복구 또는 충돌만 남긴다")
  void serializesSameKeyHttpRequestsAtActualPublicationClaim() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    try (ClaimGate gate = installClaimGate();
        ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<Integer> sameInputFirst =
          workers.submit(() ->
              fixture()
                  .post(
                      fixture,
                      key(1),
                      fixture.choiceIds(),
                      fixture.choiceIds().getFirst(),
                      fixture.rewardId())
                  .statusCode());
      WaitEvidence firstWait = awaitGateWait(gate.holderPid(), sameInputFirst);
      Future<Integer> sameInputSecond =
          workers.submit(() ->
              fixture()
                  .post(
                      fixture,
                      key(1),
                      fixture.choiceIds().reversed(),
                      fixture.choiceIds().getFirst(),
                      fixture.rewardId())
                  .statusCode());
      WaitEvidence secondWait = awaitGateWait(gate.holderPid(), sameInputSecond);
      gate.release();

      assertThat(
              List.of(
                  sameInputFirst.get(15, TimeUnit.SECONDS),
                  sameInputSecond.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(201, 200);
      assertPublicationCounts(fixture.user().getId(), 1, 1, 4, 1);
      assertWaits(firstWait, secondWait, gate.holderPid());
      recordFinalState("C01", fixture.user().getId());
    }
  }

  @Test
  @DisplayName("C-02: 실제 HTTP 같은 키의 다른 의미 경합은 한 공개와 하나의 충돌만 남긴다")
  void serializesSameKeyDifferentInputHttpRequestsAtActualPublicationClaim() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    try (ClaimGate gate = installClaimGate();
        ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<Integer> first =
          workers.submit(() ->
              fixture()
                  .post(
                      fixture,
                      key(2),
                      fixture.choiceIds(),
                      fixture.choiceIds().getFirst(),
                      fixture.rewardId())
                  .statusCode());
      WaitEvidence firstWait = awaitGateWait(gate.holderPid(), first);
      Future<Integer> second =
          workers.submit(() ->
              fixture()
                  .post(
                      fixture,
                      key(2),
                      fixture.choiceIds(),
                      fixture.choiceIds().getFirst(),
                      fixture.alternateRewardId())
                  .statusCode());
      WaitEvidence secondWait = awaitGateWait(gate.holderPid(), second);
      gate.release();

      assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(201, 409);
      assertPublicationCounts(fixture.user().getId(), 1, 1, 4, 1);
      assertWaits(firstWait, secondWait, gate.holderPid());
      recordFinalState("C02", fixture.user().getId());
    }
  }

  @Test
  @DisplayName("C-03: 실제 HTTP 같은 방장의 다른 키 경합은 User 잠금 뒤 한 공개와 한 OPEN 충돌만 남긴다")
  void serializesSameHostDifferentKeyHttpRequestsAtActualPublicationClaim() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    try (ClaimGate gate = installClaimGate();
        ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<Integer> first =
          workers.submit(() ->
              fixture()
                  .post(
                      fixture,
                      key(3),
                      fixture.choiceIds(),
                      fixture.choiceIds().getFirst(),
                      fixture.rewardId())
                  .statusCode());
      WaitEvidence firstWait = awaitGateWait(gate.holderPid(), first);
      Future<Integer> second =
          workers.submit(() ->
              fixture()
                  .post(
                      fixture,
                      key(4),
                      fixture.choiceIds(),
                      fixture.choiceIds().getFirst(),
                      fixture.alternateRewardId())
                  .statusCode());
      WaitEvidence secondWait = awaitGateWait(gate.holderPid(), second);
      gate.release();

      assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(201, 409);
      assertPublicationCounts(fixture.user().getId(), 1, 1, 4, 1);
      assertWaits(firstWait, secondWait, gate.holderPid());
      recordFinalState("C03", fixture.user().getId());
    }
  }

  @Test
  @DisplayName("C-04: 다른 방장은 한 방장의 실제 User 잠금 대기와 독립적으로 HTTP 공개한다")
  void allowsDifferentHostWhileAnotherHostWaitsForUserLock() throws Exception {
    NkCreateHttpFixture.Fixture blockedFixture = fixture().fixture();
    NkCreateHttpFixture.Fixture independentFixture = fixture().fixture();
    try (Connection holder = lockUser(blockedFixture.user().getId());
        ExecutorService workers = Executors.newFixedThreadPool(2)) {
      int holderPid = backendPid(holder);
      Future<Integer> blocked =
          workers.submit(() ->
              fixture()
                  .post(
                      blockedFixture,
                      key(5),
                      blockedFixture.choiceIds(),
                      blockedFixture.choiceIds().getFirst(),
                      blockedFixture.rewardId())
                  .statusCode());
      WaitEvidence blockedWait = awaitWaitFor(holderPid, blocked);

      Future<Integer> independent =
          workers.submit(() ->
              fixture()
                  .post(
                      independentFixture,
                      key(6),
                      independentFixture.choiceIds(),
                      independentFixture.choiceIds().getFirst(),
                      independentFixture.rewardId())
                  .statusCode());
      assertThat(independent.get(15, TimeUnit.SECONDS)).isEqualTo(201);
      assertThat(blocked.isDone()).isFalse();
      holder.commit();
      recordReleaseEvidence(holderPid, "COMMIT");
      assertThat(blocked.get(15, TimeUnit.SECONDS)).isEqualTo(201);
      assertThat(blockedWait.waitEventType()).isEqualTo("Lock");
      assertThat(blockedWait.blockerPids()).contains(Integer.toString(holderPid));
      assertPublicationCounts(blockedFixture.user().getId(), 1, 1, 4, 1);
      assertPublicationCounts(independentFixture.user().getId(), 1, 1, 4, 1);
      recordFinalState("C04-blocked", blockedFixture.user().getId());
      recordFinalState("C04-independent", independentFixture.user().getId());
    }
  }

  @Test
  @DisplayName("C-05/C-06: 실제 Room 잠금 대기 뒤 DB 마감과 holder commit 또는 rollback을 다시 판정한다")
  void rechecksRoomStateAfterActualRoomLockWait() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    long roomId =
        insertRoom(fixture, "OPEN", "now() - interval '24 hours'", "now() + interval '5 seconds'");
    try (Connection holder = lockRoom(roomId);
        ExecutorService workers = Executors.newSingleThreadExecutor()) {
      int holderPid = backendPid(holder);
      Future<Integer> pending =
          workers.submit(() ->
              fixture()
                  .post(
                      fixture,
                      key(7),
                      fixture.choiceIds(),
                      fixture.choiceIds().getFirst(),
                      fixture.rewardId())
                  .statusCode());
      WaitEvidence wait = awaitWaitFor(holderPid, pending);
      Instant closesAt =
          jdbc.sql("select closes_at from rooms where id = :roomId")
              .param("roomId", roomId)
              .query(Instant.class)
              .single();
      awaitDbDeadline(closesAt, pending);
      holder.commit();
      recordReleaseEvidence(holderPid, "COMMIT");
      assertThat(pending.get(15, TimeUnit.SECONDS)).isEqualTo(201);
      assertThat(wait.waitEventType()).isEqualTo("Lock");
      assertThat(wait.blockerPids()).contains(Integer.toString(holderPid));
      assertThat(roomStatus(roomId)).isEqualTo("CLOSED");
      recordFinalState("C05", fixture.user().getId());
    }

    NkCreateHttpFixture.Fixture committedFixture = fixture().fixture();
    long committedRoomId =
        insertRoom(
            committedFixture, "OPEN", "now() - interval '1 hour'", "now() + interval '1 hour'");
    try (Connection holder = lockRoom(committedRoomId);
        ExecutorService workers = Executors.newSingleThreadExecutor()) {
      int holderPid = backendPid(holder);
      Future<Integer> pending =
          workers.submit(() ->
              fixture()
                  .post(
                      committedFixture,
                      key(12),
                      committedFixture.choiceIds(),
                      committedFixture.choiceIds().getFirst(),
                      committedFixture.rewardId())
                  .statusCode());
      WaitEvidence wait = awaitWaitFor(holderPid, pending);
      try (Statement statement = holder.createStatement()) {
        statement.execute("update rooms set status = 'CLOSED' where id = " + committedRoomId);
      }
      holder.commit();
      recordReleaseEvidence(holderPid, "COMMIT");
      assertThat(pending.get(15, TimeUnit.SECONDS)).isEqualTo(201);
      assertThat(wait.waitEventType()).isEqualTo("Lock");
      assertThat(wait.blockerPids()).contains(Integer.toString(holderPid));
      assertThat(roomStatus(committedRoomId)).isEqualTo("CLOSED");
      recordFinalState("C06-commit", committedFixture.user().getId());
    }

    NkCreateHttpFixture.Fixture rollbackFixture = fixture().fixture();
    long rollbackRoomId =
        insertRoom(
            rollbackFixture, "OPEN", "now() - interval '1 hour'", "now() + interval '1 hour'");
    try (Connection holder = lockRoom(rollbackRoomId);
        ExecutorService workers = Executors.newSingleThreadExecutor()) {
      int holderPid = backendPid(holder);
      Future<Integer> pending =
          workers.submit(() ->
              fixture()
                  .post(
                      rollbackFixture,
                      key(8),
                      rollbackFixture.choiceIds(),
                      rollbackFixture.choiceIds().getFirst(),
                      rollbackFixture.rewardId())
                  .statusCode());
      WaitEvidence wait = awaitWaitFor(holderPid, pending);
      try (Statement statement = holder.createStatement()) {
        statement.execute("update rooms set status = 'CLOSED' where id = " + rollbackRoomId);
      }
      holder.rollback();
      recordReleaseEvidence(holderPid, "ROLLBACK");
      assertThat(pending.get(15, TimeUnit.SECONDS)).isEqualTo(409);
      assertThat(wait.waitEventType()).isEqualTo("Lock");
      assertThat(wait.blockerPids()).contains(Integer.toString(holderPid));
      assertThat(roomStatus(rollbackRoomId)).isEqualTo("OPEN");
      recordFinalState("C06-rollback", rollbackFixture.user().getId());
    }
  }

  @Test
  @DisplayName("C-07: 만료된 같은 키 교체와 다른 키 신규 HTTP 경합은 한 공개와 이전 Game 보존을 남긴다")
  void racesExpiredGenerationAgainstDifferentKeyThroughActualHttp() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    String expiredKey = key(9);
    assertThat(
            fixture()
                .post(
                    fixture,
                    expiredKey,
                    fixture.choiceIds(),
                    fixture.choiceIds().getFirst(),
                    fixture.rewardId())
                .statusCode())
        .isEqualTo(201);
    long priorGameId =
        jdbc.sql(
                "select game_id from publication_idempotency where user_id = :userId and request_key = :key")
            .param("userId", fixture.user().getId())
            .param("key", expiredKey)
            .query(Long.class)
            .single();
    jdbc.sql("update rooms set status = 'CLOSED' where host_user_id = :userId")
        .param("userId", fixture.user().getId())
        .update();
    jdbc.sql(
            "update publication_idempotency set record_expires_at = now() - interval '1 microsecond' where user_id = :userId and request_key = :key")
        .param("userId", fixture.user().getId())
        .param("key", expiredKey)
        .update();
    try (Connection holder = lockUser(fixture.user().getId());
        ExecutorService workers = Executors.newFixedThreadPool(2)) {
      int holderPid = backendPid(holder);
      Future<Integer> replacement =
          workers.submit(() ->
              fixture()
                  .post(
                      fixture,
                      expiredKey,
                      fixture.choiceIds(),
                      fixture.choiceIds().getFirst(),
                      fixture.alternateRewardId())
                  .statusCode());
      WaitEvidence replacementWait = awaitWaitFor(holderPid, replacement);
      Future<Integer> newKey =
          workers.submit(() ->
              fixture()
                  .post(
                      fixture,
                      key(10),
                      fixture.choiceIds(),
                      fixture.choiceIds().getFirst(),
                      fixture.rewardId())
                  .statusCode());
      WaitEvidence newKeyWait = awaitWaitFor(holderPid, newKey);
      holder.commit();
      recordReleaseEvidence(holderPid, "COMMIT");
      int replacementStatus = replacement.get(15, TimeUnit.SECONDS);
      int newKeyStatus = newKey.get(15, TimeUnit.SECONDS);
      assertThat(List.of(replacementStatus, newKeyStatus)).containsExactlyInAnyOrder(201, 409);
      assertThat(replacementWait.waitEventType()).isEqualTo("Lock");
      assertThat(newKeyWait.waitEventType()).isEqualTo("Lock");
      assertThat(
              jdbc.sql("select count(*) from games where id = :id")
                  .param("id", priorGameId)
                  .query(Long.class)
                  .single())
          .isEqualTo(1L);
      if (replacementStatus == 201) {
        assertThat(recordGameId(fixture.user().getId(), expiredKey)).isNotEqualTo(priorGameId);
        assertThat(recordCount(fixture.user().getId())).isEqualTo(1L);
      } else {
        assertThat(recordGameId(fixture.user().getId(), expiredKey)).isEqualTo(priorGameId);
        assertThat(recordGameId(fixture.user().getId(), key(10))).isNotEqualTo(priorGameId);
        assertThat(recordCount(fixture.user().getId())).isEqualTo(2L);
      }
      assertPublicationCounts(fixture.user().getId(), 2, 2, 8, recordCount(fixture.user().getId()));
      recordFinalState("C07", fixture.user().getId());
    }
  }

  @Test
  @DisplayName("C-08: 만료된 같은 키 HTTP 둘은 실제 generation UPDATE 잠금 뒤 한 교체와 한 복구를 남긴다")
  void serializesExpiredSameKeyGenerationReplacementThroughActualHttp() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    String expiredKey = key(11);
    assertThat(
            fixture()
                .post(
                    fixture,
                    expiredKey,
                    fixture.choiceIds(),
                    fixture.choiceIds().getFirst(),
                    fixture.rewardId())
                .statusCode())
        .isEqualTo(201);
    jdbc.sql("update rooms set status = 'CLOSED' where host_user_id = :userId")
        .param("userId", fixture.user().getId())
        .update();
    jdbc.sql(
            "update publication_idempotency set record_expires_at = now() - interval '1 microsecond' where user_id = :userId and request_key = :key")
        .param("userId", fixture.user().getId())
        .param("key", expiredKey)
        .update();
    try (ClaimGate gate = installGenerationGate();
        ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<Integer> first =
          workers.submit(() ->
              fixture()
                  .post(
                      fixture,
                      expiredKey,
                      fixture.choiceIds(),
                      fixture.choiceIds().getFirst(),
                      fixture.alternateRewardId())
                  .statusCode());
      WaitEvidence firstWait = awaitGateWait(gate.holderPid(), first);
      Future<Integer> second =
          workers.submit(() ->
              fixture()
                  .post(
                      fixture,
                      expiredKey,
                      fixture.choiceIds().reversed(),
                      fixture.choiceIds().getFirst(),
                      fixture.alternateRewardId())
                  .statusCode());
      WaitEvidence secondWait = awaitWaitFor(firstWait.waiterPid(), second);
      gate.release();
      assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(201, 200);
      assertThat(firstWait.waitEventType()).isEqualTo("Lock");
      assertThat(secondWait.waitEventType()).isEqualTo("Lock");
      assertThat(secondWait.blockerPids()).contains(Integer.toString(firstWait.waiterPid()));
      assertPublicationCounts(fixture.user().getId(), 2, 2, 8, 1);
      recordFinalState("C08", fixture.user().getId());
    }
  }

  private NkCreateHttpFixture fixture() {
    return new NkCreateHttpFixture(port, json, jdbc, users, logins);
  }

  private ClaimGate installClaimGate() throws Exception {
    return installGate(
        "insert", "hold_http_publication_claim_trigger", "hold_http_publication_claim");
  }

  private ClaimGate installGenerationGate() throws Exception {
    return installGate(
        "update", "hold_http_generation_update_trigger", "hold_http_generation_update");
  }

  private ClaimGate installGate(String event, String triggerName, String functionName)
      throws Exception {
    jdbc.sql(
            """
            create or replace function %s() returns trigger language plpgsql as $$
            begin
              perform pg_advisory_xact_lock(%d);
              return new;
            end $$;
            drop trigger if exists %s on publication_idempotency;
            create trigger %s before %s on publication_idempotency
              for each row execute function %s()
            """
                .formatted(functionName, CLAIM_GATE, triggerName, triggerName, event, functionName))
        .update();
    Connection holder = dataSource.getConnection();
    holder.setAutoCommit(false);
    try (Statement statement = holder.createStatement()) {
      statement.execute("select pg_advisory_xact_lock(" + CLAIM_GATE + ")");
    }
    return new ClaimGate(holder, backendPid(holder), jdbc, triggerName, functionName);
  }

  private Connection lockUser(long userId) throws Exception {
    Connection holder = dataSource.getConnection();
    holder.setAutoCommit(false);
    try (var statement =
        holder.prepareStatement("select id from users where id = ? for no key update")) {
      statement.setLong(1, userId);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
      }
    }
    return holder;
  }

  private Connection lockRoom(long roomId) throws Exception {
    Connection holder = dataSource.getConnection();
    holder.setAutoCommit(false);
    try (var statement = holder.prepareStatement("select id from rooms where id = ? for update")) {
      statement.setLong(1, roomId);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
      }
    }
    return holder;
  }

  private WaitEvidence awaitGateWait(int holderPid, Future<?> pending) {
    return awaitWaitFor(holderPid, pending);
  }

  private WaitEvidence awaitWaitFor(int holderPid, Future<?> pending) {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      WaitEvidence evidence =
          jdbc.sql(
                  """
                  select pid, coalesce(array_to_string(pg_blocking_pids(pid), ','), ''),
                         coalesce(wait_event_type, ''), coalesce(wait_event, ''),
                         coalesce(backend_xid::text, ''), coalesce(query, '')
                  from pg_stat_activity
                  where pid <> pg_backend_pid() and :holderPid = any(pg_blocking_pids(pid))
                  order by query_start desc limit 1
                  """)
              .param("holderPid", holderPid)
              .query((row, rowNum) ->
                  new WaitEvidence(
                      row.getInt(1),
                      row.getString(2),
                      row.getString(3),
                      row.getString(4),
                      row.getString(5),
                      row.getString(6)))
              .optional()
              .orElse(null);
      if (evidence != null) {
        recordWaitEvidence(evidence);
        return evidence;
      }
      if (pending.isDone()) {
        throw new AssertionError(
            "HTTP request completed before the PostgreSQL claim-gate wait was observed");
      }
      LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
    }
    throw new AssertionError(
        "did not observe HTTP request blocked at PostgreSQL publication claim gate");
  }

  private void recordWaitEvidence(WaitEvidence evidence) {
    String directory = System.getenv("NK75_HTTP_COMMIT_EVIDENCE_DIR");
    if (directory == null || directory.isBlank()) {
      return;
    }
    try {
      Path target = Path.of(directory, "http-concurrency-" + UUID.randomUUID() + ".json");
      Files.createDirectories(target.getParent());
      String pids = evidence.waiterPid() + "," + evidence.blockerPids();
      json.writeValue(
          target.toFile(),
          Map.of(
              "observedAt",
              Instant.now().toString(),
              "waiterPid",
              evidence.waiterPid(),
              "blockerPids",
              evidence.blockerPids(),
              "waitEventType",
              evidence.waitEventType(),
              "waitEvent",
              evidence.waitEvent(),
              "xid",
              evidence.xid(),
              "sql",
              evidence.sql(),
              "activity",
              jdbc.sql(
                      "select pid, coalesce(backend_xid::text, '') as xid, coalesce(state, '') as state, coalesce(wait_event_type, '') as wait_type, coalesce(wait_event, '') as wait_event, coalesce(query, '') as query from pg_stat_activity where pid in ("
                          + pids
                          + ") order by pid")
                  .query((row, rowNum) ->
                      Map.of(
                          "pid",
                          row.getInt("pid"),
                          "xid",
                          row.getString("xid"),
                          "state",
                          row.getString("state"),
                          "waitType",
                          row.getString("wait_type"),
                          "waitEvent",
                          row.getString("wait_event"),
                          "sql",
                          row.getString("query")))
                  .list(),
              "locks",
              jdbc.sql(
                      "select pid, locktype, coalesce(relation::regclass::text, '') as relation, mode, granted, coalesce(transactionid::text, '') as xid from pg_locks where pid in ("
                          + pids
                          + ") order by pid, locktype, mode")
                  .query((row, rowNum) ->
                      Map.of(
                          "pid",
                          row.getInt("pid"),
                          "locktype",
                          row.getString("locktype"),
                          "relation",
                          row.getString("relation"),
                          "mode",
                          row.getString("mode"),
                          "granted",
                          row.getBoolean("granted"),
                          "xid",
                          row.getString("xid")))
                  .list()));
    } catch (Exception exception) {
      throw new AssertionError(
          "could not persist HTTP concurrency PostgreSQL wait evidence", exception);
    }
  }

  private void recordFinalState(String scenario, long userId) {
    String directory = System.getenv("NK75_HTTP_COMMIT_EVIDENCE_DIR");
    if (directory == null || directory.isBlank()) {
      return;
    }
    try {
      Path target =
          Path.of(
              directory, "http-concurrency-final-" + scenario + "-" + UUID.randomUUID() + ".json");
      Files.createDirectories(target.getParent());
      json.writeValue(
          target.toFile(),
          Map.of(
              "observedAt",
              Instant.now().toString(),
              "scenario",
              scenario,
              "userId",
              userId,
              "records",
              recordCount(userId),
              "rooms",
              jdbc.sql(
                      "select id, status, result_state, coalesce(opened_at::text, '') as opened_at, coalesce(closes_at::text, '') as closes_at from rooms where host_user_id = :userId order by id")
                  .param("userId", userId)
                  .query((row, rowNum) ->
                      Map.of(
                          "id",
                          row.getLong("id"),
                          "status",
                          row.getString("status"),
                          "resultState",
                          row.getString("result_state"),
                          "openedAt",
                          row.getString("opened_at"),
                          "closesAt",
                          row.getString("closes_at")))
                  .list(),
              "generations",
              jdbc.sql(
                      "select request_key, claim_id::text as claim_id, coalesce(game_id::text, '') as game_id, coalesce(record_expires_at::text, '') as expires_at from publication_idempotency where user_id = :userId order by request_key")
                  .param("userId", userId)
                  .query((row, rowNum) ->
                      Map.of(
                          "requestKey",
                          row.getString("request_key"),
                          "claimId",
                          row.getString("claim_id"),
                          "gameId",
                          row.getString("game_id"),
                          "expiresAt",
                          row.getString("expires_at")))
                  .list()));
    } catch (Exception exception) {
      throw new AssertionError(
          "could not persist HTTP concurrency final-state evidence", exception);
    }
  }

  private void recordReleaseEvidence(int holderPid, String outcome) {
    String directory = System.getenv("NK75_HTTP_COMMIT_EVIDENCE_DIR");
    if (directory == null || directory.isBlank()) {
      return;
    }
    try {
      Path target = Path.of(directory, "http-concurrency-release-" + UUID.randomUUID() + ".json");
      Files.createDirectories(target.getParent());
      json.writeValue(
          target.toFile(),
          Map.of(
              "observedAt",
              Instant.now().toString(),
              "holderPid",
              holderPid,
              "outcome",
              outcome,
              "activity",
              jdbc.sql(
                      "select coalesce(backend_xid::text, '') as xid, coalesce(state, '') as state, coalesce(query, '') as query from pg_stat_activity where pid = :pid")
                  .param("pid", holderPid)
                  .query((row, rowNum) ->
                      Map.of(
                          "xid",
                          row.getString("xid"),
                          "state",
                          row.getString("state"),
                          "sql",
                          row.getString("query")))
                  .optional()
                  .orElse(Map.of("state", "connection-not-visible"))));
    } catch (Exception exception) {
      throw new AssertionError("could not persist HTTP concurrency release evidence", exception);
    }
  }

  private void awaitDbDeadline(Instant closesAt, Future<?> pending) {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      Instant now = jdbc.sql("select clock_timestamp()").query(Instant.class).single();
      if (!now.isBefore(closesAt)) {
        return;
      }
      if (pending.isDone()) {
        throw new AssertionError("HTTP request completed before the Room close deadline passed");
      }
      LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
    }
    throw new AssertionError(
        "database close deadline did not pass while HTTP request waited on Room lock");
  }

  private long insertRoom(
      NkCreateHttpFixture.Fixture fixture, String status, String openedAt, String closesAt) {
    return jdbc.sql(
            """
            insert into rooms(host_user_id, selected_reward_preset_id, status, result_state, opened_at, closes_at, created_at)
            values (:userId, :rewardId, :status, 'NOT_READY', %s, %s, now()) returning id
            """
                .formatted(openedAt, closesAt))
        .param("userId", fixture.user().getId())
        .param("rewardId", fixture.rewardId())
        .param("status", status)
        .query(Long.class)
        .single();
  }

  private String roomStatus(long roomId) {
    return jdbc.sql("select status from rooms where id = :roomId")
        .param("roomId", roomId)
        .query(String.class)
        .single();
  }

  private long recordCount(long userId) {
    return jdbc.sql("select count(*) from publication_idempotency where user_id = :userId")
        .param("userId", userId)
        .query(Long.class)
        .single();
  }

  private long recordGameId(long userId, String requestKey) {
    return jdbc.sql(
            "select game_id from publication_idempotency where user_id = :userId and request_key = :requestKey")
        .param("userId", userId)
        .param("requestKey", requestKey)
        .query(Long.class)
        .single();
  }

  private static void assertWaits(WaitEvidence first, WaitEvidence second, int holderPid) {
    assertThat(first.blockerPids()).contains(Integer.toString(holderPid));
    assertThat(second.blockerPids()).contains(Integer.toString(holderPid));
    assertThat(first.waitEventType()).isEqualTo("Lock");
    assertThat(second.waitEventType()).isEqualTo("Lock");
  }

  private int backendPid(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("select pg_backend_pid()")) {
      assertThat(result.next()).isTrue();
      return result.getInt(1);
    }
  }

  private void assertPublicationCounts(
      long userId, long rooms, long games, long choices, long records) {
    assertThat(
            jdbc.sql("select count(*) from rooms where host_user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single())
        .isEqualTo(rooms);
    assertThat(
            jdbc.sql(
                    "select count(*) from games g join rooms r on r.id = g.room_id where r.host_user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single())
        .isEqualTo(games);
    assertThat(
            jdbc.sql(
                    "select count(*) from choices c join games g on g.id = c.game_id join rooms r on r.id = g.room_id where r.host_user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single())
        .isEqualTo(choices);
    assertThat(
            jdbc.sql("select count(*) from publication_idempotency where user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single())
        .isEqualTo(records);
  }

  private static String key(int suffix) {
    return "1b541b3d-498b-4a79-894c-e0d2cc842" + String.format("%03d", suffix);
  }

  private record WaitEvidence(
      int waiterPid,
      String blockerPids,
      String waitEventType,
      String waitEvent,
      String xid,
      String sql) {}

  private final class ClaimGate implements AutoCloseable {
    private final Connection holder;
    private final int holderPid;
    private final JdbcClient jdbc;
    private final String triggerName;
    private final String functionName;
    private boolean released;

    private ClaimGate(
        Connection holder,
        int holderPid,
        JdbcClient jdbc,
        String triggerName,
        String functionName) {
      this.holder = holder;
      this.holderPid = holderPid;
      this.jdbc = jdbc;
      this.triggerName = triggerName;
      this.functionName = functionName;
    }

    int holderPid() {
      return holderPid;
    }

    void release() throws Exception {
      if (!released) {
        holder.commit();
        released = true;
        recordReleaseEvidence(holderPid, "COMMIT");
      }
    }

    @Override
    public void close() throws Exception {
      try {
        if (!released) {
          holder.rollback();
          recordReleaseEvidence(holderPid, "ROLLBACK");
        }
      } finally {
        holder.close();
        jdbc.sql("drop trigger if exists " + triggerName + " on publication_idempotency").update();
        jdbc.sql("drop function if exists " + functionName + "()").update();
      }
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EntityScan(
      basePackageClasses = {
        OAuthIdentity.class,
        SignupApplication.class,
        User.class,
        RewardPreset.class,
        QuestionPreset.class,
        QuestionPresetChoice.class,
        Room.class,
        Game.class,
        Choice.class
      })
  static class DomainEntities {}

  private static String nk75JdbcUrl() {
    return NkCreateHttpFixture.withCurrentSchema(
        PostgresTestDatabase.container().getJdbcUrl(), "nk75_http_concurrency");
  }
}

package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.willReturn;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.common.infra.DbTimeSource;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(OpenRoomHttpPostgresTest.DomainEntities.class)
class AddKickHttpConcurrencyPostgresTest {

  @LocalServerPort private int port;
  @Autowired private ObjectMapper json;
  @Autowired private JdbcClient jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private UserRepository users;
  @Autowired private LoginSessionService logins;
  @MockitoSpyBean private DbTimeSource clock;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    registry.add("spring.datasource.url", AddKickHttpConcurrencyPostgresTest::jdbcUrl);
    registry.add("spring.datasource.username", PostgresTestDatabase.container()::getUsername);
    registry.add("spring.datasource.password", PostgresTestDatabase.container()::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.default-schema", () -> "nk76_http_concurrency");
    registry.add("publication.grace", () -> "PT5M");
  }

  @Test
  @DisplayName("C-01: 같은 키 요청은 실제 User 잠금과 선점 XID 대기 뒤 한 추가와 한 복구만 남긴다")
  void serializesSameKeyAtRawIdempotencyClaim() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    String existingGameBefore = existingGameSnapshot(fixture.existingGameId());

    try (Connection holder = lockUser(fixture.user().getId());
        ExecutorService workers = Executors.newFixedThreadPool(2)) {
      int holderPid = backendPid(holder);
      Future<HttpResponse<String>> first =
          workers.submit(() -> post(fixture, key, fixture.choiceIds()));
      WaitEvidence userWait = awaitWaitFor(holderPid, Set.of(), first);
      recordLockEvidence("c01-user-wait", List.of(userWait));
      assertThat(userWait.query()).containsIgnoringCase("users");

      Future<HttpResponse<String>> second =
          workers.submit(() -> post(fixture, key, fixture.choiceIds().reversed()));
      WaitEvidence claimWait =
          awaitWaitFor(userWait.waiterPid(), Set.of(userWait.waiterPid()), second);
      assertThat(claimWait.waiterPid()).isNotEqualTo(userWait.waiterPid());
      recordLockEvidence("c01-claim-wait", List.of(claimWait));
      assertThat(claimWait.blockerPids()).contains(String.valueOf(userWait.waiterPid()));
      assertThat(claimWait.query()).containsIgnoringCase("publication_idempotency");

      holder.commit();
      assertThat(first.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
      assertThat(second.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }

    assertThat(gameCount(fixture.roomId())).isEqualTo(2L);
    assertThat(choiceCount(fixture.roomId())).isEqualTo(8L);
    assertThat(gameExists(fixture.existingGameId())).isTrue();
    assertThat(existingGameSnapshot(fixture.existingGameId())).isEqualTo(existingGameBefore);
    assertThat(recordCount(fixture.user().getId())).isEqualTo(1L);
  }

  @Test
  @DisplayName("C-02: 다른 키의 같은 질문은 Room 대기와 User 대기를 거쳐 하나만 추가한다")
  void serializesDifferentKeysAtRoomAndUserLocks() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    String originalRoom = roomSnapshot(fixture.roomId());
    String existingGameBefore = existingGameSnapshot(fixture.existingGameId());
    try (Connection holder = lockRoom(fixture.roomId());
        ExecutorService workers = Executors.newFixedThreadPool(2)) {
      int holderPid = backendPid(holder);
      Future<HttpResponse<String>> first =
          workers.submit(() -> post(fixture, UUID.randomUUID().toString(), fixture.choiceIds()));
      WaitEvidence roomWait = awaitWaitFor(holderPid, Set.of(), first);
      recordLockEvidence("c02-room-wait", List.of(roomWait));
      assertThat(roomWait.query()).containsIgnoringCase("rooms");

      Future<HttpResponse<String>> second =
          workers.submit(
              () -> post(fixture, UUID.randomUUID().toString(), fixture.choiceIds().reversed()));
      WaitEvidence userWait =
          awaitWaitFor(roomWait.waiterPid(), Set.of(roomWait.waiterPid()), second);
      recordLockEvidence("c02-user-wait", List.of(userWait));
      assertThat(userWait.waiterPid()).isNotEqualTo(roomWait.waiterPid());
      assertThat(userWait.blockerPids()).contains(String.valueOf(roomWait.waiterPid()));
      assertThat(userWait.query()).containsIgnoringCase("users");

      holder.commit();
      assertThat(first.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
      HttpResponse<String> duplicateQuestion = second.get(15, TimeUnit.SECONDS);
      assertThat(duplicateQuestion.statusCode()).isEqualTo(409);
      assertThat(json.readTree(duplicateQuestion.body()).required("code").textValue())
          .isEqualTo("QUESTION_ALREADY_PUBLISHED");
    }

    assertThat(gameCount(fixture.roomId())).isEqualTo(2L);
    assertThat(choiceCount(fixture.roomId())).isEqualTo(8L);
    assertThat(gameExists(fixture.existingGameId())).isTrue();
    assertThat(existingGameSnapshot(fixture.existingGameId())).isEqualTo(existingGameBefore);
    assertThat(completedRecordCount(fixture.user().getId())).isEqualTo(1L);
    assertThat(roomSnapshot(fixture.roomId())).isEqualTo(originalRoom);
  }

  @Test
  @DisplayName("C-03: 실제 Room 잠금 대기 중 DB 마감 시각을 넘으면 새 추가를 거절한다")
  void rejectsNewKickWhenActualRoomLockWaitCrossesDeadline() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    Instant closesAt = Instant.parse("2030-07-05T10:45:00Z");
    jdbc.sql("update rooms set opened_at = :openedAt, closes_at = :closesAt where id = :roomId")
        .param("openedAt", closesAt.minus(Duration.ofHours(24)).atOffset(ZoneOffset.UTC))
        .param("closesAt", closesAt.atOffset(ZoneOffset.UTC))
        .param("roomId", fixture.roomId())
        .update();
    willReturn(closesAt.minusNanos(1_000)).given(clock).readCurrentTime();
    String before = roomSnapshot(fixture.roomId());
    String domainBefore = domainSnapshot(fixture.roomId(), fixture.user().getId());
    long gamesBefore = gameCount(fixture.roomId());
    long choicesBefore = choiceCount(fixture.roomId());
    long recordsBefore = recordCount(fixture.user().getId());

    try (Connection holder = lockRoom(fixture.roomId());
        ExecutorService workers = Executors.newSingleThreadExecutor()) {
      int holderPid = backendPid(holder);
      Future<HttpResponse<String>> request =
          workers.submit(() -> post(fixture, UUID.randomUUID().toString(), fixture.choiceIds()));
      WaitEvidence roomWait = awaitWaitFor(holderPid, Set.of(), request);
      recordLockEvidence("c03-deadline", List.of(roomWait));
      assertThat(roomWait.query()).containsIgnoringCase("rooms");
      willReturn(closesAt).given(clock).readCurrentTime();
      holder.commit();

      HttpResponse<String> response = request.get(15, TimeUnit.SECONDS);
      assertThat(response.statusCode()).isEqualTo(409);
      assertThat(json.readTree(response.body()).required("code").textValue())
          .isEqualTo("ROOM_INTAKE_CLOSED");
    }

    assertThat(roomSnapshot(fixture.roomId())).isEqualTo(before);
    assertThat(domainSnapshot(fixture.roomId(), fixture.user().getId())).isEqualTo(domainBefore);
    assertThat(gameCount(fixture.roomId())).isEqualTo(gamesBefore);
    assertThat(choiceCount(fixture.roomId())).isEqualTo(choicesBefore);
    assertThat(recordCount(fixture.user().getId())).isEqualTo(recordsBefore);
  }

  @ParameterizedTest(name = "{0}: {4}")
  @CsvSource({
    "users, status, update users set status = 'SUSPENDED' where id = ?, 403, ACCOUNT_INACTIVE",
    "rooms, result_state, update rooms set result_state = 'FINAL' where id = ?, 409, ROOM_INTAKE_CLOSED"
  })
  @DisplayName("행 잠금 쿼리 한 번으로 대기 중 커밋된 최신 상태를 확인한다")
  void readsUpdatedStateInTheLockingQuery(
      String table, String statusColumn, String updateSql, int statusCode, String errorCode)
      throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    boolean userRow = table.equals("users");
    long rowId = userRow ? fixture.user().getId() : fixture.roomId();
    long recordsBefore = recordCount(fixture.user().getId());
    long gamesBefore = gameCount(fixture.roomId());

    try (ExecutorService workers = Executors.newSingleThreadExecutor();
        Connection holder = userRow ? lockUser(rowId) : lockRoom(rowId)) {
      int holderPid = backendPid(holder);
      Future<HttpResponse<String>> request =
          workers.submit(() -> post(fixture, UUID.randomUUID().toString(), fixture.choiceIds()));
      WaitEvidence wait = awaitWaitFor(holderPid, Set.of(), request);
      try (var statement = holder.prepareStatement(updateSql)) {
        statement.setLong(1, rowId);
        assertThat(statement.executeUpdate()).isEqualTo(1);
      }
      holder.commit();

      HttpResponse<String> response = request.get(15, TimeUnit.SECONDS);
      assertThat(response.statusCode()).isEqualTo(statusCode);
      assertThat(json.readTree(response.body()).required("code").textValue()).isEqualTo(errorCode);
      assertThat(recordCount(fixture.user().getId())).isEqualTo(recordsBefore);
      assertThat(gameCount(fixture.roomId())).isEqualTo(gamesBefore);
      assertThat(wait.query()).containsIgnoringCase(table).containsIgnoringCase(statusColumn);
    }
  }

  private AddKickHttpFixture.Fixture fixture() throws Exception {
    return new AddKickHttpFixture(port, json, jdbc, users, logins).fixture();
  }

  private HttpResponse<String> post(
      AddKickHttpFixture.Fixture fixture, String key, List<Long> choiceIds) throws Exception {
    return new AddKickHttpFixture(port, json, jdbc, users, logins)
        .post(fixture, key, choiceIds, fixture.choiceIds().getFirst());
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

  private WaitEvidence awaitWaitFor(int blockerPid, Set<Integer> excluded, Future<?> request)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (request.isDone()) {
        throw new AssertionError("request completed before the intended PostgreSQL lock wait");
      }
      List<WaitEvidence> waiters =
          jdbc.sql(
                  """
                  select pid, wait_event_type, pg_blocking_pids(pid)::text as blockers, query
                  from pg_stat_activity
                  where wait_event_type = 'Lock'
                    and :blockerPid = any(pg_blocking_pids(pid))
                  order by query_start desc
                  """)
              .param("blockerPid", blockerPid)
              .query((resultSet, rowNum) ->
                  new WaitEvidence(
                      resultSet.getInt("pid"),
                      resultSet.getString("wait_event_type"),
                      resultSet.getString("blockers"),
                      resultSet.getString("query")))
              .list();
      for (WaitEvidence waiter : waiters) {
        if (!excluded.contains(waiter.waiterPid())) {
          return waiter;
        }
      }
      Thread.sleep(20);
    }
    throw new AssertionError("no PostgreSQL lock waiter blocked by pid=" + blockerPid);
  }

  private boolean gameExists(long gameId) {
    return jdbc.sql("select exists(select 1 from games where id = :gameId)")
        .param("gameId", gameId)
        .query(Boolean.class)
        .single();
  }

  private String existingGameSnapshot(long gameId) {
    return jdbc.sql(
            "select jsonb_build_object("
                + "'game', (select to_jsonb(g) from games g where g.id = :gameId), "
                + "'choices', (select coalesce(jsonb_agg(to_jsonb(c) order by c.id), '[]'::jsonb) "
                + "from choices c where c.game_id = :gameId))::text")
        .param("gameId", gameId)
        .query(String.class)
        .single();
  }

  private String domainSnapshot(long roomId, long userId) {
    return jdbc.sql(
            "select jsonb_build_object("
                + "'room', (select to_jsonb(r) from rooms r where r.id = :roomId), "
                + "'games', (select coalesce(jsonb_agg(to_jsonb(g) order by g.id), '[]'::jsonb) "
                + "from games g where g.room_id = :roomId), "
                + "'choices', (select coalesce(jsonb_agg(to_jsonb(c) order by c.id), '[]'::jsonb) "
                + "from choices c join games g on g.id = c.game_id where g.room_id = :roomId), "
                + "'records', (select coalesce(jsonb_agg(to_jsonb(p) order by p.user_id, p.request_key), '[]'::jsonb) "
                + "from publication_idempotency p where p.user_id = :userId))::text")
        .param("roomId", roomId)
        .param("userId", userId)
        .query(String.class)
        .single();
  }

  private long gameCount(long roomId) {
    return jdbc.sql("select count(*) from games where room_id = :roomId")
        .param("roomId", roomId)
        .query(Long.class)
        .single();
  }

  private long choiceCount(long roomId) {
    return jdbc.sql(
            "select count(*) from choices c join games g on g.id = c.game_id where g.room_id = :roomId")
        .param("roomId", roomId)
        .query(Long.class)
        .single();
  }

  private long recordCount(long userId) {
    return jdbc.sql("select count(*) from publication_idempotency where user_id = :userId")
        .param("userId", userId)
        .query(Long.class)
        .single();
  }

  private long completedRecordCount(long userId) {
    return jdbc.sql(
            "select count(*) from publication_idempotency where user_id = :userId and game_id is not null")
        .param("userId", userId)
        .query(Long.class)
        .single();
  }

  private String roomSnapshot(long roomId) {
    return jdbc.sql(
            "select status || '|' || result_state || '|' || selected_reward_preset_id || '|' || opened_at || '|' || closes_at from rooms where id = :roomId")
        .param("roomId", roomId)
        .query(String.class)
        .single();
  }

  private void recordLockEvidence(String name, List<WaitEvidence> evidence) throws Exception {
    String directory = System.getenv("NK76_LOCK_EVIDENCE_DIR");
    if (directory == null || directory.isBlank()) {
      return;
    }
    Path output = Path.of(directory);
    Files.createDirectories(output);
    String text =
        evidence.stream()
            .map(event ->
                "waiterPid="
                    + event.waiterPid()
                    + " blockerPids="
                    + event.blockerPids()
                    + " waitEventType="
                    + event.waitEventType()
                    + " query="
                    + event.query().replaceAll("\\s+", " "))
            .collect(Collectors.joining(System.lineSeparator()));
    Files.writeString(
        output.resolve(name + ".txt"), text + System.lineSeparator(), StandardCharsets.UTF_8);
  }

  private static int backendPid(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("select pg_backend_pid()")) {
      assertThat(result.next()).isTrue();
      return result.getInt(1);
    }
  }

  private static String jdbcUrl() {
    return AddKickHttpFixture.withCurrentSchema(
        PostgresTestDatabase.container().getJdbcUrl(), "nk76_http_concurrency");
  }

  private record WaitEvidence(
      int waiterPid, String waitEventType, String blockerPids, String query) {}
}

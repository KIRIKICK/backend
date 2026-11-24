package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.willReturn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.SignupApplication;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.common.infra.DbTimeSource;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Actual HTTP, security and PostgreSQL boundary for NkCreate; fault injection is deliberately
 * separate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(OpenRoomHttpPostgresTest.DomainEntities.class)
class OpenRoomHttpPostgresTest {

  @LocalServerPort private int port;

  @Autowired private ObjectMapper json;

  @Autowired private JdbcClient jdbc;

  @Autowired private UserRepository users;

  @Autowired private LoginSessionService logins;
  @MockitoSpyBean private DbTimeSource time;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    registry.add("spring.datasource.url", OpenRoomHttpPostgresTest::nk75JdbcUrl);
    registry.add("spring.datasource.username", PostgresTestDatabase.container()::getUsername);
    registry.add("spring.datasource.password", PostgresTestDatabase.container()::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.default-schema", () -> "nk75_http");
    registry.add("publication.grace", () -> "PT5M");
  }

  @BeforeEach
  void requirePostgres() {
    assertThat(jdbc.sql("select version()").query(String.class).single()).contains("PostgreSQL 17");
    assertThat(jdbc.sql("select current_schema()").query(String.class).single())
        .isEqualTo("nk75_http");
    assertThat(
            jdbc.sql(
                    """
                    select version
                    from flyway_schema_history
                    where success and type = 'SQL'
                    order by installed_rank
                    """)
                .query(String.class)
                .list())
        .containsExactly("1", "2", "3", "4", "5", "6");
  }

  @Test
  @DisplayName("실제 POST는 MVP 프리셋의 토큰 원문과 원본 보기 ID 순서를 스냅샷으로 보존한다")
  void snapshotsSeededPresetTextWithoutDisplaySubstitution() throws Exception {
    NkCreateHttpFixture.Fixture caller = fixture().fixture();
    String questionText = "{name}이(가) 주말에 제일 많이 하는 일은?";
    long questionId =
        jdbc.sql("select id from question_presets where question_text = ?")
            .param(questionText)
            .query(Long.class)
            .single();
    var choiceIds =
        jdbc.sql("select id from question_preset_choices where question_preset_id = ? order by id")
            .param(questionId)
            .query(Long.class)
            .list();
    long rewardId =
        jdbc.sql("select id from reward_presets where title = '리액션 한가득'")
            .query(Long.class)
            .single();
    var seeded =
        new NkCreateHttpFixture.Fixture(
            caller.user(),
            caller.grant(),
            rewardId,
            caller.alternateRewardId(),
            questionId,
            choiceIds);
    String key = UUID.randomUUID().toString();
    HttpResponse<String> created =
        fixture().post(seeded, key, choiceIds.reversed(), choiceIds.get(1), rewardId);
    assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
    JsonNode result = json.readTree(created.body());
    long gameId = Long.parseLong(result.required("gameId").textValue());
    assertThat(
            jdbc.sql("select question_text_snapshot from games where id = ?")
                .param(gameId)
                .query(String.class)
                .single())
        .isEqualTo(questionText);
    assertThat(
            jdbc.sql("select text_snapshot from choices where game_id = ? order by position")
                .param(gameId)
                .query(String.class)
                .list())
        .containsExactly("넷플릭스 정주행", "카페에서 수다", "하루 종일 자기", "즉흥 여행");
    assertThat(
            jdbc.sql(
                    "select c.text_snapshot from games g join choices c on c.game_id = g.id and c.id = g.correct_choice_id where g.id = ?")
                .param(gameId)
                .query(String.class)
                .single())
        .isEqualTo("카페에서 수다");
    HttpResponse<String> replay =
        fixture().post(seeded, key, choiceIds, choiceIds.get(1), rewardId);
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(json.readTree(replay.body())).isEqualTo(result);
  }

  @Test
  @DisplayName("실제 POST는 PostgreSQL에 한 번 공개하고 보기 순서만 달라도 원래 결과를 복구한다")
  void postCreatesAndReplaysCanonicalInputAgainstPostgres() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    String key = "1b541b3d-498b-4a79-894c-e0d2cc8423ee";

    HttpResponse<String> created =
        fixture()
            .post(
                fixture,
                key,
                fixture.choiceIds().reversed(),
                fixture.choiceIds().get(1),
                fixture.rewardId());
    assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
    JsonNode first = json.readTree(created.body());
    assertThat(first.required("roomId").textValue()).matches("[1-9][0-9]*");
    assertThat(first.required("gameId").textValue()).matches("[1-9][0-9]*");
    assertThat(first.required("openedAt").textValue()).endsWith("Z");
    assertThat(first.required("closesAt").textValue()).endsWith("Z");

    HttpResponse<String> replay =
        fixture()
            .post(
                fixture, key, fixture.choiceIds(), fixture.choiceIds().get(1), fixture.rewardId());
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(json.readTree(replay.body())).isEqualTo(first);

    long roomId = Long.parseLong(first.required("roomId").textValue());
    long gameId = Long.parseLong(first.required("gameId").textValue());
    assertThat(
            jdbc.sql("select count(*) from rooms where id = :roomId and host_user_id = :userId")
                .param("roomId", roomId)
                .param("userId", fixture.user().getId())
                .query(Long.class)
                .single())
        .isEqualTo(1L);
    assertThat(
            jdbc.sql("select count(*) from games where id = :gameId and room_id = :roomId")
                .param("gameId", gameId)
                .param("roomId", roomId)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
    assertThat(
            jdbc.sql("select count(*) from choices where game_id = :gameId")
                .param("gameId", gameId)
                .query(Long.class)
                .single())
        .isEqualTo(4L);
    assertThat(
            jdbc.sql(
                    "select count(*) from publication_idempotency where user_id = :userId and request_key = :key and game_id = :gameId")
                .param("userId", fixture.user().getId())
                .param("key", key)
                .param("gameId", gameId)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("실제 POST는 같은 키의 다른 의미 입력을 충돌로 돌려주고 기존 공개를 보존한다")
  void postRejectsDifferentMeaningForSameKeyWithoutAnotherGame() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    String key = "1b541b3d-498b-4a79-894c-e0d2cc8423ef";
    assertThat(
            fixture()
                .post(
                    fixture,
                    key,
                    fixture.choiceIds(),
                    fixture.choiceIds().getFirst(),
                    fixture.rewardId())
                .statusCode())
        .isEqualTo(201);

    HttpResponse<String> conflict =
        fixture()
            .post(
                fixture,
                key,
                fixture.choiceIds(),
                fixture.choiceIds().getFirst(),
                fixture.alternateRewardId());
    assertThat(conflict.statusCode()).isEqualTo(409);
    assertThat(json.readTree(conflict.body()).required("code").textValue())
        .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
    assertThat(
            jdbc.sql(
                    "select count(*) from games g join rooms r on r.id = g.room_id where r.host_user_id = :userId")
                .param("userId", fixture.user().getId())
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("실제 POST는 접수 중인 기존 방이 있으면 신규 공개를 거절하고 멱등 기록을 남기지 않는다")
  void postRejectsNewPublicationWhenAnOpenRoomExists() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    insertRoom(fixture, "OPEN", "now() + interval '1 hour'");

    HttpResponse<String> response =
        fixture()
            .post(
                fixture,
                "1b541b3d-498b-4a79-894c-e0d2cc8423f1",
                fixture.choiceIds(),
                fixture.choiceIds().getFirst(),
                fixture.rewardId());

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo("OPEN_ROOM_EXISTS");
    assertThat(
            jdbc.sql("select count(*) from publication_idempotency where user_id = :userId")
                .param("userId", fixture.user().getId())
                .query(Long.class)
                .single())
        .isZero();
  }

  @ParameterizedTest(name = "고정 현재 시각 대비 마감={0}µs")
  @ValueSource(longs = {-1, 0, 1})
  @DisplayName("실제 POST는 고정 시각의 마감 경계부터 OPEN 방을 닫고 새 방을 공개한다")
  void postClosesExpiredOpenRoomBeforePublishingANewRoom(long offsetMicros) throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    Clock clock = fixedClock();
    long expiredRoomId =
        insertRoom(fixture, "OPEN", clock.instant().plusNanos(offsetMicros * 1_000));
    PublicationCounts before = publicationCounts(fixture.user().getId());

    HttpResponse<String> response =
        fixture()
            .post(
                fixture,
                "1b541b3d-498b-4a79-894c-e0d2cc8423f2",
                fixture.choiceIds(),
                fixture.choiceIds().getFirst(),
                fixture.rewardId());

    if (offsetMicros > 0) {
      assertThat(response.statusCode()).isEqualTo(409);
      assertThat(json.readTree(response.body()).required("code").textValue())
          .isEqualTo("OPEN_ROOM_EXISTS");
      assertThat(publicationCounts(fixture.user().getId())).isEqualTo(before);
      return;
    }
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
    long newRoomId = Long.parseLong(json.readTree(response.body()).required("roomId").textValue());
    assertThat(newRoomId).isNotEqualTo(expiredRoomId);
    assertThat(
            jdbc.sql("select status from rooms where id = :roomId")
                .param("roomId", expiredRoomId)
                .query(String.class)
                .single())
        .isEqualTo("CLOSED");
    assertThat(
            jdbc.sql("select count(*) from games where room_id = :roomId")
                .param("roomId", newRoomId)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @ParameterizedTest(name = "고정 현재 시각 대비 기록 만료={0}µs")
  @ValueSource(longs = {-1, 0, 1})
  @DisplayName("실제 POST는 고정 시각의 기록 만료 경계부터 세대를 교체하고 기존 Game을 보존한다")
  void postReplacesExpiredSuccessRecordWithoutDeletingItsExistingGame(long offsetMicros)
      throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    Clock clock = fixedClock();
    String key = "1b541b3d-498b-4a79-894c-e0d2cc8423f3";
    HttpResponse<String> first =
        fixture()
            .post(
                fixture,
                key,
                fixture.choiceIds(),
                fixture.choiceIds().getFirst(),
                fixture.rewardId());
    assertThat(first.statusCode()).withFailMessage(first.body()).isEqualTo(201);
    long priorRoomId = Long.parseLong(json.readTree(first.body()).required("roomId").textValue());
    long priorGameId = Long.parseLong(json.readTree(first.body()).required("gameId").textValue());
    jdbc.sql("update rooms set status = 'CLOSED' where id = :roomId")
        .param("roomId", priorRoomId)
        .update();
    jdbc.sql(
            "update publication_idempotency set record_expires_at = :expiresAt "
                + "where user_id = :userId and request_key = :key")
        .param(
            "expiresAt", clock.instant().plusNanos(offsetMicros * 1_000).atOffset(ZoneOffset.UTC))
        .param("userId", fixture.user().getId())
        .param("key", key)
        .update();

    PublicationCounts before = publicationCounts(fixture.user().getId());
    HttpResponse<String> replacement =
        fixture()
            .post(
                fixture,
                key,
                fixture.choiceIds(),
                fixture.choiceIds().getFirst(),
                fixture.alternateRewardId());

    if (offsetMicros > 0) {
      assertThat(replacement.statusCode()).isEqualTo(409);
      assertThat(json.readTree(replacement.body()).required("code").textValue())
          .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
      assertThat(publicationCounts(fixture.user().getId())).isEqualTo(before);
      return;
    }
    assertThat(replacement.statusCode()).withFailMessage(replacement.body()).isEqualTo(201);
    long replacementGameId =
        Long.parseLong(json.readTree(replacement.body()).required("gameId").textValue());
    assertThat(replacementGameId).isNotEqualTo(priorGameId);
    assertThat(
            jdbc.sql("select count(*) from games where id = :gameId")
                .param("gameId", priorGameId)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
    assertThat(
            jdbc.sql(
                    "select game_id from publication_idempotency "
                        + "where user_id = :userId and request_key = :key")
                .param("userId", fixture.user().getId())
                .param("key", key)
                .query(Long.class)
                .single())
        .isEqualTo(replacementGameId);
  }

  @Test
  @DisplayName("실제 POST는 인증 또는 요청 키가 없으면 공개 전에 거절한다")
  void postRejectsUnauthorizedAndMissingIdempotencyKeyBeforePublication() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    HttpResponse<String> unauthenticated = fixture().postRaw(fixture, null, false);
    assertThat(unauthenticated.statusCode()).isEqualTo(401);

    HttpResponse<String> missingKey = fixture().postRaw(fixture, null, true);
    assertThat(missingKey.statusCode()).isEqualTo(400);
    assertThat(json.readTree(missingKey.body()).required("code").textValue())
        .isEqualTo("INVALID_PUBLICATION_REQUEST_KEY");
    assertThat(
            jdbc.sql("select count(*) from rooms where host_user_id = :userId")
                .param("userId", fixture.user().getId())
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  @DisplayName("실제 POST는 canonical lowercase v4가 아닌 요청 키를 공개 전에 거절한다")
  void postRejectsNonCanonicalIdempotencyKeyBeforePublication() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();

    HttpResponse<String> response =
        fixture().postRaw(fixture, "1B541B3D-498B-4A79-894C-E0D2CC8423EE", true);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo("INVALID_PUBLICATION_REQUEST_KEY");
    assertThat(
            jdbc.sql("select count(*) from rooms where host_user_id = :userId")
                .param("userId", fixture.user().getId())
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  @DisplayName("실제 POST는 없는 질문 프리셋을 404로 돌려주고 멱등 기록도 남기지 않는다")
  void postRejectsMissingQuestionPresetWithoutPublicationRecord() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    NkCreateHttpFixture.Fixture missingQuestion =
        new NkCreateHttpFixture.Fixture(
            fixture.user(),
            fixture.grant(),
            fixture.rewardId(),
            fixture.alternateRewardId(),
            Long.MAX_VALUE,
            fixture.choiceIds());
    String key = "1b541b3d-498b-4a79-894c-e0d2cc8423f0";

    PublicationCounts before = publicationCounts(fixture.user().getId());
    HttpResponse<String> response =
        fixture()
            .post(
                missingQuestion,
                key,
                missingQuestion.choiceIds(),
                missingQuestion.choiceIds().getFirst(),
                missingQuestion.rewardId());

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo("PRESET_NOT_FOUND");
    assertThat(publicationCounts(fixture.user().getId())).isEqualTo(before);
  }

  @Test
  @DisplayName("명시적 Grace 등록 상태에서 출제 요청 키 CORS preflight를 허용한다")
  void corsPreflightAllowsPublicationRequestKeyOnlyWhenPublicationIsRegistered() throws Exception {
    NkCreateHttpFixture requests = fixture();

    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(requests.backend("/games"))
                    .header("Origin", "http://localhost:5173")
                    .header("Access-Control-Request-Method", "POST")
                    .header(
                        "Access-Control-Request-Headers",
                        "Content-Type, X-XSRF-TOKEN, Authorization, Idempotency-Key")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow())
        .isEqualTo("http://localhost:5173");
    assertThat(response.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow())
        .isEqualTo("true");
    assertThat(response.headers().firstValue("Access-Control-Allow-Methods").orElseThrow())
        .containsIgnoringCase("POST");
    assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElseThrow())
        .containsIgnoringCase("Idempotency-Key");
  }

  private Clock fixedClock() {
    Clock clock = Clock.fixed(Instant.parse("2040-01-01T00:00:00Z"), ZoneOffset.UTC);
    willReturn(clock.instant()).given(time).readCurrentTime();
    return clock;
  }

  private PublicationCounts publicationCounts(long userId) {
    long rooms =
        jdbc.sql("select count(*) from rooms where host_user_id = :userId")
            .param("userId", userId)
            .query(Long.class)
            .single();
    long games =
        jdbc.sql(
                "select count(*) from games g join rooms r on r.id = g.room_id where r.host_user_id = :userId")
            .param("userId", userId)
            .query(Long.class)
            .single();
    long choices =
        jdbc.sql(
                "select count(*) from choices c join games g on g.id = c.game_id join rooms r on r.id = g.room_id where r.host_user_id = :userId")
            .param("userId", userId)
            .query(Long.class)
            .single();
    long records =
        jdbc.sql("select count(*) from publication_idempotency where user_id = :userId")
            .param("userId", userId)
            .query(Long.class)
            .single();
    return new PublicationCounts(rooms, games, choices, records);
  }

  private record PublicationCounts(long rooms, long games, long choices, long records) {}

  private NkCreateHttpFixture fixture() {
    return new NkCreateHttpFixture(port, json, jdbc, users, logins);
  }

  private long insertRoom(NkCreateHttpFixture.Fixture fixture, String status, Instant closesAt) {
    return jdbc.sql(
            """
            insert into rooms(host_user_id, selected_reward_preset_id, status, result_state, opened_at, closes_at, created_at)
            values (:userId, :rewardId, :status, 'NOT_READY', :openedAt, :closesAt, now()) returning id
            """)
        .param("userId", fixture.user().getId())
        .param("rewardId", fixture.rewardId())
        .param("status", status)
        .param("openedAt", closesAt.minus(Duration.ofHours(24)).atOffset(ZoneOffset.UTC))
        .param("closesAt", closesAt.atOffset(ZoneOffset.UTC))
        .query(Long.class)
        .single();
  }

  private long insertRoom(
      NkCreateHttpFixture.Fixture fixture, String status, String closesAtExpression) {
    return jdbc.sql(
            """
            insert into rooms(host_user_id, selected_reward_preset_id, status, result_state, opened_at, closes_at, created_at)
            values (:userId, :rewardId, :status, 'NOT_READY', now() - interval '1 hour', %s, now())
            returning id
            """
                .formatted(closesAtExpression))
        .param("userId", fixture.user().getId())
        .param("rewardId", fixture.rewardId())
        .param("status", status)
        .query(Long.class)
        .single();
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
        PostgresTestDatabase.container().getJdbcUrl(), "nk75_http");
  }
}

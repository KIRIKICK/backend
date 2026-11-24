package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.SignupApplication;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.game.domain.Choice;
import org.kirikick.api.game.domain.Game;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.kirikick.api.publication.service.OpenRoomService;
import org.kirikick.api.question.domain.QuestionPreset;
import org.kirikick.api.question.domain.QuestionPresetChoice;
import org.kirikick.api.reward.domain.RewardPreset;
import org.kirikick.api.room.domain.Room;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.domain.UserStatus;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Actual HTTP, security and PostgreSQL boundary for NkCreate; fault injection is deliberately
 * separate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"local", "test"})
@Import(OpenRoomHttpSecurityPostgresTest.DomainEntities.class)
class OpenRoomHttpSecurityPostgresTest {

  @LocalServerPort private int port;

  @Autowired private ObjectMapper json;

  @Autowired private JdbcClient jdbc;

  @MockitoSpyBean private UserRepository users;

  @MockitoSpyBean private OpenRoomService publications;

  @Autowired private LoginSessionService logins;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    registry.add("spring.datasource.url", OpenRoomHttpSecurityPostgresTest::nk75JdbcUrl);
    registry.add("spring.datasource.username", PostgresTestDatabase.container()::getUsername);
    registry.add("spring.datasource.password", PostgresTestDatabase.container()::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.default-schema", () -> "nk75_http_security");
    registry.add("publication.grace", () -> "PT5M");
  }

  @BeforeEach
  void requirePostgres() {
    assertThat(jdbc.sql("select version()").query(String.class).single()).contains("PostgreSQL 17");
    assertThat(jdbc.sql("select current_schema()").query(String.class).single())
        .isEqualTo("nk75_http_security");
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
  @DisplayName("출제 요청 키는 games CORS preflight에만 허용한다")
  void corsPreflightDoesNotAllowPublicationKeyForOtherPaths() throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/auth/csrf"))
                    .header("Origin", "http://localhost:5173")
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "Idempotency-Key")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(403);
  }

  @Test
  @DisplayName("허용되지 않은 Origin은 출제 CORS 사전 요청에서 거절된다")
  void corsPreflightRejectsUntrustedOriginForGames() throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/games"))
                    .header("Origin", "https://untrusted.invalid")
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "Idempotency-Key")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
  }

  @Test
  @DisplayName("출제 요청은 인증과 CSRF 쿠키 및 헤더를 모두 요구한다")
  void publicationRequiresAuthenticationAndMatchingCsrf() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    String body = validBody(fixture);
    Csrf csrf = csrf();
    PublicationRows before = publicationRows(fixture);

    HttpResponse<String> missingCsrf =
        post(
            body,
            fixture.grant().accessToken(),
            "1b541b3d-498b-4a79-894c-e0d2cc842301",
            null,
            null);
    assertThat(missingCsrf.statusCode()).isEqualTo(403);
    assertThat(publicationRows(fixture)).isEqualTo(before);
    then(publications).shouldHaveNoInteractions();

    HttpResponse<String> mismatchedCsrf =
        post(
            body,
            fixture.grant().accessToken(),
            "1b541b3d-498b-4a79-894c-e0d2cc842302",
            csrf.cookie(),
            "not-the-csrf-token");
    assertThat(mismatchedCsrf.statusCode()).isEqualTo(403);
    assertThat(publicationRows(fixture)).isEqualTo(before);
    then(publications).shouldHaveNoInteractions();

    HttpResponse<String> cookieOnly =
        post(body, null, "1b541b3d-498b-4a79-894c-e0d2cc842303", csrf.cookie(), csrf.token());
    assertThat(cookieOnly.statusCode()).isEqualTo(401);
    assertThat(publicationRows(fixture)).isEqualTo(before);
    then(publications).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("비활성 회원은 출제 인증 필터에서 ACCOUNT_INACTIVE로 거절된다")
  void publicationRejectsSuspendedAccountBeforeTheController() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    ReflectionTestUtils.setField(fixture.user(), "status", UserStatus.SUSPENDED);
    users.saveAndFlush(fixture.user());
    Csrf csrf = csrf();

    HttpResponse<String> response =
        post(
            validBody(fixture),
            fixture.grant().accessToken(),
            "1b541b3d-498b-4a79-894c-e0d2cc842304",
            csrf.cookie(),
            csrf.token());

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo("ACCOUNT_INACTIVE");
    then(publications).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("인증 회원 조회 장애는 출제 컨트롤러 전에 AUTH_STATE_UNAVAILABLE로 반환된다")
  void publicationReturnsAuthenticationBackendFailureBeforeTheController() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    Csrf csrf = csrf();
    doThrow(new QueryTimeoutException("member lookup unavailable"))
        .when(users)
        .findByUuid(fixture.user().getUuid());
    try {
      HttpResponse<String> response =
          post(
              validBody(fixture),
              fixture.grant().accessToken(),
              "1b541b3d-498b-4a79-894c-e0d2cc842305",
              csrf.cookie(),
              csrf.token());

      assertThat(response.statusCode()).isEqualTo(503);
      assertThat(json.readTree(response.body()).required("code").textValue())
          .isEqualTo("AUTH_STATE_UNAVAILABLE");
      then(publications).shouldHaveNoInteractions();
    } finally {
      reset(users);
    }
  }

  @Test
  @DisplayName("출제 OpenAPI는 요청 키와 성공 및 오류 응답 계약을 공개한다")
  void documentsOpenRoomContract() throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode document = json.readTree(response.body());
    JsonNode operation = document.at("/paths/~1games/post");
    assertThat(operation.path("operationId").asText()).isEqualTo("publish");
    assertThat(operation.path("summary").asText()).isEqualTo("방 열기(첫 킥 공개)");
    assertThat(operation.isMissingNode()).isFalse();
    assertThat(operation.path("parameters").findValuesAsText("name"))
        .containsExactly("Idempotency-Key");
    assertThat(operation.at("/parameters/0/in").asText()).isEqualTo("header");
    assertThat(operation.at("/parameters/0/required").asBoolean()).isTrue();
    assertThat(operation.at("/requestBody/content/application~1json/schema/$ref").asText())
        .isEqualTo("#/components/schemas/NkCreateRequest");

    assertThat(
            document
                .at("/components/schemas/NkCreateRequest/properties/questionPresetId/type")
                .asText())
        .isEqualTo("string");
    assertThat(document.at("/components/schemas/NkCreateResponse/properties/roomId/type").asText())
        .isEqualTo("string");
    assertThat(
            document.at("/components/schemas/NkCreateResponse/properties/openedAt/format").asText())
        .isEqualTo("date-time");
    assertThat(
            document.at("/components/schemas/NkCreateResponse/properties/closesAt/format").asText())
        .isEqualTo("date-time");

    for (String status : List.of("201", "200")) {
      assertThat(
              operation
                  .at("/responses/" + status + "/content/application~1json/schema/$ref")
                  .asText())
          .isEqualTo("#/components/schemas/NkCreateResponse");
      assertThat(operation.at("/responses/" + status + "/headers/Location").isMissingNode())
          .isTrue();
    }
    for (String status : List.of("400", "404", "409", "500", "503")) {
      assertThat(
              operation
                  .at("/responses/" + status + "/content/application~1json/schema/$ref")
                  .asText())
          .isEqualTo("#/components/schemas/ErrorResponse");
      assertThat(operation.at("/responses/" + status + "/headers/Retry-After").isMissingNode())
          .isTrue();
    }
    assertErrorExample(
        document,
        "400",
        "INVALID_PUBLICATION_REQUEST_KEY",
        PublicationErrorCode.INVALID_PUBLICATION_REQUEST_KEY.getMessage());
    assertErrorExample(
        document,
        "400",
        "INVALID_PUBLICATION_INPUT",
        PublicationErrorCode.INVALID_PUBLICATION_INPUT.getMessage());
    assertErrorExample(
        document, "404", "PRESET_NOT_FOUND", PublicationErrorCode.PRESET_NOT_FOUND.getMessage());
    assertErrorExample(
        document, "409", "IDEMPOTENCY_KEY_CONFLICT", "같은 요청 키에 다른 출제 입력을 사용할 수 없습니다.");
    assertErrorExample(
        document, "409", "OPEN_ROOM_EXISTS", PublicationErrorCode.OPEN_ROOM_EXISTS.getMessage());
    assertErrorExample(document, "500", "INTERNAL_ERROR", "요청을 처리하는 중 오류가 발생했습니다.");
    assertErrorExample(
        document,
        "500",
        "PUBLICATION_RECORD_UNAVAILABLE",
        PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD.getMessage());
    assertErrorExample(document, "503", "PUBLICATION_UNAVAILABLE", "출제 결과를 확인할 수 없습니다.");
  }

  private static void assertErrorExample(
      JsonNode document, String status, String code, String message) {
    JsonNode value =
        document.at(
            "/paths/~1games/post/responses/"
                + status
                + "/content/application~1json/examples/"
                + code
                + "/value");
    assertThat(value.path("code").asText()).isEqualTo(code);
    assertThat(value.path("message").asText()).isEqualTo(message);
  }

  private NkCreateHttpFixture fixture() {
    return new NkCreateHttpFixture(port, json, jdbc, users, logins);
  }

  private String validBody(NkCreateHttpFixture.Fixture fixture) throws Exception {
    return json.writeValueAsString(
        Map.of(
            "questionPresetId", Long.toString(fixture.questionId()),
            "choiceIds", fixture.choiceIds().stream().map(String::valueOf).toList(),
            "correctChoiceId", Long.toString(fixture.choiceIds().getFirst()),
            "rewardPresetId", Long.toString(fixture.rewardId())));
  }

  private PublicationRows publicationRows(NkCreateHttpFixture.Fixture fixture) {
    long rooms =
        jdbc.sql("select count(*) from rooms where host_user_id = :userId")
            .param("userId", fixture.user().getId())
            .query(Long.class)
            .single();
    long games =
        jdbc.sql(
                """
                select count(*)
                from games game
                join rooms room on room.id = game.room_id
                where room.host_user_id = :userId
                """)
            .param("userId", fixture.user().getId())
            .query(Long.class)
            .single();
    long choices =
        jdbc.sql(
                """
                select count(*)
                from choices choice
                join games game on game.id = choice.game_id
                join rooms room on room.id = game.room_id
                where room.host_user_id = :userId
                """)
            .param("userId", fixture.user().getId())
            .query(Long.class)
            .single();
    long records =
        jdbc.sql("select count(*) from publication_idempotency where user_id = :userId")
            .param("userId", fixture.user().getId())
            .query(Long.class)
            .single();
    return new PublicationRows(rooms, games, choices, records);
  }

  private Csrf csrf() throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/auth/csrf"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return new Csrf(
        response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
        json.readTree(response.body()).required("token").textValue());
  }

  private HttpResponse<String> post(
      String body, String accessToken, String key, String cookie, String csrf) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/games"))
            .header("Origin", "http://localhost:5173")
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (accessToken != null) {
      request.header("Authorization", "Bearer " + accessToken);
    }
    if (cookie != null) {
      request.header("Cookie", cookie);
    }
    if (csrf != null) {
      request.header("X-XSRF-TOKEN", csrf);
    }
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private record Csrf(String cookie, String token) {}

  private record PublicationRows(long rooms, long games, long choices, long records) {}

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
        PostgresTestDatabase.container().getJdbcUrl(), "nk75_http_security");
  }
}

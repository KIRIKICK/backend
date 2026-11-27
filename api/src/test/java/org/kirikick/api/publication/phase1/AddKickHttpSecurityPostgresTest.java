package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"local", "test"})
@Import(OpenRoomHttpPostgresTest.DomainEntities.class)
class AddKickHttpSecurityPostgresTest {
  private static final String ORIGIN = "http://localhost:5173";
  private static final Duration TIMEOUT = Duration.ofSeconds(15);

  @LocalServerPort private int port;
  @Autowired private ObjectMapper json;
  @Autowired private JdbcClient jdbc;
  @Autowired private UserRepository users;
  @Autowired private LoginSessionService logins;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    registry.add("spring.datasource.url", AddKickHttpSecurityPostgresTest::jdbcUrl);
    registry.add("spring.datasource.username", PostgresTestDatabase.container()::getUsername);
    registry.add("spring.datasource.password", PostgresTestDatabase.container()::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.default-schema", () -> "nk76_http_security");
    registry.add("publication.grace", () -> "PT5M");
  }

  @BeforeEach
  void requirePostgres17() {
    assertThat(jdbc.sql("show server_version").query(String.class).single()).startsWith("17.");
  }

  @Test
  @DisplayName("추가 출제 POST만 Idempotency-Key CORS와 CSRF 특례를 받는다")
  void postOnlyCorsAndCsrfBoundary() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture().fixture();
    HttpResponse<String> postPreflight =
        preflight(
            "/rooms/" + fixture.roomId() + "/games",
            "POST",
            String.join(
                ", ",
                List.of(
                    HttpHeaders.AUTHORIZATION,
                    HttpHeaders.CONTENT_TYPE,
                    "X-XSRF-TOKEN",
                    "Idempotency-Key")));
    assertThat(postPreflight.statusCode()).isEqualTo(200);
    String allowedHeaders =
        postPreflight.headers().firstValue("Access-Control-Allow-Headers").orElse("");
    assertThat(allowedHeaders)
        .containsIgnoringCase(HttpHeaders.AUTHORIZATION)
        .containsIgnoringCase(HttpHeaders.CONTENT_TYPE)
        .containsIgnoringCase("X-XSRF-TOKEN")
        .containsIgnoringCase("Idempotency-Key");

    HttpResponse<String> getPreflight =
        preflight("/rooms/" + fixture.roomId() + "/games", "GET", "Idempotency-Key");
    assertThat(getPreflight.statusCode()).isEqualTo(403);
    HttpResponse<String> multiSegment =
        preflight("/rooms/" + fixture.roomId() + "/nested/games", "POST", "Idempotency-Key");
    assertThat(multiSegment.statusCode()).isEqualTo(403);

    String body =
        json.writeValueAsString(
            new Request(
                String.valueOf(fixture.questionId()),
                fixture.choiceIds().stream().map(String::valueOf).toList(),
                String.valueOf(fixture.choiceIds().getFirst())));
    long before = gameCount(fixture.roomId());
    HttpResponse<String> missingCsrf =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(endpoint("/rooms/" + fixture.roomId() + "/games"))
                    .timeout(TIMEOUT)
                    .header(HttpHeaders.ORIGIN, ORIGIN)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.grant().accessToken())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(missingCsrf.statusCode()).isEqualTo(403);
    assertThat(gameCount(fixture.roomId())).isEqualTo(before);
  }

  @Test
  @DisplayName("OpenAPI는 기존 operation ID를 유지하며 두 경로를 출제 그룹에 표시한다")
  void openApiGroupsPublicationWithoutChangingOperations() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture().fixture();
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(endpoint("/v3/api-docs"))
                    .timeout(TIMEOUT)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.grant().accessToken())
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    var document = json.readTree(response.body());
    assertThat(document.at("/paths/~1games/post/operationId").textValue()).isEqualTo("publish");
    assertThat(document.at("/paths/~1rooms~1{roomId}~1games/post/operationId").textValue())
        .isEqualTo("addKick");
    assertThat(document.at("/paths/~1games/post/tags"))
        .extracting(JsonNode::asText)
        .containsExactly("출제");
    assertThat(document.at("/paths/~1rooms~1{roomId}~1games/post/tags"))
        .extracting(JsonNode::asText)
        .containsExactly("출제");
    assertThat(document.path("tags"))
        .filteredOn(tag -> "출제".equals(tag.path("name").asText()))
        .singleElement()
        .satisfies(tag ->
            assertThat(tag.path("description").asText())
                .isEqualTo("방을 열면서 첫 킥을 공개하거나, 기존 방에 킥을 추가합니다."));
    assertThat(
            document
                .at(
                    "/paths/~1rooms~1{roomId}~1games/post/responses/201/content/application~1json/schema/$ref")
                .textValue())
        .endsWith("/NkCreateResponse");
    assertThat(
            document
                .at("/paths/~1games/post/requestBody/content/application~1json/schema/$ref")
                .textValue())
        .endsWith("/NkCreateRequest");
    assertThat(
            document
                .at(
                    "/paths/~1rooms~1{roomId}~1games/post/requestBody/content/application~1json/schema/$ref")
                .textValue())
        .endsWith("/NkKickAddRequest");
    assertThat(document.at("/paths/~1games/post/security/0/bearerAuth").isArray()).isTrue();
    assertThat(document.at("/paths/~1rooms~1{roomId}~1games/post/security/0/bearerAuth").isArray())
        .isTrue();
  }

  private HttpResponse<String> preflight(String path, String method, String requestedHeaders)
      throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(endpoint(path))
                .timeout(TIMEOUT)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", requestedHeaders)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private AddKickHttpFixture fixture() {
    return new AddKickHttpFixture(port, json, jdbc, users, logins);
  }

  private URI endpoint(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private long gameCount(long roomId) {
    return jdbc.sql("select count(*) from games where room_id = :roomId")
        .param("roomId", roomId)
        .query(Long.class)
        .single();
  }

  private static String jdbcUrl() {
    return AddKickHttpFixture.withCurrentSchema(
        PostgresTestDatabase.container().getJdbcUrl(), "nk76_http_security");
  }

  private record Request(String questionPresetId, List<String> choiceIds, String correctChoiceId) {}
}

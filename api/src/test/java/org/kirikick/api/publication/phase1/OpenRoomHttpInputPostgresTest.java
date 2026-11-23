package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.kirikick.api.ApiApplication;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.publication.controller.OpenRoomController;
import org.kirikick.api.publication.controller.dto.NkCreateRequest;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = ApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(OpenRoomHttpPostgresTest.DomainEntities.class)
class OpenRoomHttpInputPostgresTest {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    OpenRoomHttpPostgresTest.infrastructure(registry);
  }

  @LocalServerPort private int port;
  @Autowired private ObjectMapper json;
  @Autowired private JdbcClient jdbc;
  @Autowired private UserRepository users;
  @Autowired private LoginSessionService logins;

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("invalidIdentifiers")
  @DisplayName("U-02: 실제 HTTP의 모든 ID 필드에서 숫자 강제 변환과 잘못된 문자열을 거절한다")
  void rejectsInvalidIdTokens(String field, String token) throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    ObjectNode body = body(fixture);
    if (field.equals("choiceIds")) {
      body.withArray("choiceIds").set(0, json.readTree(token));
    } else {
      body.set(field, json.readTree(token));
    }
    assertInvalid(
        fixture,
        send(fixture, body.toString(), List.of(UUID.randomUUID().toString())),
        "INVALID_PUBLICATION_INPUT",
        "출제 입력이 유효하지 않습니다.");
  }

  static Stream<Arguments> invalidIdentifiers() {
    return Stream.of("questionPresetId", "choiceIds", "correctChoiceId", "rewardPresetId")
        .flatMap(field ->
            Stream.of(
                    "1",
                    "1.5",
                    "null",
                    "true",
                    "\"1.5\"",
                    "\"-1\"",
                    "\"0\"",
                    "\"9223372036854775808\"")
                .map(token -> Arguments.of(field, token)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidKeys")
  @DisplayName("U-01: 실제 HTTP는 중복 및 canonical v4가 아닌 요청 키를 거절한다")
  void rejectsInvalidKeys(List<String> keys) throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    assertInvalid(
        fixture,
        send(fixture, body(fixture).toString(), keys),
        "INVALID_PUBLICATION_REQUEST_KEY",
        "유효하지 않은 출제 요청 키입니다.");
  }

  static Stream<Arguments> invalidKeys() {
    String valid = "1b541b3d-498b-4a79-894c-e0d2cc842301";
    return Stream.of(
            List.<String>of(),
            List.of(valid, valid),
            List.of(valid + "," + valid),
            List.of(valid.toUpperCase()),
            List.of(valid.replace("4a79", "1a79")),
            List.of(valid.replace("4a79", "3a79")),
            List.of(valid.replace("4a79", "5a79")),
            List.of(valid.replace("894c", "794c")),
            List.of("not-a-key"))
        .map(Arguments::of);
  }

  @Test
  @DisplayName("U-01: 서블릿 경계에 보존된 요청 키 공백을 자동 교정하지 않는다")
  void rejectsWhitespaceWithoutNormalizingServletHeader() {
    for (String key :
        List.of(" 1b541b3d-498b-4a79-894c-e0d2cc842301", "1b541b3d-498b-4a79-894c-e0d2cc842301 ")) {
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.addHeader("Idempotency-Key", key);
      Jwt jwt =
          Jwt.withTokenValue("synthetic")
              .header("alg", "none")
              .subject(UUID.randomUUID().toString())
              .build();
      assertThatThrownBy(() ->
              new OpenRoomController(null)
                  .publish(
                      jwt,
                      request,
                      new NkCreateRequest("1", List.of("1", "2", "3", "4"), "1", "1")))
          .isInstanceOfSatisfying(
              DomainException.class,
              failure ->
                  assertThat(failure.getErrorCode())
                      .isEqualTo(PublicationErrorCode.INVALID_PUBLICATION_REQUEST_KEY));
    }
  }

  @Test
  @DisplayName("U-02: 실제 HTTP는 보기 개수·중복·정답 집합 및 알 수 없는 필드를 거절한다")
  void rejectsInvalidChoiceSetsAndUnknownProperties() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    for (int size : List.of(3, 5)) {
      ObjectNode body = body(fixture);
      body.putArray("choiceIds");
      for (int i = 0; i < size; i++)
        body.withArray("choiceIds").add(Long.toString(fixture.choiceIds().get(i % 4)));
      assertInvalid(
          fixture,
          send(fixture, body.toString(), List.of(UUID.randomUUID().toString())),
          "INVALID_PUBLICATION_INPUT",
          "출제 입력이 유효하지 않습니다.");
    }
    ObjectNode duplicate = body(fixture);
    duplicate.withArray("choiceIds").set(1, duplicate.withArray("choiceIds").get(0));
    ObjectNode correct = body(fixture);
    correct.put("correctChoiceId", "9223372036854775807");
    ObjectNode unknown = body(fixture);
    unknown.put("userId", "1");
    for (ObjectNode body : List.of(duplicate, correct, unknown)) {
      assertInvalid(
          fixture,
          send(fixture, body.toString(), List.of(UUID.randomUUID().toString())),
          "INVALID_PUBLICATION_INPUT",
          "출제 입력이 유효하지 않습니다.");
    }
  }

  private NkCreateHttpFixture fixture() {
    return new NkCreateHttpFixture(port, json, jdbc, users, logins);
  }

  private ObjectNode body(NkCreateHttpFixture.Fixture fixture) {
    return json.valueToTree(
        Map.of(
            "questionPresetId",
            Long.toString(fixture.questionId()),
            "choiceIds",
            fixture.choiceIds().stream().map(String::valueOf).toList(),
            "correctChoiceId",
            Long.toString(fixture.choiceIds().getFirst()),
            "rewardPresetId",
            Long.toString(fixture.rewardId())));
  }

  private HttpResponse<String> send(
      NkCreateHttpFixture.Fixture fixture, String body, List<String> keys) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> csrf =
        client.send(
            HttpRequest.newBuilder(fixture().backend("/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    HttpRequest.Builder request =
        HttpRequest.newBuilder(fixture().backend("/games"))
            .header("Origin", "http://localhost:5173")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + fixture.grant().accessToken())
            .header(
                "Cookie", csrf.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0])
            .header("X-XSRF-TOKEN", json.readTree(csrf.body()).required("token").textValue())
            .POST(HttpRequest.BodyPublishers.ofString(body));
    keys.forEach(key -> request.header("Idempotency-Key", key));
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private void assertInvalid(
      NkCreateHttpFixture.Fixture fixture,
      HttpResponse<String> response,
      String code,
      String message)
      throws Exception {
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(400);
    assertThat(json.readTree(response.body()))
        .isEqualTo(json.valueToTree(Map.of("code", code, "message", message)));
    assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(response.headers().firstValue("Location")).isEmpty();
    assertThat(response.headers().firstValue("Retry-After")).isEmpty();
    assertThat(
            jdbc.sql("select count(*) from rooms where host_user_id=:id")
                .param("id", fixture.user().getId())
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql("select count(*) from publication_idempotency where user_id=:id")
                .param("id", fixture.user().getId())
                .query(Long.class)
                .single())
        .isZero();
  }
}

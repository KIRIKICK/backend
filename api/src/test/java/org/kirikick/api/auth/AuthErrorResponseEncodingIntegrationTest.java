package org.kirikick.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.LoginGrant;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.infra.RedisRefreshSessionStore;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthErrorResponseEncodingIntegrationTest {
  @LocalServerPort private int port;
  @Autowired private ObjectMapper json;
  @Autowired private LoginSessionService logins;
  @Autowired private UserRepository users;
  @Autowired private RedisRefreshSessionStore sessions;

  private final HttpClient client =
      HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    String database = "jdbc:h2:mem:auth-error-encoding-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    registry.add("spring.datasource.url", () -> database);
  }

  @Test
  void authenticationEntryPointPreservesKoreanMessageAsUtf8() throws Exception {
    HttpResponse<byte[]> response =
        client.send(
            HttpRequest.newBuilder(uri("/test/protected")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());

    assertError(response, 401, AuthErrorCode.AUTHENTICATION_REQUIRED.getMessage());
  }

  @Test
  void originValidationFilterPreservesKoreanMessageAsUtf8() throws Exception {
    HttpResponse<byte[]> csrf =
        client.send(
            HttpRequest.newBuilder(uri("/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(csrf.statusCode()).isEqualTo(200);
    String token = json.readTree(csrf.body()).get("token").asText();

    HttpResponse<byte[]> response =
        client.send(
            HttpRequest.newBuilder(uri("/auth/refresh"))
                .header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());

    assertError(response, 403, "허용되지 않은 출처입니다.");
  }

  @Test
  void sessionAuthorizationFilterPreservesKoreanMessageAsUtf8() throws Exception {
    LoginGrant grant = logins.login(users.saveAndFlush(User.create("인코딩검증", null)));
    sessions.revoke(grant.session().id());

    HttpResponse<byte[]> response =
        client.send(
            HttpRequest.newBuilder(uri("/auth/csrf"))
                .header("Authorization", "Bearer " + grant.accessToken())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());

    assertError(response, 401, AuthErrorCode.AUTHENTICATION_REQUIRED.getMessage());
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private void assertError(HttpResponse<byte[]> response, int status, String message)
      throws Exception {
    assertThat(response.statusCode()).isEqualTo(status);
    String body = new String(response.body(), StandardCharsets.UTF_8);
    assertThat(json.readTree(body).get("message").asText()).isEqualTo(message);
    assertThat(response.headers().firstValue("Content-Type").orElseThrow())
        .containsIgnoringCase("charset=UTF-8");
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
  }
}

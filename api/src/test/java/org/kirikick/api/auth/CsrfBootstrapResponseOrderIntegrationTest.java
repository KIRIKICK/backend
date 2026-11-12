package org.kirikick.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.kirikick.api.auth.domain.LoginGrant;
import org.kirikick.api.auth.infra.RedisRefreshSessionStore;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.config.AuthProperties;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CsrfBootstrapResponseOrderIntegrationTest {
  private static final String CSRF_COOKIE = "dev-csrf";
  private static final String REFRESH_COOKIE = "dev-refresh";
  private static final String CSRF_HEADER = "X-XSRF-TOKEN";
  private static final String FRONTEND_ORIGIN = "http://localhost:5173";
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

  @LocalServerPort private int port;
  @Autowired private ObjectMapper json;
  @Autowired private LoginSessionService logins;
  @Autowired private UserRepository users;
  @Autowired private AuthProperties authProperties;
  @Autowired private StringRedisTemplate redis;
  @MockitoSpyBean private RedisRefreshSessionStore sessions;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    String database = "jdbc:h2:mem:csrf-bootstrap-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    registry.add("spring.datasource.url", () -> database);
  }

  @BeforeEach
  void resetSessionSpy() {
    reset(sessions);
  }

  @AfterAll
  static void closeHttpClient() {
    HTTP.close();
  }

  @ParameterizedTest
  @EnumSource(ResponseOrder.class)
  @DisplayName("초기 CSRF 응답의 적용 순서와 본문 선택은 Refresh 검증 경계를 따른다")
  void initialCsrfResponseOrderIsModeledWithActualCookieAndMaskedBody(ResponseOrder order)
      throws Exception {
    CsrfBootstrap first = bootstrap();
    CsrfBootstrap second = bootstrap();
    assertThat(first.rawCookie.getValue())
        .withFailMessage("Cookie-free CSRF responses must have distinct raw cookies")
        .isNotEqualTo(second.rawCookie.getValue());

    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    apply(cookies, order.first(first, second));
    apply(cookies, order.second(first, second));
    assertThat(csrfCookie(cookies).getValue())
        .withFailMessage("The last modeled response must select the CSRF cookie")
        .isEqualTo(order.second(first, second).rawCookie().getValue());
    String selectedToken = order.body(first, second);
    LoginGrant grant = grant();
    var activeBefore = sessions.findActive(grant.session().id(), grant.session().userUuid());
    assertThat(activeBefore).isPresent();
    List<Object> rotationStateBefore = rotationState(grant);
    assertThat(rotationStateBefore)
        .withFailMessage("A fresh grant must have its family rotation state")
        .doesNotContainNull();
    long rotationsBefore = rotationCount();

    HttpResponse<String> response = refresh(cookies, grant, selectedToken);

    if (order.matchesFinalCookie()) {
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(isSuccessorRefresh(response, grant)).isTrue();
      assertThat(rotationCount()).isEqualTo(rotationsBefore + 1);
      assertThat(sessions.findActive(grant.session().id(), grant.session().userUuid())).isPresent();
      return;
    }

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(hasRefreshCookie(response.headers().allValues(HttpHeaders.SET_COOKIE))).isFalse();
    assertThat(rotationCount()).isEqualTo(rotationsBefore);
    var activeAfterRejection =
        sessions.findActive(grant.session().id(), grant.session().userUuid());
    assertThat(activeAfterRejection)
        .withFailMessage("CSRF rejection must preserve the active login session")
        .isEqualTo(activeBefore);
    assertThat(rotationState(grant))
        .withFailMessage("CSRF rejection must preserve the family digest and last-use time")
        .isEqualTo(rotationStateBefore);

    String finalRaw = csrfCookie(cookies).getValue();
    String currentToken = csrf(cookies);
    assertThat(csrfCookie(cookies).getValue())
        .withFailMessage("CSRF reacquisition must retain the current raw cookie")
        .isEqualTo(finalRaw);
    HttpResponse<String> recovered = refresh(cookies, grant, currentToken);
    assertThat(recovered.statusCode()).isEqualTo(200);
    assertThat(isSuccessorRefresh(recovered, grant)).isTrue();
    assertThat(rotationCount()).isEqualTo(rotationsBefore + 1);
  }

  @Test
  @DisplayName("같은 raw CSRF 쿠키에서 발급된 masked 토큰은 각각 Refresh에 사용할 수 있다")
  void multipleMaskedTokensForTheSameRawCookieAreEachAccepted() throws Exception {
    CsrfBootstrap initial = bootstrap();
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    apply(cookies, initial);

    String firstMasked = csrf(cookies);
    String stableRaw = csrfCookie(cookies).getValue();
    String secondMasked = csrf(cookies);
    assertThat(csrfCookie(cookies).getValue())
        .withFailMessage("Multiple masked tokens must share the unchanged raw cookie")
        .isEqualTo(stableRaw);

    assertThat(refresh(cookies, grant(), firstMasked).statusCode()).isEqualTo(200);
    assertThat(refresh(cookies, grant(), secondMasked).statusCode()).isEqualTo(200);
  }

  private CsrfBootstrap bootstrap() throws Exception {
    HttpResponse<String> response =
        HTTP.send(csrfRequest(null), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    CookieManager captured = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    apply(captured, response);
    return new CsrfBootstrap(response, token(response), csrfCookie(captured));
  }

  private String csrf(CookieManager cookies) throws Exception {
    HttpResponse<String> response =
        HTTP.send(csrfRequest(cookies), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    apply(cookies, response);
    return token(response);
  }

  private HttpRequest csrfRequest(CookieManager cookies) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(backend("/auth/csrf")).timeout(HTTP_TIMEOUT).GET();
    if (cookies != null) {
      request.header(HttpHeaders.COOKIE, cookieHeader(cookies));
    }
    return request.build();
  }

  private HttpResponse<String> refresh(CookieManager cookies, LoginGrant grant, String maskedToken)
      throws Exception {
    String cookieHeader = cookieHeader(cookies);
    HttpRequest request =
        HttpRequest.newBuilder(backend("/auth/refresh"))
            .timeout(HTTP_TIMEOUT)
            .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
            .header(
                HttpHeaders.COOKIE,
                REFRESH_COOKIE + "=" + grant.refreshToken() + "; " + cookieHeader)
            .header(CSRF_HEADER, maskedToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private LoginGrant grant() {
    User user =
        users.saveAndFlush(
            User.create("csrf" + UUID.randomUUID().toString().substring(0, 6), null));
    return logins.login(user);
  }

  private String token(HttpResponse<String> response) throws Exception {
    return json.readTree(response.body()).required("token").textValue();
  }

  private void apply(CookieManager cookies, CsrfBootstrap bootstrap) throws Exception {
    apply(cookies, bootstrap.response());
  }

  private void apply(CookieManager cookies, HttpResponse<?> response) throws Exception {
    cookies.put(backend("/auth/csrf"), response.headers().map());
  }

  private HttpCookie csrfCookie(CookieManager cookies) {
    return cookies.getCookieStore().getCookies().stream()
        .filter(cookie -> CSRF_COOKIE.equals(cookie.getName()))
        .findFirst()
        .orElseThrow();
  }

  private String cookieHeader(CookieManager cookies) {
    List<String> values = new ArrayList<>();
    for (HttpCookie cookie : cookies.getCookieStore().getCookies()) {
      if (!cookie.hasExpired()) {
        values.add(cookie.getName() + "=" + cookie.getValue());
      }
    }
    return String.join("; ", values);
  }

  private URI backend(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private boolean hasRefreshCookie(List<String> setCookies) {
    return setCookies.stream().anyMatch(value -> value.startsWith(REFRESH_COOKIE + "="));
  }

  private boolean isSuccessorRefresh(HttpResponse<String> response, LoginGrant grant)
      throws Exception {
    if (!hasRefreshCookie(response.headers().allValues(HttpHeaders.SET_COOKIE))) {
      return false;
    }
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    cookies.put(backend("/auth/refresh"), response.headers().map());
    return cookies.getCookieStore().getCookies().stream()
        .filter(cookie -> REFRESH_COOKIE.equals(cookie.getName()))
        .anyMatch(cookie ->
            !cookie.hasExpired()
                && !cookie.getValue().isBlank()
                && !cookie.getValue().equals(grant.refreshToken()));
  }

  private List<Object> rotationState(LoginGrant grant) {
    return redis
        .opsForHash()
        .multiGet(
            authProperties.redisNamespace() + ":session:" + grant.session().id(),
            List.of("currentDigest", "lastUsedAt"));
  }

  private long rotationCount() {
    return mockingDetails(sessions).getInvocations().stream()
        .filter(invocation -> invocation.getMethod().getName().equals("rotate"))
        .count();
  }

  private enum ResponseOrder {
    A_THEN_B_WITH_A_BODY(false, true),
    A_THEN_B_WITH_B_BODY(true, true),
    B_THEN_A_WITH_A_BODY(true, false),
    B_THEN_A_WITH_B_BODY(false, false);

    private final boolean matchesFinalCookie;
    private final boolean applyAFirst;

    ResponseOrder(boolean matchesFinalCookie, boolean applyAFirst) {
      this.matchesFinalCookie = matchesFinalCookie;
      this.applyAFirst = applyAFirst;
    }

    boolean matchesFinalCookie() {
      return matchesFinalCookie;
    }

    CsrfBootstrap first(CsrfBootstrap a, CsrfBootstrap b) {
      return applyAFirst ? a : b;
    }

    CsrfBootstrap second(CsrfBootstrap a, CsrfBootstrap b) {
      return applyAFirst ? b : a;
    }

    String body(CsrfBootstrap a, CsrfBootstrap b) {
      return switch (this) {
        case A_THEN_B_WITH_A_BODY, B_THEN_A_WITH_A_BODY -> a.token();
        case A_THEN_B_WITH_B_BODY, B_THEN_A_WITH_B_BODY -> b.token();
      };
    }
  }

  private record CsrfBootstrap(HttpResponse<String> response, String token, HttpCookie rawCookie) {}
}

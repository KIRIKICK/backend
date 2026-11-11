package org.kirikick.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.infra.JwtTokenService;
import org.kirikick.api.auth.infra.RedisRefreshSessionStore;
import org.kirikick.api.auth.repository.OAuthIdentityRepository;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.auth.support.LocalOidcProvider;
import org.kirikick.api.config.AuthProperties;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
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
class RefreshTokenConcurrencyIntegrationTest {
  private static final LocalOidcProvider OIDC = startProvider();
  private static final String FRONTEND_ORIGIN = "http://localhost:5173";
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

  @LocalServerPort private int port;
  @Autowired private ObjectMapper json;
  @Autowired private UserRepository users;
  @Autowired private OAuthIdentityRepository identities;
  @Autowired private JwtTokenService tokens;
  @Autowired private AuthProperties authProperties;
  @Autowired private StringRedisTemplate redis;
  @MockitoSpyBean private RedisRefreshSessionStore sessions;

  private final List<HttpClient> browserClients = new ArrayList<>();

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    OIDC.authProperties().forEach((name, value) -> registry.add(name, () -> value));
    registry.add("spring.datasource.url", PostgresTestDatabase.container()::getJdbcUrl);
    registry.add("spring.datasource.username", PostgresTestDatabase.container()::getUsername);
    registry.add("spring.datasource.password", PostgresTestDatabase.container()::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @BeforeEach
  void resetSessionSpy() {
    OIDC.clearCapturedRequests();
    reset(sessions);
  }

  @AfterEach
  void closeBrowserClients() {
    browserClients.forEach(HttpClient::close);
    browserClients.clear();
    reset(sessions);
  }

  @AfterAll
  static void closeOidc() {
    HTTP.close();
    OIDC.close();
  }

  @ParameterizedTest
  @EnumSource(HeaderOrder.class)
  @DisplayName("동일 Refresh 토큰의 동시 사용은 family를 폐기하고 새 로그인으로 복구할 수 있다")
  void concurrentRefreshReplayRevokesFamilyWithoutOrderDependentCookieResult(HeaderOrder order)
      throws Exception {
    Browser browser = authenticatedBrowser();
    RefreshResult baseline = refresh(browser.client(), browser.csrfToken());
    assertThat(baseline.response().statusCode()).isEqualTo(200);
    assertRefreshSuccess(baseline.response());
    assertThat(protectedProfile(baseline.accessToken()).statusCode()).isEqualTo(200);

    String oldRequestCookies = cookieHeader(browser.cookies());
    String oldRefresh = refreshCookieValue(browser.cookies());
    assertThat(hasRefreshCookie(browser.cookies())).isTrue();
    assertThat(hasCookieNamed(oldRequestCookies, "dev-refresh")).isTrue();
    String oldSessionId = sessionId(baseline.accessToken());
    assertThat(sessions.findActive(oldSessionId, browser.user().getUuid()).isPresent()).isTrue();

    CountDownLatch rotateArrivals = new CountDownLatch(2);
    CountDownLatch releaseRotations = new CountDownLatch(1);
    Queue<String> rotationInputs = new ConcurrentLinkedQueue<>();
    willAnswer(
            invocation -> {
              rotationInputs.add(invocation.getArgument(0));
              rotateArrivals.countDown();
              if (!releaseRotations.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to release refresh rotations");
              }
              return invocation.callRealMethod();
            })
        .given(sessions)
        .rotate(anyString());

    CompletableFuture<HttpResponse<String>> firstRequest = null;
    CompletableFuture<HttpResponse<String>> secondRequest = null;
    HttpResponse<String> first;
    HttpResponse<String> second;
    try {
      firstRequest = manualRefresh(oldRequestCookies, browser.csrfToken());
      secondRequest = manualRefresh(oldRequestCookies, browser.csrfToken());
      assertThat(rotateArrivals.await(10, TimeUnit.SECONDS)).isTrue();
      releaseRotations.countDown();
      first = firstRequest.get(10, TimeUnit.SECONDS);
      second = secondRequest.get(10, TimeUnit.SECONDS);
    } finally {
      releaseRotations.countDown();
      cancel(firstRequest);
      cancel(secondRequest);
      reset(sessions);
    }

    assertThat(
            rotationInputs.size() == 2
                && rotationInputs.stream().allMatch(input -> input.equals(oldRefresh)))
        .isTrue();

    RaceResponses responses = classify(first, second);
    assertRefreshSuccess(responses.success());
    assertRefreshReplay(responses.replay());
    assertThat(isRevoked(oldSessionId)).isTrue();
    assertThat(sessions.findActive(oldSessionId, browser.user().getUuid()).isEmpty()).isTrue();

    CookieManager model = copyCookies(browser.cookies());
    apply(model, order.first(responses));
    apply(model, order.second(responses));
    String winnerRefresh = refreshCookieFrom(responses.success());
    String modeledRefresh = refreshCookieValue(model);
    assertThat(modeledRefresh.equals(winnerRefresh)).isTrue();
    assertThat(!modeledRefresh.equals(oldRefresh)).isTrue();
    assertThat(hasRefreshCookie(model)).isTrue();

    String successorRequestCookies = cookieHeader(model);
    assertThat(hasCookieNamed(successorRequestCookies, "dev-refresh")).isTrue();
    assertThat(refreshManually(oldRequestCookies, browser.csrfToken()).statusCode()).isEqualTo(401);
    assertThat(refreshManually(successorRequestCookies, browser.csrfToken()).statusCode())
        .isEqualTo(401);

    assertThat(protectedProfile(baseline.accessToken()).statusCode()).isEqualTo(401);
    assertThat(protectedProfile(responses.successAccessToken()).statusCode()).isEqualTo(401);
    String winnerSessionId = sessionId(responses.successAccessToken());
    assertThat(winnerSessionId.equals(oldSessionId)).isTrue();
    assertThat(sessions.findActive(winnerSessionId, browser.user().getUuid()).isEmpty()).isTrue();

    Browser recovered = authenticatedBrowser(browser.user(), browser.subject(), model);
    RefreshResult recoveredRefresh = refresh(recovered.client(), recovered.csrfToken());
    assertThat(recoveredRefresh.response().statusCode()).isEqualTo(200);
    assertThat(protectedProfile(recoveredRefresh.accessToken()).statusCode()).isEqualTo(200);
    assertThat(hasRefreshCookie(recovered.cookies())).isTrue();
    String recoveredSessionId = sessionId(recoveredRefresh.accessToken());
    assertThat(!recoveredSessionId.equals(oldSessionId)).isTrue();
    assertThat(sessions.findActive(recoveredSessionId, browser.user().getUuid()).isPresent())
        .isTrue();
    assertThat(sessions.findActive(oldSessionId, browser.user().getUuid()).isEmpty()).isTrue();
  }

  private Browser authenticatedBrowser() throws Exception {
    User user =
        users.saveAndFlush(
            User.create("동시갱신" + UUID.randomUUID().toString().substring(0, 6), null));
    String subject = "refresh-concurrency-" + UUID.randomUUID();
    identities.saveAndFlush(OAuthIdentity.create(user.getId(), OAuthProvider.KAKAO, subject));
    return authenticatedBrowser(user, subject, new CookieManager(null, CookiePolicy.ACCEPT_ALL));
  }

  private Browser authenticatedBrowser(User user, String subject, CookieManager cookies)
      throws Exception {
    HttpClient client =
        HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).cookieHandler(cookies).build();
    browserClients.add(client);
    OIDC.nextTokenSubject(subject);
    HttpResponse<Void> login = oauth(client);
    assertThat(login.statusCode()).isEqualTo(303);
    assertThat(hasRefreshCookie(cookies)).isTrue();

    HttpResponse<String> csrf =
        client.send(
            HttpRequest.newBuilder(backend("/auth/csrf")).timeout(HTTP_TIMEOUT).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(csrf.statusCode()).isEqualTo(200);
    return new Browser(
        client, cookies, user, subject, json.readTree(csrf.body()).required("token").textValue());
  }

  private HttpResponse<Void> oauth(HttpClient browser) throws Exception {
    HttpResponse<Void> start =
        browser.send(
            HttpRequest.newBuilder(backend("/oauth2/authorization/kakao")).GET().build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(start.statusCode()).isEqualTo(302);
    HttpResponse<Void> authorization =
        browser.send(
            HttpRequest.newBuilder(URI.create(start.headers().firstValue("Location").orElseThrow()))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(authorization.statusCode()).isEqualTo(302);
    URI callback = URI.create(authorization.headers().firstValue("Location").orElseThrow());
    return browser.send(
        HttpRequest.newBuilder(backend(callback.getRawPath() + "?" + callback.getRawQuery()))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.discarding());
  }

  private RefreshResult refresh(HttpClient browser, String csrfToken) throws Exception {
    HttpResponse<String> response =
        browser.send(refreshRequest(null, csrfToken), HttpResponse.BodyHandlers.ofString());
    return new RefreshResult(response, accessToken(response));
  }

  private CompletableFuture<HttpResponse<String>> manualRefresh(String cookies, String csrfToken) {
    return HTTP.sendAsync(refreshRequest(cookies, csrfToken), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> refreshManually(String cookies, String csrfToken) throws Exception {
    return HTTP.send(refreshRequest(cookies, csrfToken), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest refreshRequest(String cookies, String csrfToken) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(backend("/auth/refresh"))
            .timeout(HTTP_TIMEOUT)
            .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
            .header("X-XSRF-TOKEN", csrfToken)
            .POST(HttpRequest.BodyPublishers.noBody());
    if (cookies != null) {
      request.header(HttpHeaders.COOKIE, cookies);
    }
    return request.build();
  }

  private HttpResponse<String> protectedProfile(String accessToken) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(backend("/users/me/profile"))
            .timeout(HTTP_TIMEOUT)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private RaceResponses classify(HttpResponse<String> first, HttpResponse<String> second)
      throws Exception {
    List<HttpResponse<String>> responses = List.of(first, second);
    List<HttpResponse<String>> successful =
        responses.stream().filter(response -> response.statusCode() == 200).toList();
    List<HttpResponse<String>> replay =
        responses.stream().filter(response -> response.statusCode() == 401).toList();
    assertThat(successful).hasSize(1);
    assertThat(replay).hasSize(1);
    assertThat(errorCode(replay.getFirst())).isEqualTo(AuthErrorCode.REFRESH_TOKEN_REUSED.name());
    return new RaceResponses(
        successful.getFirst(), replay.getFirst(), accessToken(successful.getFirst()));
  }

  private void assertRefreshSuccess(HttpResponse<String> response) throws Exception {
    assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    assertSetCookieMetadata(response, List.of("dev-refresh:set"));
    HttpCookie refreshCookie =
        response.headers().allValues(HttpHeaders.SET_COOKIE).stream()
            .flatMap(value -> HttpCookie.parse(value).stream())
            .filter(cookie -> cookie.getName().equals("dev-refresh"))
            .findFirst()
            .orElseThrow();
    assertThat(refreshCookie.isHttpOnly()).isTrue();
    assertThat("/".equals(refreshCookie.getPath())).isTrue();
    assertThat(refreshCookie.getDomain() == null).isTrue();
    assertThat(refreshCookie.getSecure()).isFalse();
    assertThat(refreshCookie.getMaxAge() > 0).isTrue();
    assertThat(
            response.headers().allValues(HttpHeaders.SET_COOKIE).stream()
                .allMatch(value -> value.contains("SameSite=Strict")))
        .isTrue();
    assertThat(accessToken(response).isBlank()).isFalse();
  }

  private void assertRefreshReplay(HttpResponse<String> response) throws Exception {
    assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    assertThat(errorCode(response)).isEqualTo(AuthErrorCode.REFRESH_TOKEN_REUSED.name());
    assertSetCookieMetadata(response, List.of());
  }

  private String accessToken(HttpResponse<String> response) throws IOException {
    JsonNode body = json.readTree(response.body());
    return body.required("accessToken").textValue();
  }

  private String errorCode(HttpResponse<String> response) throws IOException {
    return json.readTree(response.body()).required("code").textValue();
  }

  private String sessionId(String accessToken) {
    return tokens.decode(accessToken).getClaimAsString("sid");
  }

  private URI backend(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private CookieManager copyCookies(CookieManager source) {
    CookieManager copy = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    for (HttpCookie cookie : source.getCookieStore().getCookies()) {
      copy.getCookieStore().add(backend("/auth/refresh"), (HttpCookie) cookie.clone());
    }
    return copy;
  }

  private void apply(CookieManager cookies, HttpResponse<String> response) throws IOException {
    cookies.put(backend("/auth/refresh"), response.headers().map());
  }

  private boolean isRevoked(String sessionId) {
    return "REVOKED"
        .equals(
            redis
                .opsForHash()
                .get(authProperties.redisNamespace() + ":session:" + sessionId, "status"));
  }

  private static void cancel(CompletableFuture<?> future) {
    if (future != null && !future.isDone()) {
      future.cancel(true);
    }
  }

  private static String refreshCookieValue(CookieManager cookies) {
    return cookies.getCookieStore().getCookies().stream()
        .filter(cookie -> cookie.getName().equals("dev-refresh") && !cookie.getValue().isBlank())
        .map(HttpCookie::getValue)
        .findFirst()
        .orElseThrow();
  }

  private static String refreshCookieFrom(HttpResponse<String> response) {
    return response.headers().allValues(HttpHeaders.SET_COOKIE).stream()
        .filter(value -> setCookieName(value).equals("dev-refresh"))
        .map(RefreshTokenConcurrencyIntegrationTest::setCookieValue)
        .findFirst()
        .orElseThrow();
  }

  private static void assertSetCookieMetadata(
      HttpResponse<String> response, List<String> expectedMetadata) {
    List<String> actualMetadata =
        response.headers().allValues(HttpHeaders.SET_COOKIE).stream()
            .map(value ->
                setCookieName(value)
                    + (value.toLowerCase().contains("max-age=0") ? ":delete" : ":set"))
            .toList();
    assertThat(actualMetadata).containsExactlyElementsOf(expectedMetadata);
  }

  private static String setCookieName(String value) {
    return value.substring(0, value.indexOf('='));
  }

  private static String setCookieValue(String value) {
    int start = value.indexOf('=') + 1;
    int end = value.indexOf(';', start);
    return value.substring(start, end == -1 ? value.length() : end);
  }

  private static boolean hasRefreshCookie(CookieManager cookies) {
    return cookies.getCookieStore().getCookies().stream()
        .anyMatch(cookie -> cookie.getName().equals("dev-refresh") && !cookie.getValue().isBlank());
  }

  private static boolean hasCookieNamed(String cookies, String name) {
    return cookies.contains(name + "=");
  }

  private static String cookieHeader(CookieManager cookies) {
    List<String> values = new ArrayList<>();
    for (HttpCookie cookie : cookies.getCookieStore().getCookies()) {
      if (!cookie.hasExpired()) {
        values.add(cookie.getName() + "=" + cookie.getValue());
      }
    }
    return String.join("; ", values);
  }

  private enum HeaderOrder {
    SUCCESS_THEN_REPLAY {
      @Override
      HttpResponse<String> first(RaceResponses responses) {
        return responses.success();
      }

      @Override
      HttpResponse<String> second(RaceResponses responses) {
        return responses.replay();
      }
    },
    REPLAY_THEN_SUCCESS {
      @Override
      HttpResponse<String> first(RaceResponses responses) {
        return responses.replay();
      }

      @Override
      HttpResponse<String> second(RaceResponses responses) {
        return responses.success();
      }
    };

    abstract HttpResponse<String> first(RaceResponses responses);

    abstract HttpResponse<String> second(RaceResponses responses);
  }

  private record Browser(
      HttpClient client, CookieManager cookies, User user, String subject, String csrfToken) {}

  private record RefreshResult(HttpResponse<String> response, String accessToken) {}

  private record RaceResponses(
      HttpResponse<String> success, HttpResponse<String> replay, String successAccessToken) {}

  private static LocalOidcProvider startProvider() {
    try {
      return LocalOidcProvider.start();
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}

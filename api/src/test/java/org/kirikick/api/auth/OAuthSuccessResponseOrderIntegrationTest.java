package org.kirikick.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.kirikick.api.auth.domain.LoginGrant;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.infra.JwtTokenService;
import org.kirikick.api.auth.infra.RedisRefreshSessionStore;
import org.kirikick.api.auth.repository.OAuthIdentityRepository;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.auth.support.LocalOidcProvider;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OAuthSuccessResponseOrderIntegrationTest {

  private static final LocalOidcProvider OIDC = startProvider();
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
  private static final String FRONTEND_ORIGIN = "http://localhost:5173";

  @LocalServerPort private int port;
  @Autowired private ObjectMapper json;
  @Autowired private UserRepository users;
  @Autowired private OAuthIdentityRepository identities;
  @Autowired private RedisRefreshSessionStore sessions;
  @Autowired private JwtTokenService tokens;
  @MockitoSpyBean private LoginSessionService logins;

  private final List<HttpClient> clients = new ArrayList<>();
  private final ConcurrentLinkedQueue<LoginGrant> issued = new ConcurrentLinkedQueue<>();
  private HttpClient http;

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
  void setUp() {
    OIDC.clearCapturedRequests();
    issued.clear();
    http = browser(null);
    willAnswer(
            invocation -> {
              LoginGrant grant = (LoginGrant) invocation.callRealMethod();
              issued.add(grant);
              return grant;
            })
        .given(logins)
        .login(any(User.class));
  }

  @AfterEach
  void tearDown() {
    reset(logins);
    clients.forEach(HttpClient::close);
    clients.clear();
  }

  @AfterAll
  static void closeOidc() {
    OIDC.close();
  }

  @ParameterizedTest
  @EnumSource(ModelCase.class)
  @DisplayName("정상 OAuth 성공 응답의 CookieManager 적용 순서는 최종 refresh 계정을 선택한다")
  void successfulOAuthResponseOrderSelectsTheFinalRefreshAccount(ModelCase modelCase)
      throws Exception {
    User firstUser = registeredUser();
    User secondUser = modelCase.sameAccount ? firstUser : registeredUser();
    CookieManager flowCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    HttpClient flowClient = browser(flowCookies);
    csrfToken(flowClient);
    assertThat(refreshCookieValue(flowCookies).isEmpty()).isTrue();

    String firstSubject = "oauth-order-a-" + UUID.randomUUID();
    String secondSubject =
        modelCase.sameAccount ? firstSubject : "oauth-order-b-" + UUID.randomUUID();
    CapturedSuccess first =
        completeRegisteredOAuth(flowClient, flowCookies, firstUser, firstSubject);
    CapturedSuccess second =
        completeRegisteredOAuth(flowClient, flowCookies, secondUser, secondSubject);

    assertThat(issued.size()).isZero();
    assertThat(sessions.findActive(first.grant.session().id(), firstUser.getUuid()).isPresent())
        .isTrue();
    assertThat(sessions.findActive(second.grant.session().id(), secondUser.getUuid()).isPresent())
        .isTrue();

    CookieManager model = copyCookies(flowCookies);
    CapturedSuccess firstApplied = modelCase.applyFirst(first, second);
    CapturedSuccess secondApplied = modelCase.applySecond(first, second);
    apply(model, firstApplied.response);
    apply(model, secondApplied.response);

    assertThat(refreshCookieMatches(model, secondApplied.response)).isTrue();
    // Successful authentication expires CSRF; re-bootstrap only after both responses are applied.
    RefreshResult refresh = refresh(model, csrfToken(browser(model)));
    assertThat(refresh.response.statusCode()).isEqualTo(200);

    User selectedUser = secondApplied.user;
    assertThat(tokens.decode(refresh.accessToken).getSubject())
        .isEqualTo(selectedUser.getUuid().toString());
    assertThat(
            secondApplied
                .grant
                .session()
                .id()
                .equals(tokens.decode(refresh.accessToken).getClaimAsString("sid")))
        .isTrue();
    assertThat(
            sessions
                .findActive(secondApplied.grant.session().id(), selectedUser.getUuid())
                .isPresent())
        .isTrue();

    HttpResponse<String> profile = profile(refresh.accessToken);
    assertThat(profile.statusCode()).isEqualTo(200);
    assertThat(json.readTree(profile.body()).required("uuid").asText())
        .isEqualTo(selectedUser.getUuid().toString());

    // The non-selected family remains an observation: no lifecycle policy is asserted here.
    observeUnselectedFamily(modelCase.name(), firstApplied.grant, firstApplied.user);
  }

  private CapturedSuccess completeRegisteredOAuth(
      HttpClient flowClient, CookieManager flowCookies, User user, String subject)
      throws Exception {
    PreparedCallback callback = prepareCallback(flowClient, user, subject);
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(callback.callback)
                .timeout(HTTP_TIMEOUT)
                .header(HttpHeaders.COOKIE, cookieHeader(flowCookies))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertSuccessResponse(response);
    LoginGrant grant = Objects.requireNonNull(issued.poll(), "expected login grant");
    return new CapturedSuccess(user, grant, response);
  }

  @Test
  @DisplayName("늦은 성공 callback의 첫 헤더는 새 OAuth 응답 뒤에 적용된다")
  void delayedSuccessHeaderCanSelectTheEarlierOAuthAccount() throws Exception {
    assertUnblockedRelayPreservesCallback();
    User firstUser = registeredUser();
    User secondUser = registeredUser();
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    HttpClient browser = browser(cookies);
    csrfToken(browser);
    assertThat(refreshCookieValue(cookies).isEmpty()).isTrue();
    PreparedCallback first =
        prepareCallback(browser, firstUser, "oauth-relay-a-" + UUID.randomUUID());

    try (CallbackResponseRelay relay = new CallbackResponseRelay(first.callback)) {
      CountDownLatch firstHeadersReceived = new CountDownLatch(1);
      CompletableFuture<HttpResponse<byte[]>> delayed =
          browser.sendAsync(
              HttpRequest.newBuilder(relay.uri()).timeout(HTTP_TIMEOUT).GET().build(),
              responseInfo -> {
                firstHeadersReceived.countDown();
                return HttpResponse.BodySubscribers.ofByteArray();
              });
      assertThat(relay.awaitCaptured()).isTrue();
      assertThat(firstHeadersReceived.getCount()).isEqualTo(1L);
      LoginGrant firstGrant = Objects.requireNonNull(issued.poll(), "expected login grant");
      assertSuccessResponse(relay.upstream());

      CapturedSuccess second =
          completeWithBrowserCallback(browser, secondUser, "oauth-relay-b-" + UUID.randomUUID());
      assertThat(issued.size()).isZero();
      assertThat(sessions.findActive(firstGrant.session().id(), firstUser.getUuid()).isPresent())
          .isTrue();
      assertThat(sessions.findActive(second.grant.session().id(), secondUser.getUuid()).isPresent())
          .isTrue();
      assertThat(refreshCookieMatches(cookies, second.response)).isTrue();

      assertThat(firstHeadersReceived.getCount()).isEqualTo(1L);
      relay.release();
      HttpResponse<byte[]> late = delayed.get(10, TimeUnit.SECONDS);
      assertThat(firstHeadersReceived.getCount()).isZero();
      assertRelayParity(relay.upstream(), late);
      assertThat(refreshCookieMatches(cookies, late)).isTrue();

      RefreshResult refresh = refresh(cookies, csrfToken(browser));
      assertThat(refresh.response.statusCode()).isEqualTo(200);
      assertThat(tokens.decode(refresh.accessToken).getSubject())
          .isEqualTo(firstUser.getUuid().toString());
      assertThat(
              firstGrant
                  .session()
                  .id()
                  .equals(tokens.decode(refresh.accessToken).getClaimAsString("sid")))
          .isTrue();
      assertThat(sessions.findActive(firstGrant.session().id(), firstUser.getUuid()).isPresent())
          .isTrue();
      HttpResponse<String> profile = profile(refresh.accessToken);
      assertThat(profile.statusCode()).isEqualTo(200);
      assertThat(json.readTree(profile.body()).required("uuid").asText())
          .isEqualTo(firstUser.getUuid().toString());
      observeUnselectedFamily("HTTP_B_THEN_A", second.grant, secondUser);
    }
  }

  private void assertUnblockedRelayPreservesCallback() throws Exception {
    User user = registeredUser();
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    HttpClient browser = browser(cookies);
    PreparedCallback callback = prepareCallback(browser, user, "oauth-parity-" + UUID.randomUUID());
    try (CallbackResponseRelay relay = new CallbackResponseRelay(callback.callback)) {
      relay.release();
      HttpResponse<byte[]> response =
          browser.send(
              HttpRequest.newBuilder(relay.uri()).timeout(HTTP_TIMEOUT).GET().build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertSuccessResponse(response);
      assertRelayParity(relay.upstream(), response);
      LoginGrant grant = Objects.requireNonNull(issued.poll(), "expected login grant");
      assertThat(sessions.findActive(grant.session().id(), user.getUuid()).isPresent()).isTrue();
    }
  }

  private PreparedCallback prepareCallback(HttpClient flowClient, User user, String subject)
      throws Exception {
    OIDC.nextTokenSubject(subject);
    if (identities.findByProviderAndProviderSubject(OAuthProvider.KAKAO, subject).isEmpty()) {
      identities.saveAndFlush(OAuthIdentity.create(user.getId(), OAuthProvider.KAKAO, subject));
    }
    HttpResponse<Void> start =
        flowClient.send(
            HttpRequest.newBuilder(backend("/oauth2/authorization/kakao"))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(start.statusCode()).isEqualTo(302);
    HttpResponse<Void> authorization =
        http.send(
            HttpRequest.newBuilder(URI.create(start.headers().firstValue("Location").orElseThrow()))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(authorization.statusCode()).isEqualTo(302);
    URI callback = URI.create(authorization.headers().firstValue("Location").orElseThrow());
    return new PreparedCallback(backend(callback.getRawPath() + "?" + callback.getRawQuery()));
  }

  private CapturedSuccess completeWithBrowserCallback(HttpClient browser, User user, String subject)
      throws Exception {
    PreparedCallback callback = prepareCallback(browser, user, subject);
    HttpResponse<String> response =
        browser.send(
            HttpRequest.newBuilder(callback.callback).timeout(HTTP_TIMEOUT).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertSuccessResponse(response);
    LoginGrant grant = Objects.requireNonNull(issued.poll(), "expected login grant");
    return new CapturedSuccess(user, grant, response);
  }

  private void assertRelayParity(HttpResponse<byte[]> upstream, HttpResponse<byte[]> delayed) {
    assertThat(delayed.statusCode()).isEqualTo(upstream.statusCode());
    assertThat(delayed.headers().firstValue(HttpHeaders.LOCATION))
        .contains("http://localhost:5173/auth/kakao/callback?result=AUTHENTICATED");
    assertThat(
            delayed
                .headers()
                .allValues(HttpHeaders.LOCATION)
                .equals(upstream.headers().allValues(HttpHeaders.LOCATION)))
        .isTrue();
    assertThat(delayed.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    assertThat(
            delayed
                .headers()
                .allValues(HttpHeaders.SET_COOKIE)
                .equals(upstream.headers().allValues(HttpHeaders.SET_COOKIE)))
        .isTrue();
    assertThat(
            delayed
                .headers()
                .allValues(HttpHeaders.CACHE_CONTROL)
                .equals(upstream.headers().allValues(HttpHeaders.CACHE_CONTROL)))
        .isTrue();
    assertThat(
            delayed
                .headers()
                .allValues(HttpHeaders.PRAGMA)
                .equals(upstream.headers().allValues(HttpHeaders.PRAGMA)))
        .isTrue();
    assertThat(Arrays.equals(delayed.body(), upstream.body())).isTrue();
  }

  private void observeUnselectedFamily(String scenario, LoginGrant grant, User user) {
    boolean active = sessions.findActive(grant.session().id(), user.getUuid()).isPresent();
    System.out.println("R2 observation: case=" + scenario + "; unselectedFamilyActive=" + active);
  }

  private User registeredUser() {
    return users.saveAndFlush(
        User.create("순서" + UUID.randomUUID().toString().substring(0, 6), null));
  }

  private HttpClient browser(CookieManager cookies) {
    HttpClient.Builder builder =
        HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER);
    if (cookies != null) builder.cookieHandler(cookies);
    HttpClient client = builder.build();
    clients.add(client);
    return client;
  }

  private String csrfToken(HttpClient client) throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(backend("/auth/csrf")).timeout(HTTP_TIMEOUT).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return json.readTree(response.body()).required("token").textValue();
  }

  private RefreshResult refresh(CookieManager cookies, String csrfToken) throws Exception {
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(backend("/auth/refresh"))
                .timeout(HTTP_TIMEOUT)
                .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrfToken)
                .header(HttpHeaders.COOKIE, cookieHeader(cookies))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 200) {
      assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
      List<String> refreshCookies =
          response.headers().allValues(HttpHeaders.SET_COOKIE).stream()
              .filter(header -> header.startsWith("dev-refresh="))
              .toList();
      assertThat(
              refreshCookies.size() == 1
                  && refreshCookies.stream().allMatch(this::isSafeRefreshCookie))
          .isTrue();
      apply(cookies, response);
      assertThat(refreshCookieMatches(cookies, response)).isTrue();
    }
    JsonNode body = json.readTree(response.body());
    return new RefreshResult(response, body.path("accessToken").asText());
  }

  private HttpResponse<String> profile(String accessToken) throws Exception {
    return http.send(
        HttpRequest.newBuilder(backend("/users/me/profile"))
            .timeout(HTTP_TIMEOUT)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private void assertSuccessResponse(HttpResponse<?> response) {
    assertThat(response.statusCode()).isEqualTo(303);
    assertThat(response.headers().firstValue(HttpHeaders.LOCATION))
        .contains("http://localhost:5173/auth/kakao/callback?result=AUTHENTICATED");
    assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    List<String> setCookies = response.headers().allValues(HttpHeaders.SET_COOKIE);
    List<String> refreshCookies =
        setCookies.stream().filter(header -> header.startsWith("dev-refresh=")).toList();
    assertThat(refreshCookies.size()).isEqualTo(1);
    assertThat(isSafeRefreshCookie(refreshCookies.getFirst())).isTrue();
  }

  private boolean isSafeRefreshCookie(String header) {
    HttpCookie cookie = HttpCookie.parse(header).getFirst();
    return cookie.getName().equals("dev-refresh")
        && "/".equals(cookie.getPath())
        && cookie.getDomain() == null
        && cookie.isHttpOnly()
        && !cookie.getSecure()
        && cookie.getMaxAge() > 0
        && !cookie.getValue().isBlank()
        && List.of(header.split(";")).stream()
            .map(String::trim)
            .anyMatch(attribute -> attribute.equalsIgnoreCase("SameSite=Strict"));
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

  private boolean refreshCookieMatches(CookieManager cookies, HttpResponse<?> response) {
    return refreshCookieValue(cookies)
        .map(value -> value.equals(refreshCookieValue(response).orElse("")))
        .orElse(false);
  }

  private Optional<String> refreshCookieValue(CookieManager cookies) {
    return cookies.getCookieStore().getCookies().stream()
        .filter(cookie -> cookie.getName().equals("dev-refresh") && !cookie.getValue().isBlank())
        .map(HttpCookie::getValue)
        .findFirst();
  }

  private Optional<String> refreshCookieValue(HttpResponse<?> response) {
    return response.headers().allValues(HttpHeaders.SET_COOKIE).stream()
        .filter(header -> header.startsWith("dev-refresh="))
        .map(header -> header.substring("dev-refresh=".length(), header.indexOf(';')))
        .findFirst();
  }

  private String cookieHeader(CookieManager cookies) {
    if (cookies == null) {
      return "";
    }
    return cookies.getCookieStore().getCookies().stream()
        .filter(cookie -> !cookie.hasExpired())
        .map(cookie -> cookie.getName() + "=" + cookie.getValue())
        .reduce((first, second) -> first + "; " + second)
        .orElse("");
  }

  private URI backend(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private static LocalOidcProvider startProvider() {
    try {
      return LocalOidcProvider.start();
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private enum ModelCase {
    SAME_A_THEN_B(true, true),
    SAME_B_THEN_A(true, false),
    DIFFERENT_A_THEN_B(false, true),
    DIFFERENT_B_THEN_A(false, false);

    private final boolean sameAccount;
    private final boolean applyAFirst;

    ModelCase(boolean sameAccount, boolean applyAFirst) {
      this.sameAccount = sameAccount;
      this.applyAFirst = applyAFirst;
    }

    private CapturedSuccess applyFirst(CapturedSuccess first, CapturedSuccess second) {
      return applyAFirst ? first : second;
    }

    private CapturedSuccess applySecond(CapturedSuccess first, CapturedSuccess second) {
      return applyAFirst ? second : first;
    }
  }

  private static final class CallbackResponseRelay implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final HttpClient upstreamClient =
        HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final URI target;
    private final CountDownLatch captured = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final AtomicReference<HttpResponse<byte[]>> upstream = new AtomicReference<>();
    private final AtomicReference<Throwable> fixtureFailure = new AtomicReference<>();

    private CallbackResponseRelay(URI target) throws IOException {
      this.target = target;
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/", this::relay);
      server.start();
    }

    private URI uri() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/callback");
    }

    private boolean awaitCaptured() throws InterruptedException {
      return captured.await(10, TimeUnit.SECONDS);
    }

    private HttpResponse<byte[]> upstream() {
      Throwable failure = fixtureFailure.get();
      assertThat(failure == null ? null : failure.getClass().getSimpleName())
          .as("callback relay fixture failure")
          .isNull();
      return Objects.requireNonNull(upstream.get(), "expected captured callback response");
    }

    private void release() {
      released.countDown();
    }

    private void relay(HttpExchange exchange) throws IOException {
      try {
        HttpRequest.Builder request = HttpRequest.newBuilder(target).timeout(HTTP_TIMEOUT).GET();
        String cookie = exchange.getRequestHeaders().getFirst(HttpHeaders.COOKIE);
        if (cookie != null) {
          request.header(HttpHeaders.COOKIE, cookie);
        }
        HttpResponse<byte[]> response =
            upstreamClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        upstream.set(response);
        captured.countDown();
        if (!released.await(10, TimeUnit.SECONDS)) {
          throw new IOException("test callback relay was not released");
        }
        response
            .headers()
            .map()
            .forEach((name, values) -> {
              if (!name.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH)
                  && !name.equalsIgnoreCase("transfer-encoding")
                  && !name.equalsIgnoreCase("connection")) {
                values.forEach(value -> exchange.getResponseHeaders().add(name, value));
              }
            });
        exchange.sendResponseHeaders(response.statusCode(), response.body().length);
        exchange.getResponseBody().write(response.body());
      } catch (InterruptedException exception) {
        fixtureFailure.compareAndSet(null, exception);
        Thread.currentThread().interrupt();
        exchange.sendResponseHeaders(500, -1);
      } catch (Exception exception) {
        fixtureFailure.compareAndSet(null, exception);
        exchange.sendResponseHeaders(500, -1);
      } finally {
        captured.countDown();
        exchange.close();
      }
    }

    @Override
    public void close() {
      release();
      server.stop(0);
      executor.shutdownNow();
      upstreamClient.close();
    }
  }

  private record PreparedCallback(URI callback) {}

  private record CapturedSuccess(User user, LoginGrant grant, HttpResponse<String> response) {}

  private record RefreshResult(HttpResponse<String> response, String accessToken) {}
}

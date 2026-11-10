package org.kirikick.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.repository.OAuthIdentityRepository;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.auth.support.LocalOidcProvider;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.domain.UserStatus;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(OAuthSessionCookieRaceIntegrationTest.GateConfiguration.class)
class OAuthSessionCookieRaceIntegrationTest {

  private static final LocalOidcProvider OIDC = startProvider();
  @LocalServerPort private int port;

  @Autowired private UserRepository users;

  @Autowired private OAuthIdentityRepository identities;

  @Autowired private CookieSerializer onboardingCookieSerializer;

  @Autowired private SessionRepository<?> sessionRepository;

  @Autowired private LateTerminalResponseGate gate;

  @Autowired private ObjectMapper json;

  @Autowired private SessionRepositoryFilter<?> sessionRepositoryFilter;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    OIDC.authProperties().forEach((name, value) -> registry.add(name, () -> value));
    registry.add("spring.datasource.url", PostgresTestDatabase.container()::getJdbcUrl);
    registry.add("spring.datasource.username", PostgresTestDatabase.container()::getUsername);
    registry.add("spring.datasource.password", PostgresTestDatabase.container()::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @AfterAll
  static void closeOidc() {
    OIDC.close();
  }

  @Test
  @DisplayName("기본 Session resolver와 응답 commit 이후 Session 생성 차단을 유지한다")
  void retainsDefaultSessionResolverAndPostCommitProtection()
      throws IOException, InterruptedException {
    assertThat(ReflectionTestUtils.getField(sessionRepositoryFilter, "httpSessionIdResolver"))
        .isInstanceOf(CookieHttpSessionIdResolver.class);

    HttpResponse<Void> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/__test/post-commit-session"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(gate.postCommitSessionCreationWasRejected()).isTrue();
    assertThat(gate.postCommitSessionCreationFailureMessage())
        .isEqualTo("Cannot create a session after the response has been committed");
  }

  @Test
  @DisplayName("이전 흐름의 늦은 종료 응답은 새 onboarding 쿠키를 삭제하지 않는다")
  void delayedTerminalResponseDoesNotDeleteNewerOnboardingCookie() throws Exception {
    OIDC.clearCapturedRequests();
    String subject = "inactive-race-" + UUID.randomUUID();
    User member = users.saveAndFlush(User.create("비활성회원", null));
    ReflectionTestUtils.setField(member, "status", UserStatus.SUSPENDED);
    users.saveAndFlush(member);
    identities.saveAndFlush(OAuthIdentity.create(member.getId(), OAuthProvider.KAKAO, subject));

    HttpClient browser =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    StartedFlow first = startFlow(browser);
    String firstSessionId = sessionId(first.cookieValue());
    OIDC.nextTokenSubject(subject);
    gate.arm(first.callback().getRawPath(), firstSessionId);

    CompletableFuture<HttpResponse<Void>> firstCallback =
        CompletableFuture.supplyAsync(() -> sendCallback(browser, first.callback()));
    boolean released = false;
    try {
      assertThat(gate.awaitPostHandler(Duration.ofSeconds(10))).isTrue();
      assertThat(gate.sessionWasInvalidated()).isTrue();
      assertThat(gate.responseWasUncommitted()).isTrue();
      assertThat(firstCallback.isDone()).isFalse();

      String secondSubject = "new-race-" + UUID.randomUUID();
      OIDC.nextTokenSubject(secondSubject);
      StartedFlow second = startFlow(browser);
      String secondSessionId = sessionId(second.cookieValue());
      assertThat(secondSessionId).isNotEqualTo(firstSessionId);
      assertThat(sessionRepository.findById(secondSessionId)).isNotNull();

      HttpResponse<Void> secondCallback = sendCallback(browser, second.callback());
      assertThat(secondCallback.statusCode()).isEqualTo(303);
      assertThat(secondCallback.headers().firstValue("Location").orElseThrow())
          .isEqualTo("http://localhost:5173/auth/kakao/callback?result=SIGNUP_REQUIRED");
      String proofSessionId = sessionId(onboardingCookieValue(browser));
      assertThat(sessionRepository.findById(proofSessionId)).isNotNull();
      assertThat(signupContext(browser).statusCode()).isEqualTo(200);

      gate.release();
      released = true;
      HttpResponse<Void> terminalResponse = firstCallback.get(10, TimeUnit.SECONDS);
      assertThat(terminalResponse.statusCode()).isEqualTo(303);
      assertThat(terminalResponse.headers().firstValue("Location").orElseThrow())
          .isEqualTo(
              "http://localhost:5173/auth/kakao/callback?result=ERROR&errorCode=OAUTH_FAILED");

      boolean deletesOnboardingCookie =
          terminalResponse.headers().allValues("Set-Cookie").stream()
              .anyMatch(
                  value -> value.startsWith("dev-onboarding=") && value.contains("Max-Age=0"));
      assertThat(deletesOnboardingCookie).isFalse();
      assertThat(hasOnboardingCookie(browser)).isTrue();
      assertThat(signupContext(browser).statusCode()).isEqualTo(200);
      assertInvalidSignupProof(signupContextWithCookie(first.cookieValue()));
      assertThat(sessionRepository.findById(proofSessionId)).isNotNull();

      HttpClient residualOldCookieBrowser = browserWithCookie(first.cookieValue());
      assertInvalidSignupProof(signupContext(residualOldCookieBrowser));
      String recoverySubject = "recovery-race-" + UUID.randomUUID();
      OIDC.nextTokenSubject(recoverySubject);
      StartedFlow recovery = startFlow(residualOldCookieBrowser);
      assertThat(recovery.cookieValue().equals(first.cookieValue())).isFalse();
      assertThat(sessionRepository.findById(sessionId(recovery.cookieValue()))).isNotNull();
      HttpResponse<Void> recoveryCallback =
          sendCallback(residualOldCookieBrowser, recovery.callback());
      assertThat(recoveryCallback.statusCode()).isEqualTo(303);
      assertThat(recoveryCallback.headers().firstValue("Location").orElseThrow())
          .isEqualTo("http://localhost:5173/auth/kakao/callback?result=SIGNUP_REQUIRED");
      assertThat(signupContext(residualOldCookieBrowser).statusCode()).isEqualTo(200);
    } finally {
      if (!released) {
        gate.release();
        firstCallback.get(10, TimeUnit.SECONDS);
      }
    }
  }

  private StartedFlow startFlow(HttpClient browser) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/oauth2/authorization/kakao"))
            .GET();
    HttpResponse<Void> start =
        browser.send(request.build(), HttpResponse.BodyHandlers.discarding());
    assertThat(start.statusCode()).isEqualTo(302);
    String cookie = cookieValue(start, "dev-onboarding");
    assertThat(cookie).isNotNull();

    HttpResponse<Void> authorization =
        browser.send(
            HttpRequest.newBuilder(URI.create(start.headers().firstValue("Location").orElseThrow()))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(authorization.statusCode()).isEqualTo(302);
    return new StartedFlow(
        cookie, URI.create(authorization.headers().firstValue("Location").orElseThrow()));
  }

  private HttpResponse<Void> sendCallback(HttpClient browser, URI callback) {
    try {
      URI target =
          URI.create(
              "http://127.0.0.1:" + port + callback.getRawPath() + "?" + callback.getRawQuery());
      return browser.send(
          HttpRequest.newBuilder(target).GET().build(), HttpResponse.BodyHandlers.discarding());
    } catch (IOException | InterruptedException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private HttpResponse<String> signupContext(HttpClient browser)
      throws IOException, InterruptedException {
    return browser.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/auth/signup/context"))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> signupContextWithCookie(String cookie)
      throws IOException, InterruptedException {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/auth/signup/context"))
                .header("Cookie", "dev-onboarding=" + cookie)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private void assertInvalidSignupProof(HttpResponse<String> response) throws IOException {
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo(AuthErrorCode.INVALID_SIGNUP_PROOF.name());
  }

  private static HttpClient browserWithCookie(String cookieValue) {
    CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    cookieManager
        .getCookieStore()
        .add(URI.create("http://127.0.0.1"), new HttpCookie("dev-onboarding", cookieValue));
    return HttpClient.newBuilder().cookieHandler(cookieManager).build();
  }

  private static boolean hasOnboardingCookie(HttpClient browser) {
    return cookieManager(browser).getCookieStore().getCookies().stream()
        .anyMatch(cookie -> cookie.getName().equals("dev-onboarding"));
  }

  private static String onboardingCookieValue(HttpClient browser) {
    return cookieManager(browser).getCookieStore().getCookies().stream()
        .filter(cookie -> cookie.getName().equals("dev-onboarding"))
        .map(HttpCookie::getValue)
        .findFirst()
        .orElseThrow();
  }

  private static CookieManager cookieManager(HttpClient browser) {
    return (CookieManager) browser.cookieHandler().orElseThrow();
  }

  private String sessionId(String cookieValue) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("dev-onboarding", cookieValue));
    return onboardingCookieSerializer.readCookieValues(request).getFirst();
  }

  private static String cookieValue(HttpResponse<?> response, String name) {
    String prefix = name + "=";
    return response.headers().allValues("Set-Cookie").stream()
        .filter(value -> value.startsWith(prefix))
        .map(value -> value.substring(prefix.length()).split(";", 2)[0])
        .findFirst()
        .orElse(null);
  }

  private static LocalOidcProvider startProvider() {
    try {
      return LocalOidcProvider.start();
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private record StartedFlow(String cookieValue, URI callback) {}

  @TestConfiguration(proxyBeanMethods = false)
  static class GateConfiguration {

    @Bean
    LateTerminalResponseGate lateTerminalResponseGate(SessionRepository<?> sessionRepository) {
      return new LateTerminalResponseGate(sessionRepository);
    }

    @Bean
    FilterRegistrationBean<LateTerminalResponseGate> lateTerminalResponseGateRegistration(
        LateTerminalResponseGate gate) {
      FilterRegistrationBean<LateTerminalResponseGate> registration =
          new FilterRegistrationBean<>(gate);
      registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER + 1);
      return registration;
    }
  }

  static class LateTerminalResponseGate implements Filter {

    private final SessionRepository<?> sessionRepository;
    private final AtomicBoolean armed = new AtomicBoolean();
    private volatile String callbackPath;
    private volatile String sessionId;
    private volatile CountDownLatch postHandler = new CountDownLatch(1);
    private volatile CountDownLatch release = new CountDownLatch(1);
    private volatile boolean sessionWasInvalidated;
    private volatile boolean responseWasUncommitted;
    private volatile boolean postCommitSessionCreationWasRejected;
    private volatile String postCommitSessionCreationFailureMessage;

    LateTerminalResponseGate(SessionRepository<?> sessionRepository) {
      this.sessionRepository = sessionRepository;
    }

    void arm(String callbackPath, String sessionId) {
      this.callbackPath = callbackPath;
      this.sessionId = sessionId;
      postHandler = new CountDownLatch(1);
      release = new CountDownLatch(1);
      armed.set(true);
    }

    boolean awaitPostHandler(Duration timeout) throws InterruptedException {
      return postHandler.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    void release() {
      release.countDown();
    }

    boolean sessionWasInvalidated() {
      return sessionWasInvalidated;
    }

    boolean responseWasUncommitted() {
      return responseWasUncommitted;
    }

    boolean postCommitSessionCreationWasRejected() {
      return postCommitSessionCreationWasRejected;
    }

    String postCommitSessionCreationFailureMessage() {
      return postCommitSessionCreationFailureMessage;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
      HttpServletRequest httpRequest = (HttpServletRequest) request;
      if (httpRequest.getRequestURI().equals("/__test/post-commit-session")) {
        ((HttpServletResponse) response).flushBuffer();
        try {
          httpRequest.getSession(true);
          postCommitSessionCreationWasRejected = false;
        } catch (IllegalStateException exception) {
          postCommitSessionCreationWasRejected = true;
          postCommitSessionCreationFailureMessage = exception.getMessage();
        }
        return;
      }
      if (!httpRequest.getRequestURI().equals(callbackPath) || !armed.compareAndSet(true, false)) {
        chain.doFilter(request, response);
        return;
      }

      chain.doFilter(request, response);
      sessionWasInvalidated = sessionRepository.findById(sessionId) == null;
      responseWasUncommitted = !((HttpServletResponse) response).isCommitted();
      postHandler.countDown();
      try {
        if (!release.await(10, TimeUnit.SECONDS)) {
          throw new ServletException("test response gate was not released");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new ServletException(exception);
      }
    }
  }
}

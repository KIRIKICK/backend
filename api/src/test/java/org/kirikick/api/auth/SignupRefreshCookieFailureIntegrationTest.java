package org.kirikick.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.controller.AuthHttpSupport;
import org.kirikick.api.auth.domain.IssuedSession;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.infra.RedisFlowClaimGuard;
import org.kirikick.api.auth.infra.RedisRefreshSessionStore;
import org.kirikick.api.auth.infra.SessionStateUnavailableException;
import org.kirikick.api.auth.onboarding.OnboardingProof;
import org.kirikick.api.auth.repository.OAuthIdentityRepository;
import org.kirikick.api.auth.repository.SignupApplicationRepository;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.auth.support.LocalOidcProvider;
import org.kirikick.api.user.domain.UserStatus;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SignupRefreshCookieFailureIntegrationTest {
  private static final LocalOidcProvider OIDC = startProvider();

  @LocalServerPort private int port;
  @Autowired private ObjectMapper json;
  @Autowired private SignupApplicationRepository applications;
  @Autowired private OAuthIdentityRepository identities;
  @Autowired private UserRepository users;
  @Autowired private SessionRepository<?> sessionRepository;
  @Autowired private CookieSerializer onboardingCookieSerializer;
  @MockitoSpyBean private RedisRefreshSessionStore sessions;
  @MockitoSpyBean private RedisFlowClaimGuard claims;
  @MockitoSpyBean private AuthHttpSupport http;

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
  void resetFaults() {
    OIDC.clearCapturedRequests();
    reset(sessions, claims, http);
  }

  @AfterAll
  static void closeOidc() {
    OIDC.close();
  }

  @Test
  @DisplayName("grant 반환 전 불투명한 발급 실패는 refresh 쿠키 없이 error dispatch로 끝난다")
  void issueFailureBeforePersistenceDoesNotWriteRefresh() throws Exception {
    SignupAttempt attempt = signupAttempt();
    willThrow(new SessionStateUnavailableException(new RuntimeException("test issue failure")))
        .given(sessions)
        .issue(anyLong(), any(UUID.class));

    HttpResponse<String> response = signup(attempt);

    assertAuthenticationRequired(response);
    assertThat(response.headers().allValues("Set-Cookie").isEmpty()).isTrue();
    assertCompletedSignup(attempt, false);
    then(sessions).should().issue(anyLong(), any(UUID.class));
    then(http).should(never()).writeRefresh(any(), any());
    then(http).should(never()).clearRefresh(any());
  }

  @Test
  @DisplayName("불투명한 가입 완료 표식 응답 유실은 grant만 폐기하고 refresh 쿠키를 변경하지 않는다")
  void opaqueCompletedFinishAcknowledgementLossReachesTheErrorDispatch() throws Exception {
    SignupAttempt attempt = signupAttempt();
    AtomicReference<IssuedSession> issued = captureIssuedSession();
    loseFinishAcknowledgement(new RuntimeException("test acknowledgement loss"));

    HttpResponse<String> response = signup(attempt);

    assertAuthenticationRequired(response);
    assertRevokedBeforeRefreshWrite(response, issued.get());
    assertCompletedSignup(attempt, true);
  }

  @Test
  @DisplayName("Redis 연결 오류로 분류된 완료 표식 응답 유실은 쿠키 변경 없이 MVC 503을 반환한다")
  void connectionFailureAfterCompletedFinishRevokesRefreshBeforeItCanBeWritten() throws Exception {
    SignupAttempt attempt = signupAttempt();
    AtomicReference<IssuedSession> issued = captureIssuedSession();
    loseFinishAcknowledgement(new RedisConnectionFailureException("test acknowledgement loss"));

    HttpResponse<String> response = signup(attempt);

    assertUnavailable(response);
    assertRevokedBeforeRefreshWrite(response, issued.get());
    assertCompletedSignup(attempt, true);
  }

  @Test
  @DisplayName("늦은 가입 실패 응답 이후에도 새 OAuth의 refresh 쿠키와 갱신 권한은 유지된다")
  void delayedSignupFailurePreservesNewerRefreshCookieAndItsGrant() throws Exception {
    SignupAttempt attempt = signupAttempt();
    AtomicReference<IssuedSession> issued = captureIssuedSession();
    loseFinishAcknowledgement(new RedisConnectionFailureException("test acknowledgement loss"));

    try (SignupResponseRelay relay = new SignupResponseRelay(backend("/auth/signup"))) {
      CountDownLatch receivedHeaders = new CountDownLatch(1);
      CompletableFuture<HttpResponse<String>> delayed =
          attempt
              .browser()
              .sendAsync(
                  signupRequest(attempt, relay.uri()),
                  info -> {
                    receivedHeaders.countDown();
                    return HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
                  });
      assertThat(relay.received.await(10, TimeUnit.SECONDS)).isTrue();
      HttpResponse<String> upstream = relay.upstream.get();
      assertThat(upstream).isNotNull();
      assertUnavailable(upstream);
      IssuedSession first = issued.get();
      assertRevokedBeforeRefreshWrite(upstream, first);
      assertCompletedSignup(attempt, true);
      assertThat(delayed.isDone()).isFalse();
      assertThat(receivedHeaders.getCount()).isEqualTo(1);

      HttpResponse<Void> login = oauth(attempt.browser(), attempt.subject());
      assertThat(login.statusCode()).isEqualTo(303);
      assertThat(login.headers().firstValue("Location"))
          .contains("http://localhost:5173/auth/kakao/callback?result=AUTHENTICATED");
      IssuedSession second = issued.get();
      assertThat(second.session().id().equals(first.session().id())).isFalse();
      assertThat(hasRefreshCookie(attempt.cookies())).isTrue();
      assertThat(sessions.findActive(second.session().id(), second.session().userUuid()))
          .isPresent();
      assertThat(delayed.isDone()).isFalse();

      HttpResponse<String> csrf =
          attempt
              .browser()
              .send(
                  HttpRequest.newBuilder(backend("/auth/csrf")).GET().build(),
                  HttpResponse.BodyHandlers.ofString());
      assertThat(csrf.statusCode()).isEqualTo(200);
      String csrfToken = json.readTree(csrf.body()).required("token").textValue();

      assertThat(refresh(attempt.browser(), csrfToken).statusCode()).isEqualTo(200);
      assertThat(hasRefreshCookie(attempt.cookies())).isTrue();
      String refreshBeforeFailureResponse = refreshCookieValue(attempt.cookies());
      assertThat(sessions.findActive(first.session().id(), first.session().userUuid())).isEmpty();
      assertThat(sessions.findActive(second.session().id(), second.session().userUuid()))
          .isPresent();
      assertThat(receivedHeaders.getCount()).isEqualTo(1);

      relay.release.countDown();
      HttpResponse<String> response = delayed.get(10, TimeUnit.SECONDS);
      assertThat(receivedHeaders.getCount()).isZero();
      assertThat(response.statusCode()).isEqualTo(upstream.statusCode());
      assertThat(response.body()).isEqualTo(upstream.body());
      for (String name : List.of("Set-Cookie", "Content-Type", "Cache-Control", "Pragma")) {
        assertThat(response.headers().allValues(name).equals(upstream.headers().allValues(name)))
            .as("relay preserves %s", name)
            .isTrue();
      }
      assertThat(hasRefreshCookie(attempt.cookies())).isTrue();
      assertThat(refreshBeforeFailureResponse.equals(refreshCookieValue(attempt.cookies())))
          .isTrue();
      assertThat(sessions.findActive(first.session().id(), first.session().userUuid())).isEmpty();
      assertThat(sessions.findActive(second.session().id(), second.session().userUuid()))
          .isPresent();

      HttpResponse<String> refresh = refresh(attempt.browser(), csrfToken);
      assertThat(refresh.statusCode()).isEqualTo(200);
      assertThat(hasRefreshCookie(attempt.cookies())).isTrue();
      assertThat(sessions.findActive(first.session().id(), first.session().userUuid())).isEmpty();
      assertThat(sessions.findActive(second.session().id(), second.session().userUuid()))
          .isPresent();
    }
  }

  @Test
  @DisplayName("다른 계정의 가입이 발급 시도 전에 실패해도 기존 로그인 쿠키와 갱신 권한은 유지된다")
  void preWriteSignupFailurePreservesExistingLoginForAnotherAccount() throws Exception {
    SignupAttempt existing = signupAttempt();
    AtomicReference<IssuedSession> existingIssued = captureIssuedSession();
    HttpResponse<String> completed =
        existing
            .browser()
            .send(
                signupRequest(existing, backend("/auth/signup")),
                HttpResponse.BodyHandlers.ofString());
    assertThat(completed.statusCode()).isEqualTo(201);
    IssuedSession previous = existingIssued.get();
    String previousCookie = refreshCookieValue(existing.cookies());
    reset(sessions, claims, http);

    SignupAttempt attempt = signupAttempt(existing.browser(), existing.cookies());
    AtomicReference<IssuedSession> issued = captureIssuedSession();
    loseFinishAcknowledgement(new RedisConnectionFailureException("test acknowledgement loss"));

    HttpResponse<String> response =
        attempt
            .browser()
            .send(
                signupRequest(attempt, backend("/auth/signup")),
                HttpResponse.BodyHandlers.ofString());

    assertUnavailable(response);
    IssuedSession failed = issued.get();
    assertRevokedBeforeRefreshWrite(response, failed);
    assertCompletedSignup(attempt, true);
    assertThat(previous.session().userUuid().equals(failed.session().userUuid())).isFalse();
    assertThat(previousCookie.equals(refreshCookieValue(attempt.cookies()))).isTrue();
    assertThat(sessions.findActive(previous.session().id(), previous.session().userUuid()))
        .isPresent();
    assertThat(refresh(attempt.browser(), attempt.csrfToken()).statusCode()).isEqualTo(200);
    assertThat(hasRefreshCookie(attempt.cookies())).isTrue();
    assertThat(sessions.findActive(previous.session().id(), previous.session().userUuid()))
        .isPresent();
    assertThat(sessions.findActive(failed.session().id(), failed.session().userUuid())).isEmpty();
  }

  private AtomicReference<IssuedSession> captureIssuedSession() {
    AtomicReference<IssuedSession> issued = new AtomicReference<>();
    willAnswer(
            invocation -> {
              IssuedSession result = (IssuedSession) invocation.callRealMethod();
              issued.set(result);
              return result;
            })
        .given(sessions)
        .issue(anyLong(), any(UUID.class));
    return issued;
  }

  private void loseFinishAcknowledgement(RuntimeException cause) {
    AtomicBoolean inject = new AtomicBoolean(true);
    willAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              // Real Redis FINISH_SIGNUP has ended the claim; only its return to the caller is
              // lost.
              if (inject.getAndSet(false)) {
                assertThat(result)
                    .isEqualTo(RedisFlowClaimGuard.FinishSignupResult.FINISHED_CLEANUP_PERMIT);
                throw new SessionStateUnavailableException(cause);
              }
              return result;
            })
        .given(claims)
        .finishSignup(
            any(UUID.class),
            any(UUID.class),
            any(Instant.class),
            any(UUID.class),
            any(Instant.class));
  }

  private void assertRevokedBeforeRefreshWrite(
      HttpResponse<String> response, IssuedSession issued) {
    assertThat(issued != null).isTrue();
    assertThat(sessions.findActive(issued.session().id(), issued.session().userUuid()).isEmpty())
        .isTrue();
    assertThat(
            response.headers().allValues("Set-Cookie").stream()
                .anyMatch(value -> value.startsWith("dev-refresh=")))
        .isFalse();
    then(sessions).should().issue(anyLong(), any(UUID.class));
    then(claims)
        .should()
        .finishSignup(
            any(UUID.class),
            any(UUID.class),
            any(Instant.class),
            any(UUID.class),
            any(Instant.class));
    then(http).should(never()).writeRefresh(any(), any());
    then(http).should(never()).clearRefresh(any());
  }

  private void assertCompletedSignup(SignupAttempt attempt, boolean sessionRetained) {
    var application = applications.findById(attempt.applicationId()).orElseThrow();
    assertThat(application.isCompleted()).isTrue();
    assertThat(users.findById(application.getCompletedUserId()).orElseThrow().getStatus())
        .isEqualTo(UserStatus.ACTIVE);
    assertThat(
            identities
                .findByProviderAndProviderSubject(OAuthProvider.KAKAO, attempt.subject())
                .orElseThrow()
                .getUserId()
                .equals(application.getCompletedUserId()))
        .isTrue();
    OnboardingProof proof = attempt.proof();
    assertThat(
            claims.inspectProof(
                proof.slotId(),
                proof.proofId(),
                proof.expiresAt(),
                proof.issuedAt().plusSeconds(1)))
        .isEqualTo(RedisFlowClaimGuard.InspectProofResult.STALE);
    assertThat(sessionRepository.findById(proof.sessionId()) != null).isEqualTo(sessionRetained);
  }

  private HttpResponse<String> refresh(HttpClient browser, String csrfToken)
      throws IOException, InterruptedException {
    return browser.send(
        HttpRequest.newBuilder(backend("/auth/refresh"))
            .header("Origin", "http://localhost:5173")
            .header("X-XSRF-TOKEN", csrfToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static boolean hasRefreshCookie(CookieManager cookies) {
    return cookies.getCookieStore().getCookies().stream()
        .anyMatch(cookie -> cookie.getName().equals("dev-refresh") && !cookie.getValue().isEmpty());
  }

  private static String refreshCookieValue(CookieManager cookies) {
    return cookies.getCookieStore().getCookies().stream()
        .filter(cookie -> cookie.getName().equals("dev-refresh") && !cookie.getValue().isEmpty())
        .map(HttpCookie::getValue)
        .findFirst()
        .orElseThrow();
  }

  private SignupAttempt signupAttempt() throws Exception {
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    HttpClient browser = HttpClient.newBuilder().cookieHandler(cookies).build();
    return signupAttempt(browser, cookies);
  }

  private SignupAttempt signupAttempt(HttpClient browser, CookieManager cookies) throws Exception {
    String subject = "signup-refresh-failure-" + UUID.randomUUID();
    HttpResponse<Void> completed = oauth(browser, subject);
    assertThat(completed.statusCode()).isEqualTo(303);
    HttpResponse<String> context =
        browser.send(
            HttpRequest.newBuilder(backend("/auth/signup/context")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(context.statusCode()).isEqualTo(200);
    UUID applicationId =
        UUID.fromString(json.readTree(context.body()).required("signupApplicationId").textValue());
    HttpResponse<String> csrf =
        browser.send(
            HttpRequest.newBuilder(backend("/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(csrf.statusCode()).isEqualTo(200);
    assertThat(cookies.getCookieStore().getCookies())
        .extracting(HttpCookie::getName)
        .contains("dev-onboarding", "dev-csrf");
    HttpCookie onboarding =
        cookies.getCookieStore().getCookies().stream()
            .filter(cookie -> cookie.getName().equals("dev-onboarding"))
            .findFirst()
            .orElseThrow();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("dev-onboarding", onboarding.getValue()));
    String sessionId = onboardingCookieSerializer.readCookieValues(request).getFirst();
    Session session = sessionRepository.findById(sessionId);
    assertThat(session != null).isTrue();
    OnboardingProof proof =
        session.getAttributeNames().stream()
            .map(session::getAttribute)
            .filter(OnboardingProof.class::isInstance)
            .map(OnboardingProof.class::cast)
            .findFirst()
            .orElseThrow();
    return new SignupAttempt(
        browser,
        cookies,
        applicationId,
        json.readTree(csrf.body()).required("token").textValue(),
        subject,
        proof);
  }

  private HttpResponse<Void> oauth(HttpClient browser, String subject) throws Exception {
    OIDC.nextTokenSubject(subject);
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

  private HttpResponse<String> signup(SignupAttempt attempt)
      throws IOException, InterruptedException {
    return HttpClient.newHttpClient()
        .send(
            signupRequest(attempt, backend("/auth/signup")), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest signupRequest(SignupAttempt attempt, URI target) {
    return HttpRequest.newBuilder(target)
        .header("Cookie", cookieHeader(attempt.cookies()))
        .header("Origin", "http://localhost:5173")
        .header("X-XSRF-TOKEN", attempt.csrfToken())
        .header("Content-Type", "application/json")
        .POST(
            HttpRequest.BodyPublishers.ofString(
                """
                {"signupApplicationId":"%s","displayName":"가입검증","consents":{"service":true,"privacy":true,"ageConfirmed":true}}
                """
                    .formatted(attempt.applicationId())))
        .build();
  }

  private void assertUnavailable(HttpResponse<String> response) throws IOException {
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo(AuthErrorCode.AUTH_STATE_UNAVAILABLE.name());
  }

  private void assertAuthenticationRequired(HttpResponse<String> response) throws IOException {
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo(AuthErrorCode.AUTHENTICATION_REQUIRED.name());
  }

  private URI backend(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private String cookieHeader(CookieManager cookies) {
    return cookies.getCookieStore().getCookies().stream()
        .map(cookie -> cookie.getName() + "=" + cookie.getValue())
        .collect(Collectors.joining("; "));
  }

  private static LocalOidcProvider startProvider() {
    try {
      return LocalOidcProvider.start();
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private record SignupAttempt(
      HttpClient browser,
      CookieManager cookies,
      UUID applicationId,
      String csrfToken,
      String subject,
      OnboardingProof proof) {}

  private static final class SignupResponseRelay implements AutoCloseable {
    private final HttpServer server;
    private final CountDownLatch received = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicReference<HttpResponse<String>> upstream = new AtomicReference<>();

    private SignupResponseRelay(URI target) throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/auth/signup",
          exchange -> {
            try (exchange) {
              HttpRequest.Builder request = HttpRequest.newBuilder(target);
              for (String name : List.of("Cookie", "Origin", "X-XSRF-TOKEN", "Content-Type")) {
                exchange
                    .getRequestHeaders()
                    .getOrDefault(name, List.of())
                    .forEach(value -> request.header(name, value));
              }
              HttpResponse<String> response =
                  HttpClient.newHttpClient()
                      .send(
                          request
                              .POST(
                                  HttpRequest.BodyPublishers.ofByteArray(
                                      exchange.getRequestBody().readAllBytes()))
                              .build(),
                          HttpResponse.BodyHandlers.ofString());
              upstream.set(response);
              received.countDown();
              // Hold the first downstream headers, not merely the response body.
              if (!release.await(15, TimeUnit.SECONDS)) {
                throw new IOException("timed out waiting to deliver signup response headers");
              }
              for (String name : List.of("Set-Cookie", "Content-Type", "Cache-Control", "Pragma")) {
                response
                    .headers()
                    .allValues(name)
                    .forEach(value -> exchange.getResponseHeaders().add(name, value));
              }
              byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(response.statusCode(), body.length);
              exchange.getResponseBody().write(body);
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
              throw new IOException(exception);
            } finally {
              received.countDown();
            }
          });
      server.start();
    }

    private URI uri() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/auth/signup");
    }

    @Override
    public void close() {
      release.countDown();
      server.stop(0);
    }
  }
}

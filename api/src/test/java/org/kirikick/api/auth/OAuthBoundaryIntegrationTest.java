package org.kirikick.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.kirikick.api.ApiApplication;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.infra.RedisFlowClaimGuard;
import org.kirikick.api.auth.infra.RedisRefreshSessionStore;
import org.kirikick.api.auth.infra.SessionStateUnavailableException;
import org.kirikick.api.auth.oauth.OAuthFlowAuthorizationRequestRepository;
import org.kirikick.api.auth.onboarding.OnboardingProof;
import org.kirikick.api.auth.onboarding.OnboardingSessionProofs;
import org.kirikick.api.auth.repository.OAuthIdentityRepository;
import org.kirikick.api.auth.repository.SignupApplicationRepository;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.service.SignupApplicationPublicationService;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.auth.support.LocalOidcProvider;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.config.AuthProperties;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.domain.UserStatus;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthBoundaryIntegrationTest {

  private static final LocalOidcProvider OIDC = startProvider();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Autowired private MockMvc mvc;
  @Autowired private UserRepository users;
  @Autowired private OAuthIdentityRepository identities;

  @Autowired private SignupApplicationRepository applications;
  @Autowired private ObjectMapper json;
  @Autowired private Environment environment;
  @Autowired private AuthProperties authProperties;
  @MockitoSpyBean private RedisFlowClaimGuard flowGuard;
  @MockitoSpyBean private RedisRefreshSessionStore sessionStore;
  @MockitoSpyBean private SignupApplicationPublicationService publications;
  @MockitoSpyBean private LoginSessionService loginSessions;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private CookieSerializer onboardingCookieSerializer;
  @Autowired private SessionRepository<? extends Session> sessionRepository;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    OIDC.authProperties().forEach((name, value) -> registry.add(name, () -> value));
    registry.add("spring.datasource.url", () -> PostgresTestDatabase.container().getJdbcUrl());
    registry.add(
        "spring.datasource.username", () -> PostgresTestDatabase.container().getUsername());
    registry.add(
        "spring.datasource.password", () -> PostgresTestDatabase.container().getPassword());
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @AfterAll
  static void closeProvider() {
    OIDC.close();
  }

  @Test
  void unknownSubjectCompletesTheRealOidcCallbackAsSignupRequiredWithoutMembershipOrServiceAuth()
      throws Exception {
    long membersBefore = users.count();
    long applicationsBefore = applications.count();
    OIDC.clearCapturedRequests();
    String subject = "unknown-" + UUID.randomUUID();
    OIDC.nextTokenSubject(subject);
    MvcResult start = mvc.perform(get("/oauth2/authorization/kakao")).andReturn();
    Cookie oldCookie = start.getResponse().getCookie("dev-onboarding");

    assertThat(start.getResponse().getStatus()).isEqualTo(302);
    assertThat(oldCookie).isNotNull();
    HttpResponse<Void> authorization =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(start.getResponse().getRedirectedUrl()))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());
    URI callback = URI.create(authorization.headers().firstValue("Location").orElseThrow());
    MvcResult result =
        mvc.perform(get(callback).cookie(oldCookie)).andExpect(status().isSeeOther()).andReturn();

    assertThat(OIDC.tokenRequest())
        .as("the callback must reach the local token endpoint")
        .isNotNull();
    assertThat(result.getResponse().getRedirectedUrl())
        .isEqualTo("http://localhost:5173/auth/kakao/callback?result=SIGNUP_REQUIRED");
    assertThat(result.getResponse().getCookie("dev-refresh")).isNull();
    assertThat(users.count()).isEqualTo(membersBefore);
    assertThat(applications.count()).isEqualTo(applicationsBefore + 1);
    assertThat(OIDC.authorization().state()).isNotBlank();
    assertThat(OIDC.authorization().nonce()).isNotBlank();
    assertThat(OIDC.authorization().codeChallengeMethod()).isEqualTo("S256");
    assertThat(OIDC.tokenRequest().clientSecretPost()).isTrue();
    assertThat(OIDC.tokenRequest().codeVerifier()).isNotBlank();

    Cookie rotatedCookie = result.getResponse().getCookie("dev-onboarding");
    assertThat(rotatedCookie).isNotNull();
    assertThat(rotatedCookie.getValue()).isNotEqualTo(oldCookie.getValue());
    UUID applicationId = signupApplicationId(rotatedCookie);
    assertThat(applications.findById(applicationId))
        .hasValueSatisfying(application -> {
          assertThat(application.getProvider()).isEqualTo(OAuthProvider.KAKAO);
          assertThat(application.getProviderSubject()).isEqualTo(subject);
          assertThat(application.isCompleted()).isFalse();
        });
    mvc.perform(get("/auth/signup/context").cookie(oldCookie)).andExpect(status().isUnauthorized());
  }

  @Test
  void activeIdentityCompletesTheRealOidcCallbackAndCanRefresh() throws Exception {
    OIDC.clearCapturedRequests();
    String subject = "active-" + UUID.randomUUID();
    User member = users.saveAndFlush(User.create("활성회원", null));
    identities.saveAndFlush(OAuthIdentity.create(member.getId(), OAuthProvider.KAKAO, subject));
    OIDC.nextTokenSubject(subject);

    StartedFlow flow = startFlow();
    MvcResult callback = complete(flow);

    assertThat(callback.getResponse().getRedirectedUrl())
        .isEqualTo("http://localhost:5173/auth/kakao/callback?result=AUTHENTICATED");
    Cookie refresh = callback.getResponse().getCookie("dev-refresh");
    assertThat(refresh).isNotNull();
    MvcResult csrf = mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
    Cookie csrfCookie = csrf.getResponse().getCookie("dev-csrf");
    String csrfToken =
        json.readTree(csrf.getResponse().getContentAsString()).required("token").textValue();
    mvc.perform(
            post("/auth/refresh")
                .cookie(refresh, csrfCookie)
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header("X-XSRF-TOKEN", csrfToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty());
  }

  @ParameterizedTest
  @EnumSource(
      value = UserStatus.class,
      names = {"WITHDRAWN", "SUSPENDED", "BANNED"})
  void inactiveIdentityPublishesNeitherSignupProofNorServiceAuthentication(UserStatus status)
      throws Exception {
    OIDC.clearCapturedRequests();
    String subject = "inactive-" + status + "-" + UUID.randomUUID();
    User member = users.saveAndFlush(User.create("비활성회원", null));
    ReflectionTestUtils.setField(member, "status", status);
    users.saveAndFlush(member);
    identities.saveAndFlush(OAuthIdentity.create(member.getId(), OAuthProvider.KAKAO, subject));
    OIDC.nextTokenSubject(subject);

    StartedFlow flow = startFlow();
    MvcResult callback = complete(flow);

    assertThat(callback.getResponse().getRedirectedUrl())
        .isEqualTo("http://localhost:5173/auth/kakao/callback?result=ERROR&errorCode=OAUTH_FAILED");
    assertThat(callback.getResponse().getCookie("dev-refresh")).isNull();
    Cookie deletedOnboarding = callback.getResponse().getCookie("dev-onboarding");
    assertThat(deletedOnboarding).isNotNull();
    assertThat(deletedOnboarding.getValue()).isEmpty();
    assertThat(deletedOnboarding.getMaxAge()).isZero();
    assertThat(deletedOnboarding.isHttpOnly()).isTrue();
    assertThat(deletedOnboarding.getPath()).isEqualTo("/");
    assertThat(deletedOnboarding.getDomain()).isNull();
    assertThat(deletedOnboarding.getAttribute("SameSite")).isEqualTo("Lax");
  }

  @Test
  void callbackWithWrongStateDoesNotReachTokenEndpointOrPublishAuthentication() throws Exception {
    OIDC.clearCapturedRequests();
    StartedFlow flow = startFlow();
    URI wrongState =
        UriComponentsBuilder.fromUri(flow.callback())
            .replaceQueryParam("state", "wrong-state")
            .build(true)
            .toUri();

    MvcResult callback =
        mvc.perform(get(wrongState).cookie(flow.cookie()))
            .andExpect(status().isSeeOther())
            .andReturn();

    assertThat(callback.getResponse().getRedirectedUrl())
        .isEqualTo("http://localhost:5173/auth/kakao/callback?result=ERROR&errorCode=OAUTH_FAILED");
    assertThat(OIDC.tokenRequest()).isNull();
    assertThat(callback.getResponse().getCookie("dev-refresh")).isNull();
  }

  @Test
  void callbackWithWrongOidcNonceReachesTheProviderButPublishesNoAuthentication() throws Exception {
    OIDC.clearCapturedRequests();
    OIDC.nextTokenFault(LocalOidcProvider.TokenFault.WRONG_NONCE);
    StartedFlow flow = startFlow();

    MvcResult callback = complete(flow);

    assertThat(callback.getResponse().getRedirectedUrl())
        .isEqualTo("http://localhost:5173/auth/kakao/callback?result=ERROR&errorCode=OAUTH_FAILED");
    assertThat(OIDC.tokenRequest()).isNotNull();
    assertThat(callback.getResponse().getCookie("dev-refresh")).isNull();
  }

  @Test
  void unknownCallbackCanCompleteExplicitSignupWithTheExactNestedConsentPayload() throws Exception {
    OIDC.clearCapturedRequests();
    OIDC.nextTokenSubject("signup-" + UUID.randomUUID());
    long membersBefore = users.count();
    MvcResult callback = complete(startFlow());
    Cookie onboarding = callback.getResponse().getCookie("dev-onboarding");
    UUID applicationId = signupApplicationId(onboarding);
    MvcResult csrf =
        mvc.perform(get("/auth/csrf").cookie(onboarding)).andExpect(status().isOk()).andReturn();
    Cookie csrfCookie = csrf.getResponse().getCookie("dev-csrf");
    String csrfToken =
        json.readTree(csrf.getResponse().getContentAsString()).required("token").textValue();

    MvcResult signup =
        mvc.perform(
                post("/auth/signup")
                    .cookie(onboarding, csrfCookie)
                    .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                    .header("X-XSRF-TOKEN", csrfToken)
                    .contentType("application/json")
                    .content(signupRequest(applicationId, "가입회원")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andReturn();

    assertThat(users.count()).isEqualTo(membersBefore + 1);
    assertThat(applications.findById(applicationId))
        .hasValueSatisfying(application -> assertThat(application.isCompleted()).isTrue());
    assertThat(signup.getResponse().getCookie("dev-refresh")).isNotNull();
    Cookie deletedOnboarding = signup.getResponse().getCookie("dev-onboarding");
    assertThat(deletedOnboarding).isNotNull();
    assertThat(deletedOnboarding.getMaxAge()).isZero();
  }

  @Test
  void unavailableApplicationPublicationReturnsUnavailableWithoutRedirectOrPendingRow()
      throws Exception {
    OIDC.clearCapturedRequests();
    OIDC.nextTokenSubject("publication-unavailable-" + UUID.randomUUID());
    long applicationsBefore = applications.count();
    doThrow(new DataAccessResourceFailureException("postgres unavailable"))
        .when(publications)
        .publish(any(UUID.class), any(OAuthProvider.class), any(String.class), any(Instant.class));
    try {
      StartedFlow flow = startFlow();
      MvcResult callback = mvc.perform(get(flow.callback()).cookie(flow.cookie())).andReturn();

      assertThat(callback.getResponse().getStatus()).isEqualTo(503);
      assertThat(callback.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
      assertThat(callback.getResponse().getHeader("Location")).isNull();
      assertThat(callback.getResponse().getCookie("dev-refresh")).isNull();
      assertThat(applications.count()).isEqualTo(applicationsBefore);
    } finally {
      reset(publications);
    }
  }

  @Test
  void semanticPublicationFailureIsNotNormalizedToAuthStateUnavailable() throws Exception {
    OIDC.clearCapturedRequests();
    OIDC.nextTokenSubject("publication-semantic-" + UUID.randomUUID());
    StartedFlow flow = startFlow();
    long applicationsBefore = applications.count();
    doThrow(new DataIntegrityViolationException("unexpected constraint"))
        .when(publications)
        .publish(any(UUID.class), any(OAuthProvider.class), any(String.class), any(Instant.class));
    try {
      assertThatThrownBy(() -> complete(flow)).isInstanceOf(DataIntegrityViolationException.class);
      assertThat(applications.count()).isEqualTo(applicationsBefore);
    } finally {
      reset(publications);
    }
  }

  @Test
  void signupIssuesTheLoginOnlyAfterTheCompletedUserAndIdentityAreCommitted() throws Exception {
    OIDC.clearCapturedRequests();
    String subject = "commit-visible-" + UUID.randomUUID();
    OIDC.nextTokenSubject(subject);
    MvcResult callback = complete(startFlow());
    Cookie onboarding = callback.getResponse().getCookie("dev-onboarding");
    assertThat(onboarding).isNotNull();
    UUID applicationId = signupApplicationId(onboarding);
    MvcResult csrf =
        mvc.perform(get("/auth/csrf").cookie(onboarding)).andExpect(status().isOk()).andReturn();
    Cookie csrfCookie = csrf.getResponse().getCookie("dev-csrf");
    String csrfToken =
        json.readTree(csrf.getResponse().getContentAsString()).required("token").textValue();

    doAnswer(
            invocation -> {
              User user = invocation.getArgument(0);
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              try (var connection = jdbc.getDataSource().getConnection();
                  var query =
                      connection.prepareStatement(
                          "select exists(select 1 from users where id = ?), "
                              + "exists(select 1 from oauth_identities where user_id = ? and provider_subject = ?), "
                              + "exists(select 1 from signup_applications where id = ? and completed_user_id = ?)")) {
                assertThat(connection.getAutoCommit()).isTrue();
                query.setLong(1, user.getId());
                query.setLong(2, user.getId());
                query.setString(3, subject);
                query.setObject(4, applicationId);
                query.setLong(5, user.getId());
                try (var result = query.executeQuery()) {
                  assertThat(result.next()).isTrue();
                  assertThat(result.getBoolean(1)).isTrue();
                  assertThat(result.getBoolean(2)).isTrue();
                  assertThat(result.getBoolean(3)).isTrue();
                }
              }
              return invocation.callRealMethod();
            })
        .when(loginSessions)
        .login(any(User.class));
    try {
      mvc.perform(
              post("/auth/signup")
                  .cookie(onboarding, csrfCookie)
                  .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                  .header("X-XSRF-TOKEN", csrfToken)
                  .contentType("application/json")
                  .content(signupRequest(applicationId, "커밋확인회원")))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.accessToken").isNotEmpty());
    } finally {
      reset(loginSessions);
    }
  }

  @Test
  void guessedSignupApplicationIdCannotCompleteThePendingApplication() throws Exception {
    OIDC.clearCapturedRequests();
    OIDC.nextTokenSubject("guessed-application-" + UUID.randomUUID());
    long membersBefore = users.count();
    MvcResult callback = complete(startFlow());
    Cookie onboarding = callback.getResponse().getCookie("dev-onboarding");
    assertThat(onboarding).isNotNull();
    UUID actualApplicationId = signupApplicationId(onboarding);
    MvcResult csrf =
        mvc.perform(get("/auth/csrf").cookie(onboarding)).andExpect(status().isOk()).andReturn();
    Cookie csrfCookie = csrf.getResponse().getCookie("dev-csrf");
    String csrfToken =
        json.readTree(csrf.getResponse().getContentAsString()).required("token").textValue();

    mvc.perform(
            post("/auth/signup")
                .cookie(onboarding, csrfCookie)
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header("X-XSRF-TOKEN", csrfToken)
                .contentType("application/json")
                .content(signupRequest(UUID.randomUUID(), "추측회원")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_SIGNUP_PROOF"));

    assertThat(users.count()).isEqualTo(membersBefore);
    assertThat(applications.findById(actualApplicationId))
        .hasValueSatisfying(application -> assertThat(application.isCompleted()).isFalse());
  }

  @Test
  void concurrentSignupPostsCreateOneMemberIdentityResultAndLoginGrant() throws Exception {
    OIDC.clearCapturedRequests();
    String subject = "concurrent-signup-" + UUID.randomUUID();
    OIDC.nextTokenSubject(subject);
    long membersBefore = users.count();
    long identitiesBefore = identities.count();
    MvcResult callback = complete(startFlow());
    Cookie onboarding = callback.getResponse().getCookie("dev-onboarding");
    assertThat(onboarding).isNotNull();
    UUID applicationId = signupApplicationId(onboarding);
    MvcResult csrf =
        mvc.perform(get("/auth/csrf").cookie(onboarding)).andExpect(status().isOk()).andReturn();
    Cookie csrfCookie = csrf.getResponse().getCookie("dev-csrf");
    String csrfToken =
        json.readTree(csrf.getResponse().getContentAsString()).required("token").textValue();
    CyclicBarrier barrier = new CyclicBarrier(2);

    CompletableFuture<MvcResult> first =
        CompletableFuture.supplyAsync(
            () -> concurrentSignup(barrier, onboarding, csrfCookie, csrfToken, applicationId));
    CompletableFuture<MvcResult> second =
        CompletableFuture.supplyAsync(
            () -> concurrentSignup(barrier, onboarding, csrfCookie, csrfToken, applicationId));
    MvcResult firstResult = first.get(10, TimeUnit.SECONDS);
    MvcResult secondResult = second.get(10, TimeUnit.SECONDS);

    assertThat(
            List.of(firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()))
        .containsExactlyInAnyOrder(201, 401);
    assertThat(users.count()).isEqualTo(membersBefore + 1);
    assertThat(identities.count()).isEqualTo(identitiesBefore + 1);
    assertThat(applications.findById(applicationId))
        .hasValueSatisfying(application -> assertThat(application.isCompleted()).isTrue());
    verify(loginSessions, atMost(1)).login(any(User.class));
  }

  @Test
  void oauthStartSessionWriteFailureReturnsUnavailableWithoutRedirectOrSignupApplication()
      throws Exception {
    long applicationsBefore = applications.count();
    long membersBefore = users.count();
    SessionRedisOperationsReplacement redisOperations = replaceSessionRedisOperations();
    doAnswer(
            invocation -> {
              if (isFlowDelta((Map<?, ?>) invocation.getArgument(1))) {
                throw new DataAccessResourceFailureException("redis unavailable");
              }
              return invocation.callRealMethod();
            })
        .when(redisOperations.proofHashOperations())
        .putAll(any(), any());
    try {
      MvcResult result = mvc.perform(get("/oauth2/authorization/kakao")).andReturn();

      assertThat(result.getResponse().getStatus()).isEqualTo(503);
      assertThat(result.getResponse().getHeader("Location")).isNull();
      assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
      assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
      assertThat(result.getResponse().getCookie("dev-refresh")).isNull();
      assertThat(applications.count()).isEqualTo(applicationsBefore);
      assertThat(users.count()).isEqualTo(membersBefore);
    } finally {
      redisOperations.restore();
    }
  }

  @Test
  void proofWriteFailureReturnsUnavailableWithoutPublishingASignupApplication() throws Exception {
    OIDC.clearCapturedRequests();
    OIDC.nextTokenSubject("proof-write-failure-" + UUID.randomUUID());
    StartedFlow flow = startFlow();
    long applicationsBefore = applications.count();

    SessionRedisOperationsReplacement redisOperations = replaceSessionRedisOperations();
    doAnswer(
            invocation -> {
              if (isProofDelta((Map<?, ?>) invocation.getArgument(1))) {
                throw new DataAccessResourceFailureException("redis unavailable");
              }
              return invocation.callRealMethod();
            })
        .when(redisOperations.proofHashOperations())
        .putAll(any(), any());
    try {
      MvcResult callback = mvc.perform(get(flow.callback()).cookie(flow.cookie())).andReturn();

      assertThat(callback.getResponse().getStatus()).isEqualTo(503);
      assertThat(callback.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
      assertThat(callback.getResponse().getHeader("Location")).isNull();
      assertThat(callback.getResponse().getCookie("dev-refresh")).isNull();
      assertThat(applications.count()).isEqualTo(applicationsBefore);
    } finally {
      redisOperations.restore();
    }
  }

  @Test
  void proofWriteAppliedThenFailedDoesNotPublishASignupApplicationOrRedirect() throws Exception {
    OIDC.clearCapturedRequests();
    OIDC.nextTokenSubject("proof-write-unknown-" + UUID.randomUUID());
    StartedFlow flow = startFlow();
    long applicationsBefore = applications.count();

    SessionRedisOperationsReplacement redisOperations = replaceSessionRedisOperations();
    doAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              if (isProofDelta((Map<?, ?>) invocation.getArgument(1))) {
                throw new DataAccessResourceFailureException("redis acknowledgement lost");
              }
              return result;
            })
        .when(redisOperations.proofHashOperations())
        .putAll(any(), any());
    try {
      MvcResult callback = mvc.perform(get(flow.callback()).cookie(flow.cookie())).andReturn();

      assertThat(callback.getResponse().getStatus()).isEqualTo(503);
      assertThat(callback.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
      assertThat(callback.getResponse().getHeader("Location")).isNull();
      assertThat(callback.getResponse().getCookie("dev-refresh")).isNull();
      assertThat(applications.count()).isEqualTo(applicationsBefore);
    } finally {
      redisOperations.restore();
    }
  }

  @Test
  void committedSignupSurvivesOneShotSessionIssueFailureAndSameIdentityCanStartANewOAuthLogin()
      throws Exception {
    OIDC.clearCapturedRequests();
    String subject = "issuance-recovery-" + UUID.randomUUID();
    long membersBefore = users.count();
    long identitiesBefore = identities.count();
    OIDC.nextTokenSubject(subject);

    MvcResult callback = complete(startFlow());
    Cookie onboarding = callback.getResponse().getCookie("dev-onboarding");
    UUID applicationId = signupApplicationId(onboarding);
    assertThat(onboarding).isNotNull();
    MvcResult csrf =
        mvc.perform(get("/auth/csrf").cookie(onboarding)).andExpect(status().isOk()).andReturn();
    Cookie csrfCookie = csrf.getResponse().getCookie("dev-csrf");
    String csrfToken =
        json.readTree(csrf.getResponse().getContentAsString()).required("token").textValue();

    doThrow(new DomainException(AuthErrorCode.AUTH_STATE_UNAVAILABLE))
        .doCallRealMethod()
        .when(sessionStore)
        .issue(anyLong(), any(UUID.class));

    OAuthIdentity identity;
    User member;
    try {
      MvcResult signup =
          mvc.perform(
                  post("/auth/signup")
                      .cookie(onboarding, csrfCookie)
                      .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                      .header("X-XSRF-TOKEN", csrfToken)
                      .contentType("application/json")
                      .content(signupRequest(applicationId, "발급실패회원")))
              .andExpect(status().isServiceUnavailable())
              .andExpect(jsonPath("$.code").value("AUTH_STATE_UNAVAILABLE"))
              .andExpect(jsonPath("$.accessToken").doesNotExist())
              .andReturn();

      assertThat(signup.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
      assertThat(signup.getResponse().getHeader(HttpHeaders.AUTHORIZATION)).isNull();
      assertThat(signup.getResponse().getCookie("dev-refresh")).isNull();
      identity =
          identities.findByProviderAndProviderSubject(OAuthProvider.KAKAO, subject).orElseThrow();
      member = users.findById(identity.getUserId()).orElseThrow();
      assertThat(member.getStatus()).isEqualTo(UserStatus.ACTIVE);
      assertThat(users.count()).isEqualTo(membersBefore + 1);
      assertThat(identities.count()).isEqualTo(identitiesBefore + 1);
      verify(sessionStore, times(1)).issue(anyLong(), any(UUID.class));
    } finally {
      reset(sessionStore);
    }

    OIDC.clearCapturedRequests();
    OIDC.nextTokenSubject(subject);
    MvcResult reentered = complete(startFlow());
    assertThat(reentered.getResponse().getRedirectedUrl())
        .isEqualTo("http://localhost:5173/auth/kakao/callback?result=AUTHENTICATED");
    Cookie refresh = reentered.getResponse().getCookie("dev-refresh");
    assertThat(refresh).isNotNull();
    assertThat(refresh.getValue()).isNotBlank();
    OAuthIdentity reenteredIdentity =
        identities.findByProviderAndProviderSubject(OAuthProvider.KAKAO, subject).orElseThrow();
    assertThat(reenteredIdentity.getId()).isEqualTo(identity.getId());
    assertThat(reenteredIdentity.getUserId()).isEqualTo(member.getId());
    assertThat(users.count()).isEqualTo(membersBefore + 1);
    assertThat(identities.count()).isEqualTo(identitiesBefore + 1);
  }

  @Test
  void unknownCallbackStoresOnlySlotAndOnboardingProofInTheTemporarySession() throws Exception {
    OIDC.clearCapturedRequests();
    OIDC.nextTokenSubject("proof-only-" + UUID.randomUUID());

    MvcResult callback = complete(startFlow());
    Cookie onboarding = callback.getResponse().getCookie("dev-onboarding");
    assertThat(onboarding).isNotNull();
    MockHttpServletRequest cookieRequest = new MockHttpServletRequest();
    cookieRequest.setCookies(onboarding);
    List<String> sessionIds = onboardingCookieSerializer.readCookieValues(cookieRequest);
    assertThat(sessionIds).hasSize(1);
    Session temporarySession = sessionRepository.findById(sessionIds.getFirst());

    assertThat(temporarySession).isNotNull();
    assertThat(temporarySession.getAttributeNames()).hasSize(2);
    assertThat(temporarySession.getAttributeNames())
        .allSatisfy(name -> {
          Object attribute = temporarySession.getAttribute(name);
          assertThat(attribute).isInstanceOfAny(UUID.class, OnboardingProof.class);
        });
    assertThat(temporarySession.getAttributeNames())
        .noneMatch(name ->
            name.equals("SPRING_SECURITY_CONTEXT")
                || name.contains("authorizedClient")
                || name.contains("OAuth2AuthorizationRequest"));
    assertThat(callback.getResponse().getCookie("dev-refresh")).isNull();
    assertThat(callback.getResponse().getHeader(HttpHeaders.AUTHORIZATION)).isNull();
  }

  @Test
  void oauthStartWithSignupClaimInProgressReturnsSafeFailureWithoutMutatingTheSession()
      throws Exception {
    doReturn(RedisFlowClaimGuard.BeginOAuthResult.SIGNUP_IN_PROGRESS)
        .when(flowGuard)
        .beginOAuth(any(UUID.class), any(UUID.class), any(Instant.class), any(Instant.class));

    MvcResult result = mvc.perform(get("/oauth2/authorization/kakao")).andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(303);
    assertThat(result.getResponse().getRedirectedUrl())
        .isEqualTo("http://localhost:5173/auth/kakao/callback?result=ERROR&errorCode=OAUTH_FAILED");
    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(result.getRequest().getSession(false)).isNull();
  }

  @Test
  void oauthStartWithUnavailableGuardReturnsUncommittedUnavailableResponse() throws Exception {
    doThrow(new SessionStateUnavailableException(new RuntimeException("redis down")))
        .when(flowGuard)
        .beginOAuth(any(UUID.class), any(UUID.class), any(Instant.class), any(Instant.class));

    MvcResult result = mvc.perform(get("/oauth2/authorization/kakao")).andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(503);
    assertThat(result.getResponse().getHeader("Location")).isNull();
    assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(result.getResponse().getContentAsString()).contains("AUTH_STATE_UNAVAILABLE");
    assertThat(result.getRequest().getSession(false)).isNull();
  }

  @Test
  void replacementAfterOldCallbackClaimsPreventsOldPublicationAndLetsTheNewContextComplete()
      throws Exception {
    OIDC.clearCapturedRequests();
    StartedFlow oldFlow = startFlow();
    try (LocalOidcProvider.TokenResponseGate gate = OIDC.blockNextTokenResponse();
        ConfigurableApplicationContext secondary = secondContext()) {
      assertSameAuthConfiguration(secondary.getBean(AuthProperties.class));
      CompletableFuture<MvcResult> oldCallback =
          CompletableFuture.supplyAsync(() -> {
            try {
              return complete(oldFlow);
            } catch (Exception exception) {
              throw new java.util.concurrent.CompletionException(exception);
            }
          });
      assertThat(gate.awaitEntered(Duration.ofSeconds(5))).isTrue();

      int port = ((WebServerApplicationContext) secondary).getWebServer().getPort();
      StartedFlow replacement = startFlowOnServer(port, oldFlow.cookie());
      assertThat(replacement.cookie().getValue()).isNotEqualTo(oldFlow.cookie().getValue());

      gate.release();
      MvcResult oldResult = oldCallback.get(10, TimeUnit.SECONDS);
      assertThat(oldResult.getResponse().getRedirectedUrl())
          .isEqualTo(
              "http://localhost:5173/auth/kakao/callback?result=ERROR&errorCode=OAUTH_FAILED");
      assertThat(oldResult.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
      assertThat(oldResult.getResponse().getCookie("dev-refresh")).isNull();
      assertThat(oldResult.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
      assertThat(oldResult.getResponse().getHeader(HttpHeaders.AUTHORIZATION)).isNull();
      assertThat(oldResult.getResponse().getContentAsString()).isEmpty();

      HttpResponse<Void> replacementResult = completeOnServer(port, replacement);
      assertThat(replacementResult.statusCode()).isEqualTo(303);
      assertThat(replacementResult.headers().firstValue("Location").orElseThrow())
          .isEqualTo("http://localhost:5173/auth/kakao/callback?result=SIGNUP_REQUIRED");
      assertThat(responseCookie(replacementResult, "dev-refresh")).isNull();
    }
  }

  private MvcResult concurrentSignup(
      CyclicBarrier barrier,
      Cookie onboarding,
      Cookie csrfCookie,
      String csrfToken,
      UUID applicationId) {
    try {
      barrier.await(5, TimeUnit.SECONDS);
      return mvc.perform(
              post("/auth/signup")
                  .cookie(onboarding, csrfCookie)
                  .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                  .header("X-XSRF-TOKEN", csrfToken)
                  .contentType("application/json")
                  .content(signupRequest(applicationId, "동시가입회원")))
          .andReturn();
    } catch (Exception exception) {
      throw new CompletionException(exception);
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private SessionRedisOperationsReplacement replaceSessionRedisOperations() {
    Object wrapped = ReflectionTestUtils.getField(sessionRepository, "delegate");
    if (!(wrapped instanceof RedisSessionRepository repository)) {
      throw new IllegalStateException("Expected RedisSessionRepository delegate");
    }
    RedisOperations<String, Object> original =
        (RedisOperations) repository.getSessionRedisOperations();
    RedisOperations<String, Object> spy = spy(original);
    ReflectionTestUtils.setField(repository, "sessionRedisOperations", spy);
    HashOperations<Object, Object, Object> hashOperations = spy((HashOperations) spy.opsForHash());
    doReturn(hashOperations).when(spy).opsForHash();
    return new SessionRedisOperationsReplacement(repository, original, hashOperations);
  }

  private record SessionRedisOperationsReplacement(
      RedisSessionRepository repository,
      RedisOperations<String, Object> original,
      HashOperations<Object, Object, Object> proofHashOperations) {

    void restore() {
      ReflectionTestUtils.setField(repository, "sessionRedisOperations", original);
    }
  }

  private static boolean isFlowDelta(Map<?, ?> delta) {
    return delta.keySet().stream()
        .map(Object::toString)
        .anyMatch(key ->
            key.equals(
                    "sessionAttr:"
                        + OAuthFlowAuthorizationRequestRepository.class.getName()
                        + ".flow")
                || key.equals(
                    "sessionAttr:"
                        + OAuthFlowAuthorizationRequestRepository.class.getName()
                        + ".slot"));
  }

  private static boolean isProofDelta(Map<?, ?> delta) {
    return delta.keySet().stream()
        .map(Object::toString)
        .anyMatch(
            key -> key.equals("sessionAttr:" + OnboardingSessionProofs.class.getName() + ".proof"));
  }

  private UUID signupApplicationId(Cookie onboarding) throws Exception {
    MvcResult context =
        mvc.perform(get("/auth/signup/context").cookie(onboarding))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.signupApplicationId").isNotEmpty())
            .andReturn();
    return UUID.fromString(
        json.readTree(context.getResponse().getContentAsString())
            .required("signupApplicationId")
            .textValue());
  }

  private static String signupRequest(UUID applicationId, String displayName) {
    return "{\"signupApplicationId\":\""
        + applicationId
        + "\",\"displayName\":\""
        + displayName
        + "\",\"consents\":{\"service\":true,\"privacy\":true,\"ageConfirmed\":true}}";
  }

  private StartedFlow startFlow() throws Exception {
    return startFlow(mvc, null);
  }

  private StartedFlow startFlow(MockMvc client, Cookie existingCookie) throws Exception {
    var request = get("/oauth2/authorization/kakao");
    if (existingCookie != null) {
      request.cookie(existingCookie);
    }
    MvcResult start = client.perform(request).andReturn();
    assertThat(start.getResponse().getStatus()).isEqualTo(302);
    assertThat(start.getResponse().getCookies())
        .extracting(Cookie::getName)
        .contains("dev-onboarding");
    Cookie cookie = start.getResponse().getCookie("dev-onboarding");
    assertThat(cookie).isNotNull();
    HttpResponse<Void> authorization =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(start.getResponse().getRedirectedUrl()))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(authorization.statusCode()).isEqualTo(302);
    return new StartedFlow(
        cookie, URI.create(authorization.headers().firstValue("Location").orElseThrow()));
  }

  private MvcResult complete(StartedFlow flow) throws Exception {
    return complete(mvc, flow);
  }

  private MvcResult complete(MockMvc client, StartedFlow flow) throws Exception {
    return client
        .perform(get(flow.callback()).cookie(flow.cookie()))
        .andExpect(status().isSeeOther())
        .andReturn();
  }

  private StartedFlow startFlowOnServer(int port, Cookie existingCookie) throws Exception {
    HttpResponse<Void> start =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/oauth2/authorization/kakao"))
                .header("Cookie", existingCookie.getName() + "=" + existingCookie.getValue())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(start.statusCode()).isEqualTo(302);
    Cookie cookie = responseCookie(start, "dev-onboarding");
    assertThat(cookie)
        .as(
            "secondary start must set the temporary-cookie name; received names: %s",
            responseCookieNames(start))
        .isNotNull();
    HttpResponse<Void> authorization =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(start.headers().firstValue("Location").orElseThrow()))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(authorization.statusCode()).isEqualTo(302);
    return new StartedFlow(
        cookie, URI.create(authorization.headers().firstValue("Location").orElseThrow()));
  }

  private HttpResponse<Void> completeOnServer(int port, StartedFlow flow) throws Exception {
    URI callback =
        URI.create(
            "http://127.0.0.1:"
                + port
                + flow.callback().getRawPath()
                + "?"
                + flow.callback().getRawQuery());
    return HTTP.send(
        HttpRequest.newBuilder(callback)
            .header("Cookie", flow.cookie().getName() + "=" + flow.cookie().getValue())
            .GET()
            .build(),
        HttpResponse.BodyHandlers.discarding());
  }

  private static Cookie responseCookie(HttpResponse<?> response, String name) {
    String prefix = name + "=";
    return response.headers().allValues("Set-Cookie").stream()
        .filter(value -> value.startsWith(prefix))
        .findFirst()
        .map(value -> new Cookie(name, value.substring(prefix.length()).split(";", 2)[0]))
        .orElse(null);
  }

  private static java.util.List<String> responseCookieNames(HttpResponse<?> response) {
    return response.headers().allValues("Set-Cookie").stream()
        .map(value -> value.substring(0, value.indexOf('=')))
        .toList();
  }

  private record StartedFlow(Cookie cookie, URI callback) {}

  private ConfigurableApplicationContext secondContext() {
    Map<String, Object> properties = new HashMap<>();
    for (String name :
        new String[] {
          "spring.data.redis.host",
          "spring.data.redis.port",
          "spring.data.redis.username",
          "spring.data.redis.password",
          "spring.data.redis.ssl.enabled",
          "spring.session.redis.namespace",
          "auth.redis-namespace",
          "auth.session-redis-namespace",
          "auth.fe-origin",
          "auth.secure-cookies",
          "auth.jwt.issuer",
          "auth.jwt.audience",
          "auth.jwt.private-key-location",
          "auth.jwt.public-key-location",
          "auth.kakao.client-id",
          "auth.kakao.client-secret",
          "auth.kakao.redirect-uri",
          "auth.kakao.issuer-uri",
          "auth.kakao.authorization-uri",
          "auth.kakao.token-uri",
          "auth.kakao.jwk-set-uri",
          "spring.datasource.url",
          "spring.datasource.username",
          "spring.datasource.password",
          "spring.datasource.driver-class-name"
        }) {
      properties.put(name, environment.getRequiredProperty(name));
    }
    properties.put("spring.jpa.hibernate.ddl-auto", "none");
    properties.put("spring.flyway.enabled", "false");
    properties.put("server.port", "0");
    String[] arguments =
        properties.entrySet().stream()
            .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
            .toArray(String[]::new);
    return new SpringApplicationBuilder(ApiApplication.class)
        .profiles("test")
        .web(WebApplicationType.SERVLET)
        .run(arguments);
  }

  private void assertSameAuthConfiguration(AuthProperties secondary) {
    assertThat(secondary.secureCookies()).isFalse();
    assertThat(secondary.redisNamespace()).isEqualTo(authProperties.redisNamespace());
    assertThat(secondary.sessionRedisNamespace()).isEqualTo(authProperties.sessionRedisNamespace());
    assertThat(secondary.feOrigin()).isEqualTo(authProperties.feOrigin());
    assertThat(secondary.jwt().issuer()).isEqualTo(authProperties.jwt().issuer());
    assertThat(secondary.jwt().audience()).isEqualTo(authProperties.jwt().audience());
    assertThat(secondary.jwt().privateKeyLocation())
        .isEqualTo(authProperties.jwt().privateKeyLocation());
    assertThat(secondary.jwt().publicKeyLocation())
        .isEqualTo(authProperties.jwt().publicKeyLocation());
    assertThat(secondary.kakao().clientId()).isEqualTo(authProperties.kakao().clientId());
    assertThat(secondary.kakao().redirectUri()).isEqualTo(authProperties.kakao().redirectUri());
    assertThat(secondary.kakao().issuerUri()).isEqualTo(authProperties.kakao().issuerUri());
    assertThat(secondary.kakao().authorizationUri())
        .isEqualTo(authProperties.kakao().authorizationUri());
    assertThat(secondary.kakao().tokenUri()).isEqualTo(authProperties.kakao().tokenUri());
    assertThat(secondary.kakao().jwkSetUri()).isEqualTo(authProperties.kakao().jwkSetUri());
  }

  private static LocalOidcProvider startProvider() {
    try {
      return LocalOidcProvider.start();
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}

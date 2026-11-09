package org.kirikick.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.kirikick.api.auth.domain.LoginGrant;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.infra.RedisFlowClaimGuard;
import org.kirikick.api.auth.infra.SessionStateUnavailableException;
import org.kirikick.api.auth.repository.OAuthIdentityRepository;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.auth.support.LocalOidcProvider;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
class DevMultiOriginOAuthIntegrationTest {

  private static final String DEFAULT_FE_ORIGIN = "http://localhost:5173";
  private static final String ADDITIONAL_FE_ORIGIN = "https://frontend.example";
  private static final LocalOidcProvider OIDC = startProvider();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Autowired private MockMvc mvc;
  @Autowired private UserRepository users;
  @Autowired private OAuthIdentityRepository identities;
  @Autowired private LoginSessionService logins;
  @MockitoSpyBean private RedisFlowClaimGuard flowGuard;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    String namespace = "kirikick:dev:multi-origin:" + UUID.randomUUID();
    registry.add("auth.redis-namespace", () -> namespace + ":auth");
    registry.add("auth.session-redis-namespace", () -> namespace + ":spring:session");
    registry.add("spring.session.redis.namespace", () -> namespace + ":spring:session");
    registry.add("auth.secure-cookies", () -> true);
    registry.add("auth.dev-additional-fe-origins", () -> ADDITIONAL_FE_ORIGIN);
    registry.add("spring.datasource.url", () -> "jdbc:h2:mem:dev-multi-origin;DB_CLOSE_DELAY=-1");
    OIDC.authProperties().forEach((name, value) -> registry.add(name, () -> value));
  }

  @AfterAll
  static void closeProvider() {
    OIDC.close();
  }

  @BeforeEach
  void resetProvider() {
    OIDC.clearCapturedRequests();
    reset(flowGuard);
  }

  @Test
  void ordinaryOAuthStartFailureReturnsToTheSelectedAdditionalOrigin() throws Exception {
    doReturn(RedisFlowClaimGuard.BeginOAuthResult.SIGNUP_IN_PROGRESS)
        .when(flowGuard)
        .beginOAuth(any(), any(), any(), any());

    MvcResult result =
        mvc.perform(
                get("/oauth2/authorization/kakao").param("frontend_origin", ADDITIONAL_FE_ORIGIN))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(303);
    assertThat(result.getResponse().getRedirectedUrl())
        .isEqualTo(
            ADDITIONAL_FE_ORIGIN + "/auth/kakao/callback?result=ERROR&errorCode=OAUTH_FAILED");
  }

  @Test
  void unavailableOAuthStartDoesNotRedirectToTheSelectedAdditionalOrigin() throws Exception {
    doThrow(new SessionStateUnavailableException(new RuntimeException("redis unavailable")))
        .when(flowGuard)
        .beginOAuth(any(), any(), any(), any());

    MvcResult result =
        mvc.perform(
                get("/oauth2/authorization/kakao").param("frontend_origin", ADDITIONAL_FE_ORIGIN))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(503);
    assertThat(result.getResponse().getHeader("Location")).isNull();
    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
  }

  @Test
  void selectedAdditionalOriginReceivesTheSignupRequiredCallback() throws Exception {
    OIDC.nextTokenSubject("additional-origin-" + UUID.randomUUID());

    MvcResult callback = complete(startFlow(ADDITIONAL_FE_ORIGIN));

    assertThat(callback.getResponse().getRedirectedUrl())
        .isEqualTo(ADDITIONAL_FE_ORIGIN + "/auth/kakao/callback?result=SIGNUP_REQUIRED");
  }

  @Test
  void missingFrontendOriginKeepsTheDefaultSignupRequiredCallback() throws Exception {
    OIDC.nextTokenSubject("default-origin-" + UUID.randomUUID());

    MvcResult callback = complete(startFlow(null));

    assertThat(callback.getResponse().getRedirectedUrl())
        .isEqualTo(DEFAULT_FE_ORIGIN + "/auth/kakao/callback?result=SIGNUP_REQUIRED");
  }

  @Test
  void selectedAdditionalOriginLoginRefreshesForTheAuthenticatedUser() throws Exception {
    String subject = "active-additional-origin-" + UUID.randomUUID();
    User user = users.saveAndFlush(User.create("활성회원", null));
    identities.saveAndFlush(OAuthIdentity.create(user.getId(), OAuthProvider.KAKAO, subject));
    OIDC.nextTokenSubject(subject);

    MvcResult callback = complete(startFlow(ADDITIONAL_FE_ORIGIN));

    assertThat(callback.getResponse().getRedirectedUrl())
        .isEqualTo(ADDITIONAL_FE_ORIGIN + "/auth/kakao/callback?result=AUTHENTICATED");
    Cookie refresh = callback.getResponse().getCookie("__Host-refresh");
    assertThat(refresh).isNotNull();
    LoginGrant grant = logins.refresh(refresh.getValue());
    assertThat(grant.session().userId()).isEqualTo(user.getId());
    assertThat(grant.session().userUuid()).isEqualTo(user.getUuid());
  }

  @Test
  void providerFailureReturnsToTheSelectedAdditionalOrigin() throws Exception {
    OIDC.nextTokenFault(LocalOidcProvider.TokenFault.WRONG_NONCE);

    MvcResult callback = complete(startFlow(ADDITIONAL_FE_ORIGIN));

    assertThat(callback.getResponse().getRedirectedUrl())
        .isEqualTo(
            ADDITIONAL_FE_ORIGIN + "/auth/kakao/callback?result=ERROR&errorCode=OAUTH_FAILED");
  }

  @Test
  void callbackSelectorCannotOverrideTheStoredFrontendOrigin() throws Exception {
    OIDC.nextTokenSubject("callback-selector-" + UUID.randomUUID());
    StartedFlow flow = startFlow(ADDITIONAL_FE_ORIGIN);
    URI callbackUri =
        UriComponentsBuilder.fromUri(flow.callback())
            .queryParam("frontend_origin", DEFAULT_FE_ORIGIN)
            .build(true)
            .toUri();

    MvcResult callback = complete(flow, callbackUri);

    assertThat(callback.getResponse().getRedirectedUrl())
        .isEqualTo(ADDITIONAL_FE_ORIGIN + "/auth/kakao/callback?result=SIGNUP_REQUIRED");
  }

  @Test
  void callbackWithWrongStateUsesTheDefaultFailureCallback() throws Exception {
    StartedFlow flow = startFlow(ADDITIONAL_FE_ORIGIN);
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
        .isEqualTo(DEFAULT_FE_ORIGIN + "/auth/kakao/callback?result=ERROR&errorCode=OAUTH_FAILED");
  }

  @ParameterizedTest
  @MethodSource("frontendOriginReplacements")
  void replacementFlowWinsWhileTheStaleCallbackUsesTheDefaultFailureTarget(
      String firstOrigin, String replacementOrigin) throws Exception {
    OIDC.nextTokenSubject("replacement-flow-" + UUID.randomUUID());
    StartedFlow first = startFlow(firstOrigin);
    StartedFlow replacement = startFlow(replacementOrigin, first.cookie());

    MvcResult stale = complete(replacement.cookie(), first.callback());
    MvcResult current = complete(replacement);

    assertThat(stale.getResponse().getRedirectedUrl())
        .isEqualTo(DEFAULT_FE_ORIGIN + "/auth/kakao/callback?result=ERROR&errorCode=OAUTH_FAILED");
    assertThat(stale.getResponse().getCookie("__Host-refresh")).isNull();
    assertThat(current.getResponse().getRedirectedUrl())
        .isEqualTo(replacementOrigin + "/auth/kakao/callback?result=SIGNUP_REQUIRED");
  }

  @Test
  void invalidSelectorKeepsTheExistingFlowUsable() throws Exception {
    OIDC.nextTokenSubject("preserved-flow-" + UUID.randomUUID());
    StartedFlow flow = startFlow(ADDITIONAL_FE_ORIGIN);

    MvcResult rejected =
        mvc.perform(
                get("/oauth2/authorization/kakao")
                    .cookie(flow.cookie())
                    .param("frontend_origin", ADDITIONAL_FE_ORIGIN, ADDITIONAL_FE_ORIGIN))
            .andReturn();

    assertThat(rejected.getResponse().getStatus()).isEqualTo(400);
    assertThat(rejected.getResponse().getHeader("Location")).isNull();
    MvcResult callback = complete(flow);
    assertThat(callback.getResponse().getRedirectedUrl())
        .isEqualTo(ADDITIONAL_FE_ORIGIN + "/auth/kakao/callback?result=SIGNUP_REQUIRED");
  }

  @Test
  void duplicateFrontendOriginDoesNotStartAnOAuthFlow() throws Exception {
    MvcResult result =
        mvc.perform(
                get("/oauth2/authorization/kakao")
                    .param("frontend_origin", ADDITIONAL_FE_ORIGIN, ADDITIONAL_FE_ORIGIN))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(result.getResponse().getHeader("Location")).isNull();
    assertThat(result.getResponse().getCookie("__Host-onboarding")).isNull();
  }

  private StartedFlow startFlow(String frontendOrigin) throws Exception {
    return startFlow(frontendOrigin, null);
  }

  private StartedFlow startFlow(String frontendOrigin, Cookie existingSession) throws Exception {
    var request = get("/oauth2/authorization/kakao");
    if (frontendOrigin != null) {
      request.param("frontend_origin", frontendOrigin);
    }
    if (existingSession != null) {
      request.cookie(existingSession);
    }
    MvcResult start = mvc.perform(request).andExpect(status().isFound()).andReturn();
    Cookie session = start.getResponse().getCookie("__Host-onboarding");
    assertThat(session).isNotNull();
    HttpResponse<Void> authorization =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(start.getResponse().getRedirectedUrl()))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(authorization.statusCode()).isEqualTo(302);
    return new StartedFlow(
        session, URI.create(authorization.headers().firstValue("Location").orElseThrow()));
  }

  private MvcResult complete(StartedFlow flow) throws Exception {
    return complete(flow, flow.callback());
  }

  private MvcResult complete(StartedFlow flow, URI callbackUri) throws Exception {
    return complete(flow.cookie(), callbackUri);
  }

  private MvcResult complete(Cookie session, URI callbackUri) throws Exception {
    return mvc.perform(get(callbackUri).cookie(session))
        .andExpect(status().isSeeOther())
        .andReturn();
  }

  private static Stream<Arguments> frontendOriginReplacements() {
    return Stream.of(
        Arguments.of(DEFAULT_FE_ORIGIN, ADDITIONAL_FE_ORIGIN),
        Arguments.of(ADDITIONAL_FE_ORIGIN, DEFAULT_FE_ORIGIN));
  }

  private static LocalOidcProvider startProvider() {
    try {
      return LocalOidcProvider.start();
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private record StartedFlow(Cookie cookie, URI callback) {}
}

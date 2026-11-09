package org.kirikick.api.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.kirikick.api.auth.infra.AuthFrontendPolicy;
import org.kirikick.api.auth.infra.RedisFlowClaimGuard;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

class OAuthFlowAuthorizationRequestRepositoryTest {
  private static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");
  private static final String DEFAULT_ORIGIN = "https://app.example";
  private static final String ADDITIONAL_ORIGIN = "http://localhost:5173";
  private static final String FRONTEND_ORIGIN_ATTRIBUTE =
      OAuthFlowAuthorizationRequestRepository.class.getName() + ".frontendOrigin";

  @Test
  void rejectsAnInvalidSelectorBeforeStartingOrReplacingAnExistingFlow() {
    RedisFlowClaimGuard guard = mock(RedisFlowClaimGuard.class);
    OAuthFlowAuthorizationRequestRepository repository = repository(guard);
    MockHttpServletRequest request = startRequest("https://evil.example");
    MockHttpSession oldSession = new MockHttpSession();
    request.setSession(oldSession);

    assertThatThrownBy(
            () -> repository.saveAuthorizationRequest(authorizationRequest(), request, response()))
        .isInstanceOf(IllegalArgumentException.class);

    verify(guard, never()).beginOAuth(any(), any(), any(), any());
    assertThat(oldSession.isInvalid()).isFalse();
  }

  @Test
  void preservesFrameworkAndProviderAttributesWhileBindingTheCanonicalSelectedOrigin()
      throws Exception {
    RedisFlowClaimGuard guard = startedGuard();
    OAuthFlowAuthorizationRequestRepository repository = repository(guard);
    MockHttpServletRequest request = startRequest("HTTP://LOCALHOST:5173");
    OAuth2AuthorizationRequest original = authorizationRequest();

    repository.saveAuthorizationRequest(original, request, response());

    OAuth2AuthorizationRequest stored =
        frameworkRepository().loadAuthorizationRequest(callbackRequest(request.getSession(false)));
    assertThat(stored.getState()).isEqualTo(original.getState());
    assertThat(stored.getAuthorizationUri()).isEqualTo(original.getAuthorizationUri());
    assertThat(stored.getRedirectUri()).isEqualTo(original.getRedirectUri());
    assertThat(stored.getAdditionalParameters()).isEqualTo(original.getAdditionalParameters());
    assertThat(stored.getAttributes())
        .containsEntry("code_verifier", "verifier")
        .containsEntry(FRONTEND_ORIGIN_ATTRIBUTE, ADDITIONAL_ORIGIN);
  }

  @Test
  void restoresTheBoundOriginBeforeAStaleClaimIsRejected() {
    RedisFlowClaimGuard guard = startedGuard();
    when(guard.claimOAuth(any(), any(), any(), any(), any()))
        .thenReturn(RedisFlowClaimGuard.ClaimOAuthResult.STALE);
    OAuthFlowAuthorizationRequestRepository repository = repository(guard);
    MockHttpServletRequest start = startRequest(ADDITIONAL_ORIGIN);
    repository.saveAuthorizationRequest(authorizationRequest(), start, response());

    MockHttpServletRequest callback = callbackRequest(start.getSession(false));

    assertThat(repository.loadAuthorizationRequest(callback)).isNull();
    assertThat(OAuthFlowAuthorizationRequestRepository.matchedFrontendOrigin(callback))
        .isEqualTo(ADDITIONAL_ORIGIN);
  }

  @Test
  void usesTheConfiguredDefaultForAValidLegacyPayloadWithoutAnOriginAttribute() throws Exception {
    RedisFlowClaimGuard guard = startedGuard();
    when(guard.claimOAuth(any(), any(), any(), any(), any()))
        .thenReturn(RedisFlowClaimGuard.ClaimOAuthResult.CLAIMED);
    OAuthFlowAuthorizationRequestRepository repository = repository(guard);
    MockHttpServletRequest start = startRequest(null);
    repository.saveAuthorizationRequest(authorizationRequest(), start, response());

    MockHttpServletRequest callback = callbackRequest(start.getSession(false));
    frameworkRepository().saveAuthorizationRequest(authorizationRequest(), callback, response());

    assertThat(repository.loadAuthorizationRequest(callback)).isNotNull();
    assertThat(OAuthFlowAuthorizationRequestRepository.matchedFrontendOrigin(callback))
        .isEqualTo(DEFAULT_ORIGIN);
  }

  @ParameterizedTest
  @MethodSource("invalidStoredFrontendOrigins")
  void rejectsAnInvalidStoredOriginBeforeClaimingTheFlow(Object storedOrigin) {
    RedisFlowClaimGuard guard = startedGuard();
    OAuthFlowAuthorizationRequestRepository repository = repository(guard);
    MockHttpServletRequest start = startRequest(ADDITIONAL_ORIGIN);
    repository.saveAuthorizationRequest(authorizationRequest(), start, response());

    MockHttpServletRequest callback = callbackRequest(start.getSession(false));
    OAuth2AuthorizationRequest corrupted =
        OAuth2AuthorizationRequest.from(authorizationRequest())
            .attributes(attributes -> attributes.put(FRONTEND_ORIGIN_ATTRIBUTE, storedOrigin))
            .build();
    frameworkRepository().saveAuthorizationRequest(corrupted, callback, response());

    assertThat(repository.loadAuthorizationRequest(callback)).isNull();
    verify(guard, never()).claimOAuth(any(), any(), any(), any(), any());
  }

  @Test
  void rejectsAnOriginRemovedFromTheAllowedPolicyBeforeClaimingTheFlow() {
    RedisFlowClaimGuard guard = startedGuard();
    OAuthFlowAuthorizationRequestRepository startRepository = repository(guard);
    MockHttpServletRequest start = startRequest(ADDITIONAL_ORIGIN);
    startRepository.saveAuthorizationRequest(authorizationRequest(), start, response());

    OAuthFlowAuthorizationRequestRepository callbackRepository =
        new OAuthFlowAuthorizationRequestRepository(
            Clock.fixed(NOW, ZoneOffset.UTC),
            guard,
            new AuthFrontendPolicy(DEFAULT_ORIGIN, List.of()));
    MockHttpServletRequest callback = callbackRequest(start.getSession(false));

    assertThat(callbackRepository.loadAuthorizationRequest(callback)).isNull();
    verify(guard, never()).claimOAuth(any(), any(), any(), any(), any());
  }

  private static Stream<Arguments> invalidStoredFrontendOrigins() {
    return Stream.of(
        Arguments.of(42),
        Arguments.of((Object) null),
        Arguments.of("https://app.example/auth/kakao/callback"),
        Arguments.of("https://revoked.example"));
  }

  private OAuthFlowAuthorizationRequestRepository repository(RedisFlowClaimGuard guard) {
    return new OAuthFlowAuthorizationRequestRepository(
        Clock.fixed(NOW, ZoneOffset.UTC),
        guard,
        new AuthFrontendPolicy(DEFAULT_ORIGIN, List.of(ADDITIONAL_ORIGIN)));
  }

  private RedisFlowClaimGuard startedGuard() {
    RedisFlowClaimGuard guard = mock(RedisFlowClaimGuard.class);
    when(guard.beginOAuth(any(), any(), any(), any()))
        .thenReturn(RedisFlowClaimGuard.BeginOAuthResult.STARTED);
    return guard;
  }

  private MockHttpServletRequest startRequest(String frontendOrigin) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/oauth2/authorization/kakao");
    if (frontendOrigin != null) {
      request.addParameter("frontend_origin", frontendOrigin);
    }
    return request;
  }

  private MockHttpServletRequest callbackRequest(HttpSession session) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/kakao");
    request.setSession((MockHttpSession) session);
    request.addParameter("state", "state");
    return request;
  }

  private OAuth2AuthorizationRequest authorizationRequest() {
    return OAuth2AuthorizationRequest.authorizationCode()
        .authorizationUri("https://provider.example/authorize")
        .clientId("client")
        .redirectUri("https://api.example/login/oauth2/code/kakao")
        .state("state")
        .attributes(attributes -> attributes.put("code_verifier", "verifier"))
        .additionalParameters(parameters -> parameters.put("prompt", "login"))
        .build();
  }

  private MockHttpServletResponse response() {
    return new MockHttpServletResponse();
  }

  private HttpSessionOAuth2AuthorizationRequestRepository frameworkRepository() {
    return new HttpSessionOAuth2AuthorizationRequestRepository();
  }
}

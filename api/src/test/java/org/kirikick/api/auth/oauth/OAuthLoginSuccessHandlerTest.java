package org.kirikick.api.auth.oauth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.controller.AuthHttpSupport;
import org.kirikick.api.auth.domain.LoginGrant;
import org.kirikick.api.auth.domain.LoginSession;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.infra.RedisFlowClaimGuard;
import org.kirikick.api.auth.repository.OAuthIdentityRepository;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.service.SignupApplicationPublicationService;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.domain.UserStatus;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class OAuthLoginSuccessHandlerTest {

  @Test
  void revokesActiveLoginGrantWhenRefreshCookiePreparationFails() {
    Fixture fixture = new Fixture();
    RuntimeException responseFailure = new RuntimeException("response preparation failed");
    doThrow(responseFailure).when(fixture.response).addHeader(eq("Set-Cookie"), anyString());

    assertThatThrownBy(() ->
            fixture.handler.onAuthenticationSuccess(
                fixture.request, fixture.response, fixture.authentication))
        .isSameAs(responseFailure);

    verify(fixture.logins).revoke(fixture.grant);
  }

  @Test
  void preservesResponsePreparationFailureWhenGrantRevocationFails() {
    Fixture fixture = new Fixture();
    RuntimeException responseFailure = new RuntimeException("response preparation failed");
    RuntimeException revokeFailure = new RuntimeException("revoke failed");
    doThrow(responseFailure).when(fixture.response).addHeader(eq("Set-Cookie"), anyString());
    doThrow(revokeFailure).when(fixture.logins).revoke(fixture.grant);

    assertThatThrownBy(() ->
            fixture.handler.onAuthenticationSuccess(
                fixture.request, fixture.response, fixture.authentication))
        .isSameAs(responseFailure);
  }

  @Test
  void doesNotInvalidateOrPublishGrantWhenRedisFinishReportsStaleFlow() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.guard.finishOAuth(
            fixture.flow.slotId(),
            fixture.flow.flowId(),
            fixture.flow.expiresAt(),
            fixture.claimId,
            RedisFlowClaimGuard.OAuthFinish.END,
            null,
            null,
            fixture.now))
        .thenReturn(RedisFlowClaimGuard.FinishOAuthResult.STALE);

    fixture.handler.onAuthenticationSuccess(
        fixture.request, fixture.response, fixture.authentication);

    verify(fixture.session, never()).invalidate();
    verify(fixture.logins, never()).login(fixture.user);
    verify(fixture.response, never()).addHeader(eq("Set-Cookie"), anyString());
  }

  private static final class Fixture {
    private final OAuthIdentityRepository identities = mock(OAuthIdentityRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final LoginSessionService logins = mock(LoginSessionService.class);
    private final SignupApplicationPublicationService publications =
        mock(SignupApplicationPublicationService.class);
    private final RedisFlowClaimGuard guard = mock(RedisFlowClaimGuard.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final Authentication authentication = mock(Authentication.class);
    private final OidcUser principal = mock(OidcUser.class);
    private final User user = mock(User.class);
    private final HttpSession session = mock(HttpSession.class);
    private final Instant now = Instant.parse("2026-11-03T00:00:00Z");
    private final OAuthFlowMetadata flow =
        new OAuthFlowMetadata(
            UUID.randomUUID(), UUID.randomUUID(), "state", now.plusSeconds(600), "session-id");
    private final UUID claimId = UUID.randomUUID();
    private final LoginGrant grant =
        new LoginGrant(
            new LoginSession("sid", 7L, UUID.randomUUID(), Instant.EPOCH, Instant.MAX, Instant.MAX),
            "access",
            "refresh");
    private final OAuthLoginSuccessHandler handler =
        new OAuthLoginSuccessHandler(
            identities,
            users,
            logins,
            publications,
            new AuthHttpSupport(false),
            new ObjectMapper(),
            guard,
            Clock.fixed(now, ZoneOffset.UTC),
            "https://app.kirikick.example");

    private Fixture() {
      when(authentication.getPrincipal()).thenReturn(principal);
      when(request.getAttribute(OAuthFlowAuthorizationRequestRepository.MATCHED_FLOW_ATTRIBUTE))
          .thenReturn(flow);
      when(request.getAttribute(OAuthFlowAuthorizationRequestRepository.class.getName() + ".claim"))
          .thenReturn(claimId);
      when(request.getSession(false)).thenReturn(session);
      when(session.getId()).thenReturn("session-id");
      when(session.getAttribute(OAuthFlowAuthorizationRequestRepository.class.getName() + ".flow"))
          .thenReturn(flow);
      when(guard.finishOAuth(
              flow.slotId(),
              flow.flowId(),
              flow.expiresAt(),
              claimId,
              RedisFlowClaimGuard.OAuthFinish.END,
              null,
              null,
              now))
          .thenReturn(RedisFlowClaimGuard.FinishOAuthResult.END_CLEANUP_PERMIT);
      when(principal.getSubject()).thenReturn("kakao-subject");
      when(identities.findByProviderAndProviderSubject(OAuthProvider.KAKAO, "kakao-subject"))
          .thenReturn(Optional.of(OAuthIdentity.create(7L, OAuthProvider.KAKAO, "kakao-subject")));
      when(users.findById(7L)).thenReturn(Optional.of(user));
      when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
      when(logins.login(user)).thenReturn(grant);
    }
  }
}

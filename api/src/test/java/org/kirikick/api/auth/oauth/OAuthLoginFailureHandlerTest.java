package org.kirikick.api.auth.oauth;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.infra.RedisFlowClaimGuard;
import org.springframework.security.authentication.AuthenticationServiceException;

class OAuthLoginFailureHandlerTest {

  @Test
  void invalidatesSessionWhenRequestCarriesMatchedFailedFlow() throws Exception {
    RedisFlowClaimGuard guard = mock(RedisFlowClaimGuard.class);
    Instant now = Instant.parse("2026-11-03T00:00:00Z");
    OAuthLoginFailureHandler handler = handler(guard, now);
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    HttpSession session = mock(HttpSession.class);
    OAuthFlowMetadata flow = flow(now);
    UUID claimId = UUID.randomUUID();
    matchedRequest(request, session, flow, claimId);
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

    handler.onAuthenticationFailure(
        request, response, new AuthenticationServiceException("failed"));

    verify(session).invalidate();
  }

  @Test
  void preservesSessionWhenStaleCallbackHasNoRequestLocalMatchedFlow() throws Exception {
    OAuthLoginFailureHandler handler = handler(mock(RedisFlowClaimGuard.class), Instant.EPOCH);
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    HttpSession newerFlowSession = mock(HttpSession.class);
    when(request.getSession(false)).thenReturn(newerFlowSession);

    handler.onAuthenticationFailure(
        request, response, new AuthenticationServiceException("failed"));

    verify(newerFlowSession, never()).invalidate();
  }

  @Test
  void redirectsFailuresToFixedAllowlistedCallbackLocation() throws Exception {
    OAuthLoginFailureHandler handler = handler(mock(RedisFlowClaimGuard.class), Instant.EPOCH);
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    handler.onAuthenticationFailure(
        request, response, new AuthenticationServiceException("failed"));

    verify(response).setStatus(HttpServletResponse.SC_SEE_OTHER);
    verify(response).setHeader("Cache-Control", "no-store");
    verify(response)
        .setHeader(
            "Location",
            "https://app.kirikick.example/auth/kakao/callback?result=ERROR&errorCode=OAUTH_FAILED");
  }

  private OAuthLoginFailureHandler handler(RedisFlowClaimGuard guard, Instant now) {
    return new OAuthLoginFailureHandler(
        guard, Clock.fixed(now, ZoneOffset.UTC), "https://app.kirikick.example");
  }

  private void matchedRequest(
      HttpServletRequest request, HttpSession session, OAuthFlowMetadata flow, UUID claimId) {
    when(request.getAttribute(OAuthFlowAuthorizationRequestRepository.MATCHED_FLOW_ATTRIBUTE))
        .thenReturn(flow);
    when(request.getAttribute(OAuthFlowAuthorizationRequestRepository.class.getName() + ".claim"))
        .thenReturn(claimId);
    when(request.getSession(false)).thenReturn(session);
    when(session.getId()).thenReturn(flow.sessionId());
    when(session.getAttribute(OAuthFlowAuthorizationRequestRepository.class.getName() + ".flow"))
        .thenReturn(flow);
  }

  private OAuthFlowMetadata flow(Instant now) {
    return new OAuthFlowMetadata(
        UUID.randomUUID(), UUID.randomUUID(), "state", now.plusSeconds(600), "session-id");
  }
}

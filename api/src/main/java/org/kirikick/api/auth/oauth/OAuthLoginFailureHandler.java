package org.kirikick.api.auth.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.auth.infra.AuthFrontendPolicy;
import org.kirikick.api.auth.infra.RedisFlowClaimGuard;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

@RequiredArgsConstructor
public final class OAuthLoginFailureHandler implements AuthenticationFailureHandler {
  private final RedisFlowClaimGuard guard;
  private final Clock clock;
  private final AuthFrontendPolicy frontendPolicy;

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    OAuthFlowMetadata flow = OAuthFlowAuthorizationRequestRepository.matchedFlow(request);
    UUID claimId = OAuthFlowAuthorizationRequestRepository.oauthClaimId(request);
    String failureLocation =
        frontendPolicy.callback(
                OAuthFlowAuthorizationRequestRepository.matchedFrontendOrigin(request))
            + "?result=ERROR&errorCode=OAUTH_FAILED";
    if (flow == null && OAuthFlowAuthorizationRequestRepository.expiredFlow(request) != null) {
      OAuthFlowAuthorizationRequestRepository.invalidateExactFlow(
          request, OAuthFlowAuthorizationRequestRepository.expiredFlow(request));
    } else if (flow != null && claimId != null) {
      RedisFlowClaimGuard.FinishOAuthResult finish =
          guard.finishOAuth(
              flow.slotId(),
              flow.flowId(),
              flow.expiresAt(),
              claimId,
              RedisFlowClaimGuard.OAuthFinish.END,
              null,
              null,
              clock.instant());
      if (finish == RedisFlowClaimGuard.FinishOAuthResult.END_CLEANUP_PERMIT) {
        OAuthFlowAuthorizationRequestRepository.invalidateExactFlow(request, flow);
      } else if (finish == RedisFlowClaimGuard.FinishOAuthResult.STALE) {
        OAuthStaleCompletion.mark(request, flow, claimId, failureLocation);
      }
    }
    response.setStatus(HttpServletResponse.SC_SEE_OTHER);
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Location", failureLocation);
  }
}

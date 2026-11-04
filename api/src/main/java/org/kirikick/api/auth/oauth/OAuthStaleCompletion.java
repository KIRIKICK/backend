package org.kirikick.api.auth.oauth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Request-local proof that a stale OAuth callback may safely replace its invalidated session. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OAuthStaleCompletion {

  private static final String ATTRIBUTE = OAuthStaleCompletion.class.getName();
  private static final String FAILURE_SUFFIX = "?result=ERROR&errorCode=OAUTH_FAILED";

  public static boolean mark(
      HttpServletRequest request, OAuthFlowMetadata flow, UUID claimId, String failureLocation) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(flow, "flow");
    Objects.requireNonNull(claimId, "claimId");
    if (!failureLocation.endsWith(FAILURE_SUFFIX)) {
      throw new IllegalArgumentException(
          "OAuth stale completion requires the fixed failure redirect");
    }
    if (!flow.equals(OAuthFlowAuthorizationRequestRepository.matchedFlow(request))
        || !claimId.equals(OAuthFlowAuthorizationRequestRepository.oauthClaimId(request))
        || !flow.sessionId().equals(request.getRequestedSessionId())) {
      return false;
    }
    request.setAttribute(ATTRIBUTE, new Marker(flow.sessionId(), failureLocation));
    return true;
  }

  public static Marker marker(HttpServletRequest request) {
    Object value = request.getAttribute(ATTRIBUTE);
    return value instanceof Marker marker ? marker : null;
  }

  public record Marker(String invalidatedSessionId, String failureLocation) {}
}

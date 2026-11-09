package org.kirikick.api.auth.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.infra.AuthFrontendPolicy;
import org.kirikick.api.auth.infra.RedisFlowClaimGuard;
import org.kirikick.api.auth.infra.SessionStateUnavailableException;
import org.kirikick.api.common.exception.DomainException;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

/**
 * Keeps framework authorization-request payloads in Spring Session while Redis authorizes every
 * lifecycle transition for the opaque temporary slot.
 */
@RequiredArgsConstructor
public final class OAuthFlowAuthorizationRequestRepository
    implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

  public static final String MATCHED_FLOW_ATTRIBUTE =
      OAuthFlowAuthorizationRequestRepository.class.getName() + ".matched";
  private static final String OAUTH_CLAIM_ATTRIBUTE =
      OAuthFlowAuthorizationRequestRepository.class.getName() + ".claim";
  private static final String EXPIRED_FLOW_ATTRIBUTE =
      OAuthFlowAuthorizationRequestRepository.class.getName() + ".expired";
  private static final String FLOW_ATTRIBUTE =
      OAuthFlowAuthorizationRequestRepository.class.getName() + ".flow";
  private static final String SLOT_ATTRIBUTE =
      OAuthFlowAuthorizationRequestRepository.class.getName() + ".slot";
  private static final String FRONTEND_ORIGIN_ATTRIBUTE =
      OAuthFlowAuthorizationRequestRepository.class.getName() + ".frontendOrigin";
  private static final String MATCHED_FRONTEND_ORIGIN_ATTRIBUTE =
      OAuthFlowAuthorizationRequestRepository.class.getName() + ".matchedFrontendOrigin";
  private static final Duration FLOW_TTL = Duration.ofMinutes(10);

  private final HttpSessionOAuth2AuthorizationRequestRepository delegate =
      new HttpSessionOAuth2AuthorizationRequestRepository();

  private final Clock clock;
  private final RedisFlowClaimGuard guard;
  private final AuthFrontendPolicy frontendPolicy;

  @Override
  public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
    if (matchedFlow(request) != null) {
      return delegate.loadAuthorizationRequest(request);
    }
    HttpSession session = request.getSession(false);
    OAuth2AuthorizationRequest authorizationRequest = delegate.loadAuthorizationRequest(request);
    OAuthFlowMetadata flow = flow(session);
    if (authorizationRequest == null
        || flow == null
        || session == null
        || !flow.state().equals(request.getParameter(OAuth2ParameterNames.STATE))
        || !flow.state().equals(authorizationRequest.getState())
        || !flow.sessionId().equals(session.getId())) {
      return null;
    }

    String frontendOrigin = frontendOrigin(authorizationRequest);
    if (frontendOrigin == null) {
      return null;
    }
    request.setAttribute(MATCHED_FRONTEND_ORIGIN_ATTRIBUTE, frontendOrigin);

    UUID claimId = UUID.randomUUID();
    RedisFlowClaimGuard.ClaimOAuthResult result =
        guard.claimOAuth(flow.slotId(), flow.flowId(), flow.expiresAt(), claimId, clock.instant());
    if (result == RedisFlowClaimGuard.ClaimOAuthResult.EXPIRED_CLEANUP_PERMIT) {
      request.setAttribute(EXPIRED_FLOW_ATTRIBUTE, flow);
      return null;
    }
    if (result != RedisFlowClaimGuard.ClaimOAuthResult.CLAIMED) {
      return null;
    }
    request.setAttribute(MATCHED_FLOW_ATTRIBUTE, flow);
    request.setAttribute(OAUTH_CLAIM_ATTRIBUTE, claimId);
    return authorizationRequest;
  }

  @Override
  public void saveAuthorizationRequest(
      OAuth2AuthorizationRequest authorizationRequest,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (authorizationRequest == null) {
      try {
        delegate.saveAuthorizationRequest(null, request, response);
      } catch (DataAccessException exception) {
        throw new SessionStateUnavailableException(exception);
      }
      return;
    }

    String frontendOrigin = frontendPolicy.select(request.getParameterValues("frontend_origin"));
    OAuth2AuthorizationRequest storedAuthorizationRequest =
        OAuth2AuthorizationRequest.from(authorizationRequest)
            .attributes(attributes -> attributes.put(FRONTEND_ORIGIN_ATTRIBUTE, frontendOrigin))
            .build();

    HttpSession oldSession = request.getSession(false);
    UUID slotId = slot(oldSession);
    if (slotId == null) {
      slotId = UUID.randomUUID();
    }
    Instant now = clock.instant();
    OAuthFlowMetadata next =
        new OAuthFlowMetadata(
            slotId, UUID.randomUUID(), authorizationRequest.getState(), now.plus(FLOW_TTL), "");
    RedisFlowClaimGuard.BeginOAuthResult begin =
        guard.beginOAuth(next.slotId(), next.flowId(), next.expiresAt(), now);
    if (begin != RedisFlowClaimGuard.BeginOAuthResult.STARTED) {
      throw new DomainException(AuthErrorCode.AUTH_STATE_UNAVAILABLE);
    }

    try {
      if (oldSession != null) {
        oldSession.invalidate();
      }
      HttpSession fresh = request.getSession(true);
      fresh.setMaxInactiveInterval((int) FLOW_TTL.toSeconds());
      OAuthFlowMetadata stored =
          new OAuthFlowMetadata(
              next.slotId(), next.flowId(), next.state(), next.expiresAt(), fresh.getId());
      fresh.setAttribute(SLOT_ATTRIBUTE, stored.slotId());
      fresh.setAttribute(FLOW_ATTRIBUTE, stored);
      delegate.saveAuthorizationRequest(storedAuthorizationRequest, request, response);
    } catch (DataAccessException exception) {
      throw new SessionStateUnavailableException(exception);
    }
  }

  @Override
  public OAuth2AuthorizationRequest removeAuthorizationRequest(
      HttpServletRequest request, HttpServletResponse response) {
    if (matchedFlow(request) == null && loadAuthorizationRequest(request) == null) {
      return null;
    }
    try {
      return delegate.removeAuthorizationRequest(request, response);
    } catch (DataAccessException exception) {
      throw new SessionStateUnavailableException(exception);
    }
  }

  public static OAuthFlowMetadata matchedFlow(HttpServletRequest request) {
    Object value = request.getAttribute(MATCHED_FLOW_ATTRIBUTE);
    return value instanceof OAuthFlowMetadata flow ? flow : null;
  }

  public static OAuthFlowMetadata expiredFlow(HttpServletRequest request) {
    Object value = request.getAttribute(EXPIRED_FLOW_ATTRIBUTE);
    return value instanceof OAuthFlowMetadata flow ? flow : null;
  }

  public static UUID oauthClaimId(HttpServletRequest request) {
    Object value = request.getAttribute(OAUTH_CLAIM_ATTRIBUTE);
    return value instanceof UUID claimId ? claimId : null;
  }

  public static String matchedFrontendOrigin(HttpServletRequest request) {
    Object value = request.getAttribute(MATCHED_FRONTEND_ORIGIN_ATTRIBUTE);
    return value instanceof String origin ? origin : null;
  }

  private String frontendOrigin(OAuth2AuthorizationRequest authorizationRequest) {
    if (!authorizationRequest.getAttributes().containsKey(FRONTEND_ORIGIN_ATTRIBUTE)) {
      return frontendPolicy.defaultOrigin();
    }
    Object value = authorizationRequest.getAttributes().get(FRONTEND_ORIGIN_ATTRIBUTE);
    if (!(value instanceof String origin) || !frontendPolicy.isAllowed(origin)) {
      return null;
    }
    return origin;
  }

  public static OAuthFlowMetadata flow(HttpSession session) {
    if (session == null) {
      return null;
    }
    Object value = session.getAttribute(FLOW_ATTRIBUTE);
    return value instanceof OAuthFlowMetadata flow ? flow : null;
  }

  public static UUID slot(HttpSession session) {
    if (session == null) {
      return null;
    }
    Object value = session.getAttribute(SLOT_ATTRIBUTE);
    return value instanceof UUID slotId ? slotId : null;
  }

  public static boolean invalidateExactFlow(HttpServletRequest request, OAuthFlowMetadata flow) {
    HttpSession session = request.getSession(false);
    if (session == null
        || !flow.sessionId().equals(session.getId())
        || !flow.equals(flow(session))) {
      return false;
    }
    session.invalidate();
    return true;
  }
}

package org.kirikick.api.auth.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.kirikick.api.auth.controller.AuthHttpSupport;
import org.kirikick.api.auth.controller.dto.AuthErrorResponse;
import org.kirikick.api.auth.domain.LoginGrant;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.infra.RedisFlowClaimGuard;
import org.kirikick.api.auth.infra.SessionStateUnavailableException;
import org.kirikick.api.auth.onboarding.OnboardingSessionProofs;
import org.kirikick.api.auth.repository.OAuthIdentityRepository;
import org.kirikick.api.auth.service.AuthStateAvailability;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.service.SignupApplicationPublicationService;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.domain.UserStatus;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

@Slf4j
public final class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {

  private static final String DB_UNAVAILABLE_LOG =
      "auth-signup-application-unavailable exceptionType={}";

  private final OAuthIdentityRepository identities;
  private final UserRepository users;
  private final LoginSessionService logins;
  private final SignupApplicationPublicationService publications;
  private final AuthHttpSupport http;
  private final ObjectMapper objectMapper;
  private final RedisFlowClaimGuard guard;
  private final Clock clock;
  private final String callback;

  public OAuthLoginSuccessHandler(
      OAuthIdentityRepository identities,
      UserRepository users,
      LoginSessionService logins,
      SignupApplicationPublicationService publications,
      AuthHttpSupport http,
      ObjectMapper objectMapper,
      RedisFlowClaimGuard guard,
      Clock clock,
      String feOrigin) {
    this.identities = identities;
    this.users = users;
    this.logins = logins;
    this.publications = publications;
    this.http = http;
    this.objectMapper = objectMapper;
    this.guard = guard;
    this.clock = clock;
    this.callback = feOrigin + "/auth/kakao/callback";
  }

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    OAuthFlowMetadata flow = OAuthFlowAuthorizationRequestRepository.matchedFlow(request);
    UUID claimId = OAuthFlowAuthorizationRequestRepository.oauthClaimId(request);
    if (flow == null || claimId == null) {
      redirect(response, "ERROR&errorCode=OAUTH_FAILED");
      return;
    }

    OidcUser principal = (OidcUser) authentication.getPrincipal();
    Optional<OAuthIdentity> identity =
        identities.findByProviderAndProviderSubject(OAuthProvider.KAKAO, principal.getSubject());
    if (identity.isPresent()) {
      User user = users.findById(identity.get().getUserId()).orElse(null);
      if (!endOAuthFlowAndInvalidateSession(request, flow, claimId)) {
        redirect(response, "ERROR&errorCode=OAUTH_FAILED");
        return;
      }
      if (user == null || user.getStatus() != UserStatus.ACTIVE) {
        redirect(response, "ERROR&errorCode=OAUTH_FAILED");
        return;
      }
      publishLogin(response, user);
      return;
    }

    Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    UUID proofId = UUID.randomUUID();
    Instant proofDeadline = issuedAt.plus(OnboardingSessionProofs.PROOF_TTL);
    RedisFlowClaimGuard.FinishOAuthResult finish =
        guard.finishOAuth(
            flow.slotId(),
            flow.flowId(),
            flow.expiresAt(),
            claimId,
            RedisFlowClaimGuard.OAuthFinish.TO_PROOF,
            proofId,
            proofDeadline,
            issuedAt);
    if (finish != RedisFlowClaimGuard.FinishOAuthResult.PROOF_TRANSITION_PERMIT) {
      if (finish == RedisFlowClaimGuard.FinishOAuthResult.STALE) {
        OAuthStaleCompletion.mark(request, flow, claimId, failureLocation());
      }
      redirect(response, "ERROR&errorCode=OAUTH_FAILED");
      return;
    }
    if (!OAuthFlowAuthorizationRequestRepository.invalidateExactFlow(request, flow)) {
      redirect(response, "ERROR&errorCode=OAUTH_FAILED");
      return;
    }
    HttpSession session = request.getSession(true);
    try {
      session.setAttribute(
          OAuthFlowAuthorizationRequestRepository.class.getName() + ".slot", flow.slotId());
      OnboardingSessionProofs.publish(
          session,
          flow.slotId(),
          OAuthProvider.KAKAO.name(),
          principal.getSubject(),
          principal.getClaimAsString("nickname"),
          proofId,
          issuedAt,
          proofDeadline);
    } catch (DataAccessException exception) {
      cleanupProof(session, flow, proofId, proofDeadline);
      throw new SessionStateUnavailableException(exception);
    }
    try {
      publications.publish(proofId, OAuthProvider.KAKAO, principal.getSubject(), issuedAt);
    } catch (RuntimeException exception) {
      cleanupProof(session, flow, proofId, proofDeadline);
      if (AuthStateAvailability.isFailure(exception)) {
        log.warn(DB_UNAVAILABLE_LOG, exception.getClass().getName());
        AuthHttpSupport.writeError(
            response,
            objectMapper,
            HttpStatus.SERVICE_UNAVAILABLE.value(),
            new AuthErrorResponse(
                AuthErrorCode.AUTH_STATE_UNAVAILABLE.name(),
                AuthErrorCode.AUTH_STATE_UNAVAILABLE.getMessage()));
        return;
      }
      throw exception;
    }
    redirect(response, "SIGNUP_REQUIRED");
  }

  private void cleanupProof(
      HttpSession session, OAuthFlowMetadata flow, UUID proofId, Instant proofDeadline) {
    try {
      if (guard.finishProof(flow.slotId(), proofId, proofDeadline, clock.instant())
          == RedisFlowClaimGuard.FinishProofResult.CLEANUP_PERMIT) {
        OnboardingSessionProofs.invalidateIfExact(session, flow.slotId(), proofId);
      }
    } catch (RuntimeException ignored) {
      // Preserve the publication failure; Redis TTL bounds an unresolved proof.
    }
  }

  private boolean endOAuthFlowAndInvalidateSession(
      HttpServletRequest request, OAuthFlowMetadata flow, UUID claimId) {
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
    if (finish != RedisFlowClaimGuard.FinishOAuthResult.END_CLEANUP_PERMIT) {
      if (finish == RedisFlowClaimGuard.FinishOAuthResult.STALE) {
        OAuthStaleCompletion.mark(request, flow, claimId, failureLocation());
      }
      return false;
    }
    return OAuthFlowAuthorizationRequestRepository.invalidateExactFlow(request, flow);
  }

  private void publishLogin(HttpServletResponse response, org.kirikick.api.user.domain.User user)
      throws IOException {
    LoginGrant grant = logins.login(user);
    try {
      http.writeRefresh(response, grant);
      redirect(response, "AUTHENTICATED");
    } catch (IOException | RuntimeException exception) {
      try {
        logins.revoke(grant);
      } catch (RuntimeException ignored) {
        // The original publication failure determines the result; Redis TTL bounds any residual
        // grant.
      }
      try {
        http.clearRefresh(response);
      } catch (RuntimeException ignored) {
        // Never mask the original response-publication failure.
      }
      throw exception;
    }
  }

  private void redirect(HttpServletResponse response, String result) throws IOException {
    response.setStatus(HttpServletResponse.SC_SEE_OTHER);
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Location", callback + "?result=" + result);
  }

  private String failureLocation() {
    return callback + "?result=ERROR&errorCode=OAUTH_FAILED";
  }
}

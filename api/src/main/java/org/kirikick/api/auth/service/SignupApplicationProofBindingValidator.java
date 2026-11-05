package org.kirikick.api.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.domain.SignupApplication;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.repository.SignupApplicationRepository;
import org.kirikick.api.common.exception.DomainException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SignupApplicationProofBindingValidator {

  private final SignupApplicationRepository applications;
  private final Clock clock;

  public void requireCurrent(
      UUID signupApplicationId, OAuthProvider provider, String providerSubject, Instant expiresAt) {
    try {
      SignupApplication application =
          applications
              .findById(signupApplicationId)
              .orElseThrow(() -> new DomainException(AuthErrorCode.INVALID_SIGNUP_PROOF));
      if (!application.matchesIdentity(provider, providerSubject)
          || !application.getExpiresAt().equals(expiresAt)
          || application.isExpiredAt(clock.instant())) {
        throw new DomainException(AuthErrorCode.INVALID_SIGNUP_PROOF);
      }
    } catch (RuntimeException exception) {
      if (exception instanceof DomainException || !AuthStateAvailability.isFailure(exception)) {
        throw exception;
      }
      throw new DomainException(AuthErrorCode.AUTH_STATE_UNAVAILABLE);
    }
  }
}

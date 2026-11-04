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
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SignupApplicationPublicationService {

  private final SignupApplicationRepository applications;
  private final Clock clock;

  @Transactional
  public void publish(
      UUID applicationId, OAuthProvider provider, String providerSubject, Instant issuedAt) {
    SignupApplication application =
        SignupApplication.open(applicationId, provider, providerSubject, issuedAt);
    if (application.isExpiredAt(clock.instant())) {
      throw new DomainException(AuthErrorCode.INVALID_SIGNUP_PROOF);
    }
    applications.saveAndFlush(application);
  }
}

package org.kirikick.api.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.repository.SignupApplicationRepository;
import org.kirikick.api.common.exception.DomainException;

class SignupApplicationPublicationServiceTest {
  @Test
  void rejectsProofWhoseFixedExpiryHasAlreadyPassedBeforeSaving() {
    SignupApplicationRepository applications = mock(SignupApplicationRepository.class);
    Instant now = Instant.parse("2026-11-04T00:05:00Z");
    SignupApplicationPublicationService publications =
        new SignupApplicationPublicationService(applications, Clock.fixed(now, ZoneOffset.UTC));

    assertThatThrownBy(() ->
            publications.publish(
                UUID.randomUUID(), OAuthProvider.KAKAO, "subject", now.minusSeconds(300)))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).getErrorCode())
        .isEqualTo(AuthErrorCode.INVALID_SIGNUP_PROOF);

    verify(applications, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
  }
}

package org.kirikick.api.auth.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.domain.SignupApplication;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.repository.SignupApplicationRepository;
import org.kirikick.api.common.exception.DomainException;

class SignupApplicationProofBindingValidatorTest {

  private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

  private final SignupApplicationRepository applications = mock(SignupApplicationRepository.class);
  private final SignupApplicationProofBindingValidator bindings =
      new SignupApplicationProofBindingValidator(applications, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void acceptsOnlyExactUnexpiredApplicationIdentityAndExpiry() {
    UUID id = UUID.randomUUID();
    SignupApplication application =
        SignupApplication.open(id, OAuthProvider.KAKAO, "subject", NOW.minusSeconds(1));
    when(applications.findById(id)).thenReturn(Optional.of(application));

    assertThatCode(
            () -> bindings.requireCurrent(id, OAuthProvider.KAKAO, "subject", NOW.plusSeconds(299)))
        .doesNotThrowAnyException();
  }

  @Test
  void normalizesMissingOrMismatchedApplicationToInvalidProof() {
    UUID id = UUID.randomUUID();
    when(applications.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> bindings.requireCurrent(id, OAuthProvider.KAKAO, "subject", NOW.plusSeconds(300)))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).getErrorCode())
        .isEqualTo(AuthErrorCode.INVALID_SIGNUP_PROOF);
  }
}

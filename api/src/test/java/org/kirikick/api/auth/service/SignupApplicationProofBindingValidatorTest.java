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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.domain.SignupApplication;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.repository.SignupApplicationRepository;
import org.kirikick.api.common.exception.DomainException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;

class SignupApplicationProofBindingValidatorTest {

  private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

  private final SignupApplicationRepository applications = mock(SignupApplicationRepository.class);
  private final SignupApplicationProofBindingValidator bindings =
      new SignupApplicationProofBindingValidator(applications, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  @DisplayName("가입 신청의 식별값과 만료 시각이 증명과 정확히 일치하고 유효해야 한다")
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
  @DisplayName("가입 신청이 없으면 유효하지 않은 증명으로 처리한다")
  void rejectsMissingApplicationAsInvalidProof() {
    UUID id = UUID.randomUUID();
    when(applications.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> bindings.requireCurrent(id, OAuthProvider.KAKAO, "subject", NOW.plusSeconds(300)))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).getErrorCode())
        .isEqualTo(AuthErrorCode.INVALID_SIGNUP_PROOF);
  }

  @ParameterizedTest
  @DisplayName("공급자 subject 또는 만료 시각이 일치하지 않으면 거절한다")
  @MethodSource("mismatchedBindings")
  void rejectsMismatchedProviderSubjectOrExpiry(
      OAuthProvider provider, String providerSubject, Instant expiresAt) {
    UUID id = UUID.randomUUID();
    SignupApplication application = SignupApplication.open(id, OAuthProvider.KAKAO, "subject", NOW);
    when(applications.findById(id)).thenReturn(Optional.of(application));

    assertThatThrownBy(() -> bindings.requireCurrent(id, provider, providerSubject, expiresAt))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).getErrorCode())
        .isEqualTo(AuthErrorCode.INVALID_SIGNUP_PROOF);
  }

  private static Stream<Arguments> mismatchedBindings() {
    return Stream.of(
        Arguments.of(OAuthProvider.APPLE, "subject", NOW.plusSeconds(300)),
        Arguments.of(OAuthProvider.KAKAO, "other-subject", NOW.plusSeconds(300)),
        Arguments.of(OAuthProvider.KAKAO, "subject", NOW.plusSeconds(301)));
  }

  @ParameterizedTest
  @DisplayName("가입 신청은 정확한 만료 시각부터 거절한다")
  @ValueSource(longs = {300, 301})
  void rejectsApplicationAtOrAfterExactExpiry(long ageSeconds) {
    UUID id = UUID.randomUUID();
    SignupApplication application =
        SignupApplication.open(id, OAuthProvider.KAKAO, "subject", NOW.minusSeconds(ageSeconds));
    when(applications.findById(id)).thenReturn(Optional.of(application));

    assertThatThrownBy(() ->
            bindings.requireCurrent(id, OAuthProvider.KAKAO, "subject", application.getExpiresAt()))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).getErrorCode())
        .isEqualTo(AuthErrorCode.INVALID_SIGNUP_PROOF);
  }

  @Test
  @DisplayName("쿼리 시간 초과를 인증 상태 장애로 변환한다")
  void mapsQueryTimeoutToAuthStateUnavailable() {
    UUID id = UUID.randomUUID();
    when(applications.findById(id)).thenThrow(new QueryTimeoutException("database timeout"));

    assertThatThrownBy(
            () -> bindings.requireCurrent(id, OAuthProvider.KAKAO, "subject", NOW.plusSeconds(300)))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).getErrorCode())
        .isEqualTo(AuthErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  @Test
  @DisplayName("도메인 오류는 그대로 전파한다")
  void propagatesSemanticFailureWithoutNormalization() {
    UUID id = UUID.randomUUID();
    DataIntegrityViolationException original =
        new DataIntegrityViolationException("semantic failure");
    when(applications.findById(id)).thenThrow(original);

    assertThatThrownBy(
            () -> bindings.requireCurrent(id, OAuthProvider.KAKAO, "subject", NOW.plusSeconds(300)))
        .isSameAs(original);
  }
}

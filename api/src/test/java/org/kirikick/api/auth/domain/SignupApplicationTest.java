package org.kirikick.api.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.common.exception.DomainException;

class SignupApplicationTest {

  private static final Instant OPENED_AT = Instant.parse("2026-11-04T00:00:00Z");
  private static final Instant EXPIRES_AT = OPENED_AT.plusSeconds(300);

  @Test
  void opensPendingApplicationWithExactIdentityAndFixedExpiry() {
    UUID id = UUID.randomUUID();

    SignupApplication application =
        SignupApplication.open(id, OAuthProvider.KAKAO, "opaque-subject", OPENED_AT);

    assertThat(application.getId()).isEqualTo(id);
    assertThat(application.matchesIdentity(OAuthProvider.KAKAO, "opaque-subject")).isTrue();
    assertThat(application.getExpiresAt()).isEqualTo(EXPIRES_AT);
    assertThat(application.isCompleted()).isFalse();
    assertThat(application.isExpiredAt(EXPIRES_AT.minusNanos(1))).isFalse();
    assertThat(application.isExpiredAt(EXPIRES_AT)).isTrue();
  }

  @Test
  void identityComparisonIsExact() {
    SignupApplication application =
        SignupApplication.open(UUID.randomUUID(), OAuthProvider.APPLE, "Subject", OPENED_AT);

    assertThat(application.matchesIdentity(OAuthProvider.APPLE, "Subject")).isTrue();
    assertThat(application.matchesIdentity(OAuthProvider.APPLE, "subject")).isFalse();
    assertThat(application.matchesIdentity(OAuthProvider.KAKAO, "Subject")).isFalse();
  }

  @Test
  void completesOnceAndRetainsExactDisplayName() {
    SignupApplication application =
        SignupApplication.open(UUID.randomUUID(), OAuthProvider.KAKAO, "subject", OPENED_AT);
    Instant completedAt = OPENED_AT.plusSeconds(1);

    application.complete(42L, "Name ", completedAt);

    assertThat(application.isCompleted()).isTrue();
    assertThat(application.getCompletedUserId()).isEqualTo(42L);
    assertThat(application.getCompletedDisplayName()).isEqualTo("Name ");
    assertThat(application.getCompletedAt()).isEqualTo(completedAt);
    assertThat(application.matchesCompletedDisplayName("Name ")).isTrue();
    assertThat(application.matchesCompletedDisplayName("Name")).isFalse();
    assertCode(
        () -> application.complete(43L, "Other", completedAt.plusSeconds(1)),
        AuthErrorCode.SIGNUP_APPLICATION_ALREADY_COMPLETED);
  }

  @Test
  void rejectsInvalidOpenAndCompletionBoundaries() {
    assertCode(
        () -> SignupApplication.open(null, OAuthProvider.KAKAO, "subject", OPENED_AT),
        AuthErrorCode.INVALID_SIGNUP_APPLICATION);
    assertCode(
        () -> SignupApplication.open(UUID.randomUUID(), null, "subject", OPENED_AT),
        AuthErrorCode.INVALID_SIGNUP_APPLICATION);
    assertCode(
        () -> SignupApplication.open(UUID.randomUUID(), OAuthProvider.KAKAO, null, OPENED_AT),
        AuthErrorCode.INVALID_SIGNUP_APPLICATION);
    assertCode(
        () -> SignupApplication.open(UUID.randomUUID(), OAuthProvider.KAKAO, " ", OPENED_AT),
        AuthErrorCode.INVALID_SIGNUP_APPLICATION);
    assertCode(
        () ->
            SignupApplication.open(
                UUID.randomUUID(), OAuthProvider.KAKAO, "a".repeat(256), OPENED_AT),
        AuthErrorCode.INVALID_SIGNUP_APPLICATION);
    assertCode(
        () -> SignupApplication.open(UUID.randomUUID(), OAuthProvider.KAKAO, "subject", null),
        AuthErrorCode.INVALID_SIGNUP_APPLICATION);

    SignupApplication application =
        SignupApplication.open(UUID.randomUUID(), OAuthProvider.KAKAO, "subject", OPENED_AT);
    assertCode(
        () -> application.complete(null, "Name", OPENED_AT),
        AuthErrorCode.INVALID_SIGNUP_APPLICATION_COMPLETION);
    assertCode(
        () -> application.complete(0L, "Name", OPENED_AT),
        AuthErrorCode.INVALID_SIGNUP_APPLICATION_COMPLETION);
    assertCode(
        () -> application.complete(-1L, "Name", OPENED_AT),
        AuthErrorCode.INVALID_SIGNUP_APPLICATION_COMPLETION);
    assertCode(
        () -> application.complete(1L, " ", OPENED_AT),
        AuthErrorCode.INVALID_SIGNUP_APPLICATION_COMPLETION);
    assertCode(
        () -> application.complete(1L, "Name", null),
        AuthErrorCode.INVALID_SIGNUP_APPLICATION_COMPLETION);
  }

  private static void assertCode(ThrowingCallable invocation, AuthErrorCode code) {
    assertThatThrownBy(invocation)
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
  }
}

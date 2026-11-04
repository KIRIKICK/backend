package org.kirikick.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.domain.SignupApplication;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.repository.OAuthIdentityRepository;
import org.kirikick.api.auth.repository.SignupApplicationRepository;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;

class SignupApplicationCompletionServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

  @ParameterizedTest
  @MethodSource("invalidCommands")
  void rejectsInvalidCommandBeforeAccessingRepositories(
      UUID applicationId, OAuthProvider provider, String providerSubject, String displayName) {
    Fixture fixture = fixture();

    assertThatThrownBy(() ->
            fixture
                .service()
                .complete(command(applicationId, provider, providerSubject, displayName)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("가입 완료 명령의 입력값이 유효하지 않습니다.");

    verifyNoInteractions(fixture.applications(), fixture.users(), fixture.identities());
  }

  @Test
  void reportsNotFoundWhenLockedApplicationIsMissing() {
    Fixture fixture = fixture();
    SignupApplicationCompletionService.CompletionCommand command = command();
    when(fixture.applications().findByIdForUpdate(command.applicationId()))
        .thenReturn(Optional.empty());

    assertCode(
        () -> fixture.service().complete(command), AuthErrorCode.SIGNUP_APPLICATION_NOT_FOUND);
    verify(fixture.applications()).findByIdForUpdate(command.applicationId());
    verifyNoInteractions(fixture.users(), fixture.identities());
  }

  @Test
  void rejectsMismatchedIdentityWithoutWrites() {
    Fixture fixture = fixture();
    SignupApplicationCompletionService.CompletionCommand command = command();
    when(fixture.applications().findByIdForUpdate(command.applicationId()))
        .thenReturn(
            Optional.of(
                application(
                    command.applicationId(), OAuthProvider.APPLE, "other", NOW.plusSeconds(300))));

    assertCode(
        () -> fixture.service().complete(command),
        AuthErrorCode.SIGNUP_APPLICATION_IDENTITY_MISMATCH);

    verifyNoInteractions(fixture.users(), fixture.identities());
    verify(fixture.applications(), never()).flush();
  }

  @Test
  void rejectsExpiredPendingApplicationWithoutCreatingUserOrIdentity() {
    Fixture fixture = fixture();
    SignupApplicationCompletionService.CompletionCommand command = command();
    when(fixture.applications().findByIdForUpdate(command.applicationId()))
        .thenReturn(
            Optional.of(
                application(
                    command.applicationId(), command.provider(), command.providerSubject(), NOW)));

    assertCode(() -> fixture.service().complete(command), AuthErrorCode.SIGNUP_APPLICATION_EXPIRED);

    verifyNoInteractions(fixture.users(), fixture.identities());
    verify(fixture.applications(), never()).flush();
  }

  @Test
  void returnsOriginalUserForCompletedApplicationWithExactDisplayName() {
    Fixture fixture = fixture();
    SignupApplicationCompletionService.CompletionCommand command = command();
    SignupApplication application =
        application(
            command.applicationId(),
            command.provider(),
            command.providerSubject(),
            NOW.plusSeconds(300));
    application.complete(7L, command.displayName(), NOW.minusSeconds(1));
    User user = mock(User.class);
    UUID userUuid = UUID.randomUUID();
    when(user.getUuid()).thenReturn(userUuid);
    when(fixture.applications().findByIdForUpdate(command.applicationId()))
        .thenReturn(Optional.of(application));
    when(fixture.users().findById(7L)).thenReturn(Optional.of(user));

    SignupApplicationCompletionService.CompletionResult result =
        fixture.service().complete(command);

    assertThat(result)
        .isEqualTo(
            new SignupApplicationCompletionService.CompletionResult(
                command.applicationId(), userUuid, false));
    verifyNoInteractions(fixture.identities());
    verify(fixture.applications(), never()).flush();
  }

  @Test
  void rejectsDifferentCompletedDisplayNameWithoutLookingUpUser() {
    Fixture fixture = fixture();
    SignupApplicationCompletionService.CompletionCommand command = command();
    SignupApplication application =
        application(
            command.applicationId(),
            command.provider(),
            command.providerSubject(),
            NOW.plusSeconds(300));
    application.complete(7L, "Name ", NOW.minusSeconds(1));
    when(fixture.applications().findByIdForUpdate(command.applicationId()))
        .thenReturn(Optional.of(application));

    assertCode(
        () -> fixture.service().complete(command),
        AuthErrorCode.SIGNUP_APPLICATION_COMPLETED_INPUT_MISMATCH);
    verifyNoInteractions(fixture.users(), fixture.identities());
  }

  @Test
  void failsInvariantWhenCompletedApplicationReferencesMissingUser() {
    Fixture fixture = fixture();
    SignupApplicationCompletionService.CompletionCommand command = command();
    SignupApplication application =
        application(
            command.applicationId(),
            command.provider(),
            command.providerSubject(),
            NOW.plusSeconds(300));
    application.complete(7L, command.displayName(), NOW.minusSeconds(1));
    when(fixture.applications().findByIdForUpdate(command.applicationId()))
        .thenReturn(Optional.of(application));
    when(fixture.users().findById(7L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> fixture.service().complete(command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("완료된 가입 신청이 참조하는 회원을 찾을 수 없습니다.");
    verifyNoInteractions(fixture.identities());
  }

  @Test
  void persistsUserIdentityAndCompletionInOrder() {
    Fixture fixture = fixture();
    SignupApplicationCompletionService.CompletionCommand command = command();
    SignupApplication application =
        application(
            command.applicationId(),
            command.provider(),
            command.providerSubject(),
            NOW.plusSeconds(300));
    User user = mock(User.class);
    UUID userUuid = UUID.randomUUID();
    when(user.getId()).thenReturn(9L);
    when(user.getUuid()).thenReturn(userUuid);
    when(fixture.applications().findByIdForUpdate(command.applicationId()))
        .thenReturn(Optional.of(application));
    when(fixture.users().saveAndFlush(any(User.class))).thenReturn(user);
    when(fixture.identities().saveAndFlush(any(OAuthIdentity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    SignupApplicationCompletionService.CompletionResult result =
        fixture.service().complete(command);

    assertThat(result)
        .isEqualTo(
            new SignupApplicationCompletionService.CompletionResult(
                command.applicationId(), userUuid, true));
    assertThat(application.getCompletedUserId()).isEqualTo(9L);
    assertThat(application.getCompletedDisplayName()).isEqualTo(command.displayName());
    assertThat(application.getCompletedAt()).isEqualTo(NOW);
    InOrder order = inOrder(fixture.users(), fixture.identities(), fixture.applications());
    order.verify(fixture.users()).saveAndFlush(any(User.class));
    order.verify(fixture.identities()).saveAndFlush(any(OAuthIdentity.class));
    order.verify(fixture.applications()).flush();
  }

  @Test
  void translatesOnlyDuplicateOAuthIdentityConstraintToDomainError() {
    Fixture fixture = fixture();
    SignupApplicationCompletionService.CompletionCommand command = command();
    SignupApplication application =
        application(
            command.applicationId(),
            command.provider(),
            command.providerSubject(),
            NOW.plusSeconds(300));
    User user = mock(User.class);
    when(user.getId()).thenReturn(9L);
    when(fixture.applications().findByIdForUpdate(command.applicationId()))
        .thenReturn(Optional.of(application));
    when(fixture.users().saveAndFlush(any(User.class))).thenReturn(user);
    when(fixture.identities().saveAndFlush(any(OAuthIdentity.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "identity insert failed",
                new IllegalStateException(
                    new ConstraintViolationException(
                        "duplicate identity",
                        new SQLException("duplicate identity", "23505"),
                        "uk_oauth_identities_provider_subject"))));

    assertCode(
        () -> fixture.service().complete(command), AuthErrorCode.OAUTH_IDENTITY_ALREADY_EXISTS);

    assertThat(application.isCompleted()).isFalse();
    verify(fixture.applications(), never()).flush();
  }

  @ParameterizedTest
  @MethodSource("otherIdentityInsertFailures")
  void propagatesOtherIdentityInsertFailuresUnchanged(DataIntegrityViolationException failure) {
    Fixture fixture = fixture();
    SignupApplicationCompletionService.CompletionCommand command = command();
    SignupApplication application =
        application(
            command.applicationId(),
            command.provider(),
            command.providerSubject(),
            NOW.plusSeconds(300));
    User user = mock(User.class);
    when(user.getId()).thenReturn(9L);
    when(fixture.applications().findByIdForUpdate(command.applicationId()))
        .thenReturn(Optional.of(application));
    when(fixture.users().saveAndFlush(any(User.class))).thenReturn(user);
    when(fixture.identities().saveAndFlush(any(OAuthIdentity.class))).thenThrow(failure);

    assertThatThrownBy(() -> fixture.service().complete(command)).isSameAs(failure);

    assertThat(application.isCompleted()).isFalse();
    verify(fixture.applications(), never()).flush();
  }

  private static Stream<DataIntegrityViolationException> otherIdentityInsertFailures() {
    return Stream.of(
        new DataIntegrityViolationException(
            "identity insert failed",
            new ConstraintViolationException(
                "other unique constraint",
                new SQLException("duplicate user provider", "23505"),
                "uk_oauth_identities_user_provider")),
        new DataIntegrityViolationException(
            "identity insert failed",
            new ConstraintViolationException(
                "unknown constraint", new SQLException("constraint violation", "23505"), null)),
        new DataIntegrityViolationException(
            "uk_oauth_identities_provider_subject",
            new SQLException("uk_oauth_identities_provider_subject", "23505")),
        new DataIntegrityViolationException("uk_oauth_identities_provider_subject"));
  }

  private static void assertCode(ThrowingCallable invocation, AuthErrorCode code) {
    assertThatThrownBy(invocation)
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
  }

  private static SignupApplicationCompletionService.CompletionCommand command() {
    return command(UUID.randomUUID(), OAuthProvider.KAKAO, "subject", "Name");
  }

  private static SignupApplicationCompletionService.CompletionCommand command(
      UUID applicationId, OAuthProvider provider, String providerSubject, String displayName) {
    return new SignupApplicationCompletionService.CompletionCommand(
        applicationId, provider, providerSubject, displayName);
  }

  private static SignupApplication application(
      UUID id, OAuthProvider provider, String providerSubject, Instant expiresAt) {
    return SignupApplication.open(id, provider, providerSubject, expiresAt.minusSeconds(300));
  }

  private static Stream<Arguments> invalidCommands() {
    return Stream.of(
        Arguments.of(null, OAuthProvider.KAKAO, "subject", "Name"),
        Arguments.of(UUID.randomUUID(), null, "subject", "Name"),
        Arguments.of(UUID.randomUUID(), OAuthProvider.KAKAO, null, "Name"),
        Arguments.of(UUID.randomUUID(), OAuthProvider.KAKAO, " ", "Name"),
        Arguments.of(UUID.randomUUID(), OAuthProvider.KAKAO, "a".repeat(256), "Name"),
        Arguments.of(UUID.randomUUID(), OAuthProvider.KAKAO, "subject", null),
        Arguments.of(UUID.randomUUID(), OAuthProvider.KAKAO, "subject", " "));
  }

  private static Fixture fixture() {
    SignupApplicationRepository applications = mock(SignupApplicationRepository.class);
    UserRepository users = mock(UserRepository.class);
    OAuthIdentityRepository identities = mock(OAuthIdentityRepository.class);
    return new Fixture(
        applications,
        users,
        identities,
        new SignupApplicationCompletionService(
            applications, users, identities, Clock.fixed(NOW, ZoneOffset.UTC)));
  }

  private record Fixture(
      SignupApplicationRepository applications,
      UserRepository users,
      OAuthIdentityRepository identities,
      SignupApplicationCompletionService service) {}
}

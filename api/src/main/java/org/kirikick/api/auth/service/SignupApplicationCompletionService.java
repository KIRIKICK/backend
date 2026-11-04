package org.kirikick.api.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.domain.SignupApplication;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.repository.OAuthIdentityRepository;
import org.kirikick.api.auth.repository.SignupApplicationRepository;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SignupApplicationCompletionService {

  private final SignupApplicationRepository applications;
  private final UserRepository users;
  private final OAuthIdentityRepository identities;
  private final Clock clock;

  public SignupApplicationCompletionService(
      SignupApplicationRepository applications,
      UserRepository users,
      OAuthIdentityRepository identities,
      Clock clock) {
    this.applications = applications;
    this.users = users;
    this.identities = identities;
    this.clock = clock;
  }

  @Transactional
  public CompletionResult complete(CompletionCommand command) {
    SignupApplication application =
        applications
            .findByIdForUpdate(command.applicationId())
            .orElseThrow(() -> new DomainException(AuthErrorCode.SIGNUP_APPLICATION_NOT_FOUND));
    if (!application.matchesIdentity(command.provider(), command.providerSubject())) {
      throw new DomainException(AuthErrorCode.SIGNUP_APPLICATION_IDENTITY_MISMATCH);
    }
    if (application.isCompleted()) {
      if (!application.isCompletedWithDisplayName(command.displayName())) {
        throw new DomainException(AuthErrorCode.SIGNUP_APPLICATION_COMPLETED_INPUT_MISMATCH);
      }
      User user =
          users
              .findById(application.getCompletedUserId())
              .orElseThrow(() -> new IllegalStateException("완료된 가입 신청이 참조하는 회원을 찾을 수 없습니다."));
      return new CompletionResult(application.getId(), user.getUuid(), false);
    }

    Instant now = clock.instant();
    if (application.isExpiredAt(now)) {
      throw new DomainException(AuthErrorCode.SIGNUP_APPLICATION_EXPIRED);
    }
    User user = users.saveAndFlush(User.create(command.displayName(), null));
    identities.saveAndFlush(
        OAuthIdentity.create(user.getId(), command.provider(), command.providerSubject()));
    application.complete(user.getId(), command.displayName(), now);
    applications.flush();
    return new CompletionResult(application.getId(), user.getUuid(), true);
  }

  public record CompletionCommand(
      UUID applicationId, OAuthProvider provider, String providerSubject, String displayName) {

    public CompletionCommand {
      if (applicationId == null
          || provider == null
          || providerSubject == null
          || providerSubject.isBlank()
          || providerSubject.length() > 255
          || displayName == null
          || displayName.isBlank()) {
        throw new IllegalArgumentException("가입 완료 명령의 입력값이 유효하지 않습니다.");
      }
    }
  }

  public record CompletionResult(UUID applicationId, UUID userUuid, boolean newlyCompleted) {}
}

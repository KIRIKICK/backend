package org.kirikick.api.user.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.repository.OAuthIdentityRepository;
import org.kirikick.api.auth.service.AuthStateAvailability;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.domain.exception.UserErrorCode;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserProfileService {

  private final UserRepository users;
  private final OAuthIdentityRepository identities;

  public UserProfile getProfile(UUID userUuid) {
    Optional<User> member;
    try {
      member = users.findByUuid(userUuid);
    } catch (RuntimeException exception) {
      throw unavailableIfRecognized(exception);
    }

    User user = member.orElseThrow(() -> new DomainException(UserErrorCode.ACCOUNT_NOT_FOUND));
    Long userId = user.getId();
    List<OAuthProvider> linkedProviders;
    try {
      linkedProviders = identities.findProvidersByUserId(userId);
    } catch (RuntimeException exception) {
      throw unavailableIfRecognized(exception);
    }

    return new UserProfile(
        user.getUuid(),
        user.getDisplayName(),
        user.getProfileImageUrl(),
        List.copyOf(linkedProviders));
  }

  private RuntimeException unavailableIfRecognized(RuntimeException exception) {
    if (!AuthStateAvailability.isFailure(exception)) {
      return exception;
    }
    return new DomainException(UserErrorCode.PROFILE_UNAVAILABLE);
  }

  public record UserProfile(
      UUID uuid, String displayName, String profileImageUrl, List<OAuthProvider> linkedProviders) {}
}

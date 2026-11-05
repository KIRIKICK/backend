package org.kirikick.api.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.repository.OAuthIdentityRepository;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.domain.exception.UserErrorCode;
import org.kirikick.api.user.repository.UserRepository;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

  @Mock private UserRepository users;
  @Mock private OAuthIdentityRepository identities;

  @Test
  void returnsTheUserProfileAndLinkedProviders() {
    UUID userUuid = UUID.randomUUID();
    User user = user(17L, userUuid, "display", "https://image.example/profile.png");
    when(users.findByUuid(userUuid)).thenReturn(Optional.of(user));
    when(identities.findProvidersByUserId(17L))
        .thenReturn(List.of(OAuthProvider.KAKAO, OAuthProvider.APPLE));

    UserProfileService.UserProfile profile = service().getProfile(userUuid);

    assertThat(profile.uuid()).isEqualTo(userUuid);
    assertThat(profile.displayName()).isEqualTo("display");
    assertThat(profile.profileImageUrl()).isEqualTo("https://image.example/profile.png");
    assertThat(profile.linkedProviders()).containsExactly(OAuthProvider.KAKAO, OAuthProvider.APPLE);
    verify(users).findByUuid(userUuid);
    verify(identities).findProvidersByUserId(17L);
  }

  @Test
  void preservesANullProfileImageUrl() {
    UUID userUuid = UUID.randomUUID();
    User user = user(17L, userUuid, "display", null);
    when(users.findByUuid(userUuid)).thenReturn(Optional.of(user));
    when(identities.findProvidersByUserId(17L)).thenReturn(List.of(OAuthProvider.KAKAO));

    UserProfileService.UserProfile profile = service().getProfile(userUuid);

    assertThat(profile.profileImageUrl()).isNull();
  }

  @Test
  void returnsZeroOneAndTwoProvidersWithOneLookupEachAndNoWrites() {
    for (List<OAuthProvider> expectedProviders :
        List.of(
            List.<OAuthProvider>of(),
            List.of(OAuthProvider.KAKAO),
            List.of(OAuthProvider.KAKAO, OAuthProvider.APPLE))) {
      UserRepository users = mock(UserRepository.class);
      OAuthIdentityRepository identities = mock(OAuthIdentityRepository.class);
      UUID userUuid = UUID.randomUUID();
      User user = user(17L, userUuid, "display", null);
      when(users.findByUuid(userUuid)).thenReturn(Optional.of(user));
      when(identities.findProvidersByUserId(17L)).thenReturn(expectedProviders);

      UserProfileService.UserProfile profile =
          new UserProfileService(users, identities).getProfile(userUuid);

      assertThat(profile.linkedProviders()).containsExactlyElementsOf(expectedProviders);
      verify(users).findByUuid(userUuid);
      verify(identities).findProvidersByUserId(17L);
      verifyNoMoreInteractions(users, identities);
    }
  }

  @Test
  void rejectsAMissingUserWithoutLookingUpProviders() {
    UUID userUuid = UUID.randomUUID();
    when(users.findByUuid(userUuid)).thenReturn(Optional.empty());

    assertError(() -> service().getProfile(userUuid), UserErrorCode.ACCOUNT_NOT_FOUND);

    verify(identities, never()).findProvidersByUserId(anyLong());
  }

  @Test
  void mapsAUserLookupTimeoutToProfileUnavailable() {
    UUID userUuid = UUID.randomUUID();
    when(users.findByUuid(userUuid)).thenThrow(new QueryTimeoutException("timeout"));

    assertError(() -> service().getProfile(userUuid), UserErrorCode.PROFILE_UNAVAILABLE);

    verify(identities, never()).findProvidersByUserId(anyLong());
  }

  @Test
  void mapsAProviderLookupTimeoutToProfileUnavailable() {
    UUID userUuid = UUID.randomUUID();
    User user = user(17L, userUuid, "display", null);
    when(users.findByUuid(userUuid)).thenReturn(Optional.of(user));
    when(identities.findProvidersByUserId(17L)).thenThrow(new QueryTimeoutException("timeout"));

    assertError(() -> service().getProfile(userUuid), UserErrorCode.PROFILE_UNAVAILABLE);
  }

  @Test
  void preservesSemanticNegativeAndUnknownFailuresFromEitherRepository() {
    for (RuntimeException failure :
        List.of(
            new DataIntegrityViolationException("constraint"),
            new IllegalStateException("unexpected"))) {
      UUID userUuid = UUID.randomUUID();
      UserRepository failedUsers = mock(UserRepository.class);
      OAuthIdentityRepository unusedIdentities = mock(OAuthIdentityRepository.class);
      when(failedUsers.findByUuid(userUuid)).thenThrow(failure);

      assertThatThrownBy(
              () -> new UserProfileService(failedUsers, unusedIdentities).getProfile(userUuid))
          .isSameAs(failure);
      verifyNoInteractions(unusedIdentities);

      UserRepository foundUsers = mock(UserRepository.class);
      OAuthIdentityRepository failedIdentities = mock(OAuthIdentityRepository.class);
      User user = user(17L, userUuid, "display", null);
      when(foundUsers.findByUuid(userUuid)).thenReturn(Optional.of(user));
      when(failedIdentities.findProvidersByUserId(17L)).thenThrow(failure);

      assertThatThrownBy(
              () -> new UserProfileService(foundUsers, failedIdentities).getProfile(userUuid))
          .isSameAs(failure);
    }
  }

  private UserProfileService service() {
    return new UserProfileService(users, identities);
  }

  private User user(Long id, UUID uuid, String displayName, String profileImageUrl) {
    User user = User.create(displayName, profileImageUrl);
    ReflectionTestUtils.setField(user, "id", id);
    ReflectionTestUtils.setField(user, "uuid", uuid);
    return user;
  }

  private void assertError(Runnable call, UserErrorCode errorCode) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
  }
}

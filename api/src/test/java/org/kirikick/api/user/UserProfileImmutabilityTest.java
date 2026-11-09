package org.kirikick.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.user.controller.dto.UserProfileResponse;
import org.kirikick.api.user.service.UserProfileService.UserProfile;

class UserProfileImmutabilityTest {

  @Test
  void responseCopiesAndProtectsLinkedProviders() {
    var providers = new ArrayList<>(List.of("kakao"));
    var profile = new UserProfileResponse(UUID.randomUUID(), "name", null, providers);

    providers.add("apple");

    assertThat(profile.linkedProviders()).containsExactly("kakao");
    assertThatThrownBy(() -> profile.linkedProviders().add("apple"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void serviceProfileCopiesAndProtectsLinkedProviders() {
    var providers = new ArrayList<>(List.of(OAuthProvider.KAKAO));
    var profile = new UserProfile(UUID.randomUUID(), "name", null, providers);

    providers.add(OAuthProvider.APPLE);

    assertThat(profile.linkedProviders()).containsExactly(OAuthProvider.KAKAO);
    assertThatThrownBy(() -> profile.linkedProviders().add(OAuthProvider.APPLE))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}

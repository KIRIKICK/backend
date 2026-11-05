package org.kirikick.api.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.support.JpaSliceTest;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;

@JpaSliceTest
class OAuthIdentityRepositoryTest {

  @Autowired private OAuthIdentityRepository oauthIdentityRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private EntityManager entityManager;

  @Test
  void savesAndFindsAnOpaqueProviderSubject() {
    User user = userRepository.save(User.create("display", null));
    OAuthIdentity identity = OAuthIdentity.create(user.getId(), OAuthProvider.KAKAO, "AbC");

    oauthIdentityRepository.save(identity);
    entityManager.flush();
    entityManager.clear();

    OAuthIdentity reloaded =
        oauthIdentityRepository
            .findByProviderAndProviderSubject(OAuthProvider.KAKAO, "AbC")
            .orElseThrow();

    assertThat(reloaded.getUserId()).isEqualTo(user.getId());
    assertThat(reloaded.getProviderSubject()).isEqualTo("AbC");
    assertThat(reloaded.getCreatedAt()).isNotNull();
  }

  @Test
  void returnsEmptyForAnUnknownProviderSubject() {
    assertThat(
            oauthIdentityRepository.findByProviderAndProviderSubject(
                OAuthProvider.APPLE, "missing"))
        .isEmpty();
  }

  @Test
  void findsOnlyTheProvidersLinkedToTheRequestedUser() {
    User firstUser = userRepository.save(User.create("first", null));
    User secondUser = userRepository.save(User.create("second", null));
    oauthIdentityRepository.save(
        OAuthIdentity.create(firstUser.getId(), OAuthProvider.KAKAO, "first-kakao"));
    oauthIdentityRepository.save(
        OAuthIdentity.create(firstUser.getId(), OAuthProvider.APPLE, "first-apple"));
    oauthIdentityRepository.save(
        OAuthIdentity.create(secondUser.getId(), OAuthProvider.KAKAO, "second-kakao"));
    entityManager.flush();
    entityManager.clear();

    List<OAuthProvider> providers =
        oauthIdentityRepository.findProvidersByUserId(firstUser.getId());

    assertThat(providers).containsExactlyInAnyOrder(OAuthProvider.KAKAO, OAuthProvider.APPLE);
  }

  @Test
  void returnsNoProvidersForUsersWithoutLinkedIdentitiesOrUnknownUsers() {
    User user = userRepository.save(User.create("display", null));
    entityManager.flush();
    entityManager.clear();

    assertThat(oauthIdentityRepository.findProvidersByUserId(user.getId())).isEmpty();
    assertThat(oauthIdentityRepository.findProvidersByUserId(Long.MAX_VALUE)).isEmpty();
  }
}

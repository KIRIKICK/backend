package org.kirikick.api.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.support.PostgresJpaTest;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;

@PostgresJpaTest
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class OAuthIdentityRepositoryTest {

  @Autowired private OAuthIdentityRepository oauthIdentityRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private EntityManager entityManager;

  @Test
  void savesAndFindsAnOpaqueProviderSubject() {
    User user = userRepository.save(User.create("display-name", null));
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
}

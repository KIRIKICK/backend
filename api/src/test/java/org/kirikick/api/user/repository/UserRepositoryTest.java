package org.kirikick.api.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.support.JpaSliceTest;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.domain.UserStatus;
import org.springframework.beans.factory.annotation.Autowired;

@JpaSliceTest
class UserRepositoryTest {

  @Autowired private UserRepository userRepository;
  @Autowired private EntityManager entityManager;

  @Test
  @DisplayName("회원 정보를 저장하고 공개 UUID로 조회한다")
  void savesAndFindsTheFullUserModelByPublicUuid() {
    User user = User.create("display", null);

    User saved = userRepository.save(user);
    entityManager.flush();
    entityManager.clear();

    User reloaded = userRepository.findByUuid(saved.getUuid()).orElseThrow();

    assertThat(reloaded.getId()).isNotNull();
    assertThat(reloaded.getUuid()).isEqualTo(saved.getUuid());
    assertThat(reloaded.getDisplayName()).isEqualTo("display");
    assertThat(reloaded.getProfileImageUrl()).isNull();
    assertThat(reloaded.getBirthYear()).isNull();
    assertThat(reloaded.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(reloaded.getWithdrawnAt()).isNull();
    assertThat(reloaded.getCreatedAt()).isNotNull();
    assertThat(reloaded.getUpdatedAt()).isNotNull();
  }

  @Test
  @DisplayName("알 수 없는 공개 UUID는 빈 결과를 반환한다")
  void returnsEmptyForAnUnknownPublicUuid() {
    assertThat(userRepository.findByUuid(UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName("flush 후 다시 조회해도 프로필 이미지 URL을 보존한다")
  void preservesANonNullProfileImageUrlAfterFlushAndReload() {
    User saved = userRepository.save(User.create("display", "https://example.test/profile.png"));
    entityManager.flush();
    entityManager.clear();

    User reloaded = userRepository.findByUuid(saved.getUuid()).orElseThrow();

    assertThat(reloaded.getProfileImageUrl()).isEqualTo("https://example.test/profile.png");
  }
}

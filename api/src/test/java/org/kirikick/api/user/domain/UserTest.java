package org.kirikick.api.user.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.user.domain.exception.UserErrorCode;

class UserTest {

  @Test
  @DisplayName("MVP에서 출생 정보 없이 활성 회원을 생성한다")
  void createsAnActiveUserWithoutMvpBirthInformation() {
    User user = User.create("kirikick", null);

    assertNotNull(user.getUuid());
    assertEquals(UserStatus.ACTIVE, user.getStatus());
    assertEquals("kirikick", user.getDisplayName());
    assertNull(user.getProfileImageUrl());
    assertNull(user.getBirthYear());
    assertNull(user.getWithdrawnAt());
  }

  @Test
  @DisplayName("비어 있는 표시 이름을 거절한다")
  void rejectsBlankDisplayName() {
    for (String displayName : new String[] {null, "", " "}) {
      DomainException exception =
          assertThrows(DomainException.class, () -> User.create(displayName, null));
      assertEquals(UserErrorCode.INVALID_DISPLAY_NAME, exception.getErrorCode());
      assertEquals(UserErrorCode.INVALID_DISPLAY_NAME.getMessage(), exception.getMessage());
    }
  }
}

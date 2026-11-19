package org.kirikick.api.auth.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.common.exception.DomainException;

class OAuthIdentityTest {

  @Test
  @DisplayName("OAuth 공급자의 subject를 입력 그대로 보존한다")
  void preservesOpaqueProviderSubject() {
    OAuthIdentity identity = OAuthIdentity.create(1L, OAuthProvider.APPLE, "AbC-123");

    assertEquals(1L, identity.getUserId());
    assertEquals(OAuthProvider.APPLE, identity.getProvider());
    assertEquals("AbC-123", identity.getProviderSubject());
  }

  @Test
  @DisplayName("유효하지 않은 회원 ID로 OAuth 연동을 생성할 수 없다")
  void rejectsInvalidUserId() {
    for (Long userId : new Long[] {null, 0L, -1L}) {
      DomainException exception =
          assertThrows(
              DomainException.class,
              () -> OAuthIdentity.create(userId, OAuthProvider.KAKAO, "subject"));
      assertEquals(AuthErrorCode.INVALID_USER_ID, exception.getErrorCode());
    }
  }

  @Test
  @DisplayName("OAuth 공급자가 없으면 연동을 생성할 수 없다")
  void rejectsMissingProvider() {
    DomainException exception =
        assertThrows(DomainException.class, () -> OAuthIdentity.create(1L, null, "subject"));
    assertEquals(AuthErrorCode.INVALID_PROVIDER, exception.getErrorCode());
  }

  @Test
  @DisplayName("유효하지 않은 공급자 subject로 연동을 생성할 수 없다")
  void rejectsInvalidProviderSubject() {
    for (String subject : new String[] {null, "", " ", "a".repeat(256)}) {
      DomainException exception =
          assertThrows(
              DomainException.class, () -> OAuthIdentity.create(1L, OAuthProvider.KAKAO, subject));
      assertEquals(AuthErrorCode.INVALID_PROVIDER_SUBJECT, exception.getErrorCode());
    }
  }
}

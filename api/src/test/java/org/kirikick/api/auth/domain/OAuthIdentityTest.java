package org.kirikick.api.auth.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.common.exception.DomainException;

class OAuthIdentityTest {

  @Test
  void preservesOpaqueProviderSubject() {
    OAuthIdentity identity = OAuthIdentity.create(1L, OAuthProvider.APPLE, "AbC-123");

    assertEquals(1L, identity.getUserId());
    assertEquals(OAuthProvider.APPLE, identity.getProvider());
    assertEquals("AbC-123", identity.getProviderSubject());
  }

  @Test
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
  void rejectsMissingProvider() {
    DomainException exception =
        assertThrows(DomainException.class, () -> OAuthIdentity.create(1L, null, "subject"));
    assertEquals(AuthErrorCode.INVALID_PROVIDER, exception.getErrorCode());
  }

  @Test
  void rejectsInvalidProviderSubject() {
    for (String subject : new String[] {null, "", " ", "a".repeat(256)}) {
      DomainException exception =
          assertThrows(
              DomainException.class, () -> OAuthIdentity.create(1L, OAuthProvider.KAKAO, subject));
      assertEquals(AuthErrorCode.INVALID_PROVIDER_SUBJECT, exception.getErrorCode());
    }
  }
}

package org.kirikick.api.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.LoginSession;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.infra.JwtTokenService;
import org.kirikick.api.auth.infra.RedisRefreshSessionStore;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.domain.UserStatus;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.security.oauth2.jwt.Jwt;

class LoginSessionServiceTest {
  @Test
  void authorizesOnlyAnActiveDatabaseUserOwningTheActiveRedisSession() {
    RedisRefreshSessionStore sessions = mock(RedisRefreshSessionStore.class);
    JwtTokenService jwt = mock(JwtTokenService.class);
    UserRepository users = mock(UserRepository.class);
    LoginSessionService service = new LoginSessionService(sessions, jwt, users);
    UUID uuid = UUID.randomUUID();
    LoginSession session =
        new LoginSession("sid", 17L, uuid, Instant.EPOCH, Instant.MAX, Instant.MAX);
    Jwt token = mock(Jwt.class);
    User user = mock(User.class);
    when(token.getSubject()).thenReturn(uuid.toString());
    when(token.getClaimAsString("sid")).thenReturn("sid");
    when(sessions.findActive("sid", uuid)).thenReturn(Optional.of(session));
    when(users.findByUuid(uuid)).thenReturn(Optional.of(user));
    when(user.getId()).thenReturn(17L);
    when(user.getStatus()).thenReturn(UserStatus.ACTIVE);

    service.authorize(token);

    verify(sessions).findActive("sid", uuid);
  }

  @Test
  void rejectsMissingSessionAndInactiveUser() {
    RedisRefreshSessionStore sessions = mock(RedisRefreshSessionStore.class);
    JwtTokenService jwt = mock(JwtTokenService.class);
    UserRepository users = mock(UserRepository.class);
    LoginSessionService service = new LoginSessionService(sessions, jwt, users);
    UUID uuid = UUID.randomUUID();
    Jwt token = mock(Jwt.class);
    when(token.getSubject()).thenReturn(uuid.toString());
    when(token.getClaimAsString("sid")).thenReturn("sid");
    when(sessions.findActive("sid", uuid)).thenReturn(Optional.empty());

    assertCode(() -> service.authorize(token), AuthErrorCode.AUTHENTICATION_REQUIRED);

    LoginSession session =
        new LoginSession("sid", 3L, uuid, Instant.EPOCH, Instant.MAX, Instant.MAX);
    User inactive = mock(User.class);
    when(sessions.findActive("sid", uuid)).thenReturn(Optional.of(session));
    when(users.findByUuid(uuid)).thenReturn(Optional.of(inactive));
    when(inactive.getId()).thenReturn(3L);
    when(inactive.getStatus()).thenReturn(UserStatus.SUSPENDED);
    assertCode(() -> service.authorize(token), AuthErrorCode.ACCOUNT_INACTIVE);
  }

  private void assertCode(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action, AuthErrorCode expected) {
    assertThatThrownBy(action)
        .isInstanceOf(DomainException.class)
        .extracting(exception -> ((DomainException) exception).getErrorCode())
        .isEqualTo(expected);
  }
}

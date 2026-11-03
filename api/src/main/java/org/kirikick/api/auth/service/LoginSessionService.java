package org.kirikick.api.auth.service;

import java.util.UUID;
import org.kirikick.api.auth.domain.IssuedSession;
import org.kirikick.api.auth.domain.LoginGrant;
import org.kirikick.api.auth.domain.LoginSession;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.infra.JwtTokenService;
import org.kirikick.api.auth.infra.RedisRefreshSessionStore;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.domain.UserStatus;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.security.oauth2.jwt.Jwt;

public class LoginSessionService {
  private final RedisRefreshSessionStore sessions;
  private final JwtTokenService jwtTokens;
  private final UserRepository users;

  public LoginSessionService(
      RedisRefreshSessionStore sessions, JwtTokenService jwtTokens, UserRepository users) {
    this.sessions = sessions;
    this.jwtTokens = jwtTokens;
    this.users = users;
  }

  public LoginGrant login(User user) {
    if (user.getId() == null || user.getUuid() == null || user.getStatus() != UserStatus.ACTIVE) {
      throw new DomainException(AuthErrorCode.ACCOUNT_INACTIVE);
    }
    IssuedSession issued = sessions.issue(user.getId(), user.getUuid());
    try {
      return new LoginGrant(
          issued.session(), jwtTokens.issue(issued.session()), issued.refreshToken());
    } catch (RuntimeException exception) {
      sessions.revoke(issued.session().id());
      throw exception;
    }
  }

  public LoginGrant refresh(String rawRefreshToken) {
    IssuedSession issued = sessions.rotate(rawRefreshToken);
    try {
      return new LoginGrant(
          issued.session(), jwtTokens.issue(issued.session()), issued.refreshToken());
    } catch (RuntimeException exception) {
      sessions.revoke(issued.session().id());
      throw exception;
    }
  }

  public void revoke(LoginGrant grant) {
    sessions.revoke(grant.session().id());
  }

  public void authorize(Jwt token) {
    UUID userUuid;
    String sessionId;
    try {
      userUuid = UUID.fromString(token.getSubject());
      sessionId = token.getClaimAsString("sid");
      if (sessionId == null || sessionId.isBlank()) {
        throw new IllegalArgumentException();
      }
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new DomainException(AuthErrorCode.AUTHENTICATION_REQUIRED);
    }
    LoginSession session =
        sessions
            .findActive(sessionId, userUuid)
            .orElseThrow(() -> new DomainException(AuthErrorCode.AUTHENTICATION_REQUIRED));
    User user =
        users
            .findByUuid(userUuid)
            .orElseThrow(() -> new DomainException(AuthErrorCode.AUTHENTICATION_REQUIRED));
    if (!session.userId().equals(user.getId())) {
      throw new DomainException(AuthErrorCode.AUTHENTICATION_REQUIRED);
    }
    if (user.getStatus() != UserStatus.ACTIVE) {
      throw new DomainException(AuthErrorCode.ACCOUNT_INACTIVE);
    }
  }
}

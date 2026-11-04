package org.kirikick.api.auth.service;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.security.oauth2.jwt.Jwt;

public class LoginSessionService {
  private static final Logger log = LoggerFactory.getLogger(LoginSessionService.class);
  private static final String DB_UNAVAILABLE_LOG = "auth-member-state-unavailable exceptionType={}";
  private static final String CLEANUP_FAILURE_LOG = "auth-session-cleanup-failed exceptionType={}";

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
      return grant(issued);
    } catch (RuntimeException exception) {
      revokeAfterFailure(issued.session().id());
      throw exception;
    }
  }

  public LoginGrant refresh(String rawRefreshToken) {
    IssuedSession issued = sessions.rotate(rawRefreshToken);
    try {
      requireActiveMember(issued.session(), issued.session().userUuid());
      return grant(issued);
    } catch (RuntimeException exception) {
      revokeAfterFailure(issued.session().id());
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
    requireActiveMember(session, userUuid);
  }

  private LoginGrant grant(IssuedSession issued) {
    return new LoginGrant(
        issued.session(), jwtTokens.issue(issued.session()), issued.refreshToken());
  }

  private void requireActiveMember(LoginSession session, UUID userUuid) {
    Optional<User> member;
    try {
      member = users.findByUuid(userUuid);
    } catch (RuntimeException exception) {
      throw unavailableIfRecognized(exception);
    }
    User user =
        member.orElseThrow(() -> new DomainException(AuthErrorCode.AUTHENTICATION_REQUIRED));
    if (!session.userId().equals(user.getId())) {
      throw new DomainException(AuthErrorCode.AUTHENTICATION_REQUIRED);
    }
    if (user.getStatus() != UserStatus.ACTIVE) {
      throw new DomainException(AuthErrorCode.ACCOUNT_INACTIVE);
    }
  }

  private RuntimeException unavailableIfRecognized(RuntimeException exception) {
    if (!isAvailabilityFailure(exception)) {
      return exception;
    }
    log.warn(DB_UNAVAILABLE_LOG, exception.getClass().getName());
    return new DomainException(AuthErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  private static boolean isAvailabilityFailure(RuntimeException exception) {
    boolean queryTimeout = false;
    boolean allowedSqlState = false;
    boolean unsupportedSqlState = false;
    boolean resourceFailure = false;
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable current = exception;
        current != null && visited.add(current);
        current = current.getCause()) {
      if (isSemanticNegative(current)) {
        return false;
      }
      queryTimeout |= current instanceof QueryTimeoutException;
      resourceFailure |=
          current instanceof DataAccessResourceFailureException
              || current instanceof TransientDataAccessResourceException;
      if (current instanceof SQLException sqlException && sqlException.getSQLState() != null) {
        String sqlState = sqlException.getSQLState();
        if (sqlState.startsWith("08") || sqlState.equals("53300") || sqlState.equals("57P03")) {
          allowedSqlState = true;
        } else {
          unsupportedSqlState = true;
        }
      }
    }
    if (queryTimeout || allowedSqlState) {
      return true;
    }
    if (unsupportedSqlState) {
      return false;
    }
    return resourceFailure;
  }

  private static boolean isSemanticNegative(Throwable exception) {
    return exception instanceof DataIntegrityViolationException
        || exception instanceof InvalidDataAccessResourceUsageException
        || exception instanceof InvalidDataAccessApiUsageException
        || exception instanceof ConcurrencyFailureException;
  }

  private void revokeAfterFailure(String sessionId) {
    try {
      sessions.revoke(sessionId);
    } catch (RuntimeException exception) {
      log.warn(CLEANUP_FAILURE_LOG, exception.getClass().getName());
    }
  }
}

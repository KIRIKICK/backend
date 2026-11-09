package org.kirikick.api.auth.infra;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.kirikick.api.auth.domain.IssuedSession;
import org.kirikick.api.auth.domain.LoginSession;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.common.exception.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Redis-backed opaque Refresh Token sessions. Raw tokens are never stored. */
public final class RedisRefreshSessionStore {

  private static final Logger log = LoggerFactory.getLogger(RedisRefreshSessionStore.class);

  private enum SessionStatus {
    ACTIVE,
    REVOKED
  }

  private static final Duration ABSOLUTE_LIFETIME = Duration.ofDays(14);
  private static final Duration IDLE_LIFETIME = Duration.ofDays(7);
  private static final SecureRandom RANDOM = new SecureRandom();

  private static final DefaultRedisScript<String> ISSUE_SCRIPT =
      new DefaultRedisScript<>(
          "redis.call('HSET', KEYS[1], 'userId', ARGV[1], 'userUuid', ARGV[2],"
              + " 'createdAt', ARGV[3], 'absoluteExpiresAt', ARGV[4], 'expiresAt', ARGV[5],"
              + " 'lastUsedAt', ARGV[3], 'currentDigest', ARGV[6], 'status', 'ACTIVE');"
              + "redis.call('PEXPIRE', KEYS[1], ARGV[7]);"
              + "redis.call('SET', KEYS[2], ARGV[8], 'PX', ARGV[7]); return 'ISSUED';",
          String.class);

  private static final DefaultRedisScript<String> ROTATE_SCRIPT =
      new DefaultRedisScript<>(
          "local current = redis.call('GET', KEYS[1]);"
              + "if current then "
              + " if current ~= ARGV[1] then return 'INVALID' end;"
              + " if redis.call('HGET', KEYS[3], 'status') ~= 'ACTIVE' then return 'INVALID' end;"
              + " if redis.call('HGET', KEYS[3], 'currentDigest') ~= ARGV[2] then return 'INVALID' end;"
              + " if tonumber(redis.call('HGET', KEYS[3], 'expiresAt')) <= tonumber(ARGV[3]) then return 'INVALID' end;"
              + " if redis.call('HGET', KEYS[3], 'userId') ~= ARGV[8] then return 'INVALID' end;"
              + " if redis.call('HGET', KEYS[3], 'userUuid') ~= ARGV[9] then return 'INVALID' end;"
              + " if redis.call('HGET', KEYS[3], 'absoluteExpiresAt') ~= ARGV[10] then return 'INVALID' end;"
              + " redis.call('DEL', KEYS[1]);"
              + " redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[4]);"
              + " redis.call('SET', KEYS[4], ARGV[1], 'PX', ARGV[5]);"
              + " redis.call('HSET', KEYS[3], 'currentDigest', ARGV[6], 'lastUsedAt', ARGV[3], 'expiresAt', ARGV[7]);"
              + " redis.call('PEXPIRE', KEYS[3], ARGV[5]); return 'ROTATED';"
              + "end;"
              + "local used = redis.call('GET', KEYS[2]);"
              + "if used and used == ARGV[1] and redis.call('EXISTS', KEYS[3]) == 1 then "
              + "redis.call('HSET', KEYS[3], 'status', 'REVOKED'); return 'REUSED'; end;"
              + "return 'INVALID';",
          String.class);

  private static final DefaultRedisScript<Long> REVOKE_SCRIPT =
      new DefaultRedisScript<>(
          "if redis.call('EXISTS', KEYS[1]) == 1 then "
              + "redis.call('HSET', KEYS[1], 'status', 'REVOKED'); return 1; end; return 0;",
          Long.class);

  private final StringRedisTemplate redis;
  private final String namespace;
  private final Clock clock;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The Spring-managed Redis template is intentionally shared.")
  public RedisRefreshSessionStore(StringRedisTemplate redis, String namespace, Clock clock) {
    this.redis = redis;
    this.namespace = normalizeNamespace(namespace);
    this.clock = clock;
  }

  public IssuedSession issue(Long userId, UUID userUuid) {
    if (userId == null || userId <= 0 || userUuid == null) {
      throw new DomainException(AuthErrorCode.AUTHENTICATION_REQUIRED);
    }
    Instant now = clock.instant();
    Instant absoluteExpiresAt = now.plus(ABSOLUTE_LIFETIME);
    Instant expiresAt = now.plus(IDLE_LIFETIME);
    String sessionId = UUID.randomUUID().toString();
    String refreshToken = nextToken();
    String digest = digest(refreshToken);
    long ttlMillis = Duration.between(now, expiresAt).toMillis();
    String result;
    try {
      result =
          redis.execute(
              ISSUE_SCRIPT,
              List.of(sessionKey(sessionId), currentKey(digest)),
              userId.toString(),
              userUuid.toString(),
              millis(now),
              millis(absoluteExpiresAt),
              millis(expiresAt),
              digest,
              Long.toString(ttlMillis),
              sessionId);
    } catch (DataAccessException exception) {
      throw unavailable(exception);
    }
    if (!"ISSUED".equals(result)) {
      throw new DomainException(AuthErrorCode.AUTH_STATE_UNAVAILABLE);
    }
    return new IssuedSession(
        new LoginSession(sessionId, userId, userUuid, now, absoluteExpiresAt, expiresAt),
        refreshToken);
  }

  public IssuedSession rotate(String rawRefreshToken) {
    String digest = digestOrInvalid(rawRefreshToken);
    String sessionId;
    try {
      sessionId = redis.opsForValue().get(currentKey(digest));
      if (sessionId == null) {
        sessionId = redis.opsForValue().get(usedKey(digest));
      }
    } catch (DataAccessException exception) {
      throw unavailable(exception);
    }
    if (sessionId == null) {
      throw new DomainException(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }

    LoginSession existing =
        readSession(sessionId)
            .orElseThrow(() -> new DomainException(AuthErrorCode.INVALID_REFRESH_TOKEN));
    Instant now = clock.instant();
    Instant expiresAt = min(existing.absoluteExpiresAt(), now.plus(IDLE_LIFETIME));
    if (!expiresAt.isAfter(now)) {
      throw new DomainException(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }
    String successor = nextToken();
    String successorDigest = digest(successor);
    long usedTtl = Duration.between(now, existing.absoluteExpiresAt()).toMillis();
    long sessionTtl = Duration.between(now, expiresAt).toMillis();
    String result;
    try {
      result =
          redis.execute(
              ROTATE_SCRIPT,
              List.of(
                  currentKey(digest),
                  usedKey(digest),
                  sessionKey(sessionId),
                  currentKey(successorDigest)),
              sessionId,
              digest,
              millis(now),
              Long.toString(usedTtl),
              Long.toString(sessionTtl),
              successorDigest,
              millis(expiresAt),
              existing.userId().toString(),
              existing.userUuid().toString(),
              millis(existing.absoluteExpiresAt()));
    } catch (DataAccessException exception) {
      throw unavailable(exception);
    }
    if ("REUSED".equals(result)) {
      throw new DomainException(AuthErrorCode.REFRESH_TOKEN_REUSED);
    }
    if (!"ROTATED".equals(result)) {
      throw new DomainException(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }
    return new IssuedSession(
        new LoginSession(
            existing.id(),
            existing.userId(),
            existing.userUuid(),
            existing.createdAt(),
            existing.absoluteExpiresAt(),
            expiresAt),
        successor);
  }

  public Optional<LoginSession> findActive(String sessionId, UUID userUuid) {
    if (sessionId == null || userUuid == null) {
      return Optional.empty();
    }
    Optional<LoginSession> session = readSession(sessionId);
    if (session.isEmpty()
        || !session.get().userUuid().equals(userUuid)
        || !session.get().expiresAt().isAfter(clock.instant())) {
      return Optional.empty();
    }
    return session;
  }

  public void revoke(String sessionId) {
    if (sessionId == null) {
      return;
    }
    try {
      redis.execute(REVOKE_SCRIPT, List.of(sessionKey(sessionId)));
    } catch (DataAccessException exception) {
      throw unavailable(exception);
    }
  }

  private Optional<LoginSession> readSession(String sessionId) {
    Map<Object, Object> values;
    try {
      values = redis.opsForHash().entries(sessionKey(sessionId));
    } catch (DataAccessException exception) {
      throw unavailable(exception);
    }
    if (values.isEmpty() || !SessionStatus.ACTIVE.name().equals(values.get("status"))) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new LoginSession(
              sessionId,
              Long.valueOf((String) values.get("userId")),
              UUID.fromString((String) values.get("userUuid")),
              instant((String) values.get("createdAt")),
              instant((String) values.get("absoluteExpiresAt")),
              instant((String) values.get("expiresAt"))));
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
  }

  private String sessionKey(String sessionId) {
    return namespace + ":session:" + sessionId;
  }

  private String currentKey(String digest) {
    return namespace + ":current:" + digest;
  }

  private String usedKey(String digest) {
    return namespace + ":used:" + digest;
  }

  private static String normalizeNamespace(String namespace) {
    if (namespace == null || namespace.isBlank()) {
      throw new IllegalArgumentException("Redis namespace must not be blank");
    }
    return namespace.endsWith(":") ? namespace.substring(0, namespace.length() - 1) : namespace;
  }

  private static String nextToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String digestOrInvalid(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
      throw new DomainException(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }
    return digest(rawToken);
  }

  private static String digest(String value) {
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 must be available", exception);
    }
  }

  private static String millis(Instant instant) {
    return Long.toString(instant.toEpochMilli());
  }

  private static Instant instant(String millis) {
    return Instant.ofEpochMilli(Long.parseLong(millis));
  }

  private static Instant min(Instant left, Instant right) {
    return left.isBefore(right) ? left : right;
  }

  private static DomainException unavailable(DataAccessException exception) {
    // Exception messages and stack traces may expose Redis keys and token digests.
    log.warn(
        "Redis refresh session state unavailable: exceptionType={}, rootCauseType={}",
        exception.getClass().getName(),
        exception.getMostSpecificCause().getClass().getName());
    return new DomainException(AuthErrorCode.AUTH_STATE_UNAVAILABLE);
  }
}

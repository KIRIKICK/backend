package org.kirikick.api.auth.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.auth.domain.IssuedSession;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.support.RedisTestSupport;
import org.kirikick.api.common.exception.DomainException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisRefreshSessionStoreTest {

  private static RedisTestSupport redis;

  @BeforeAll
  static void setUpRedis() {
    redis = RedisTestSupport.create();
  }

  @AfterEach
  void clearNamespace() {
    redis.clearNamespace();
  }

  @AfterAll
  static void closeRedisClient() {
    redis.close();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"REVOKED", "UNKNOWN"})
  void rejectsSessionsWithoutActiveStatus(String status) {
    RedisRefreshSessionStore store = store();
    IssuedSession issued = store.issue(1L, UUID.randomUUID());
    if (status == null) {
      redis.template().opsForHash().delete(sessionKey(issued), "status");
    } else {
      redis.template().opsForHash().put(sessionKey(issued), "status", status);
    }

    assertThat(store.findActive(issued.session().id(), issued.session().userUuid())).isEmpty();
  }

  @Test
  void issuesOpaqueRefreshTokenAndStoresOnlyDigestIndexes() {
    RedisRefreshSessionStore store = store();
    UUID userUuid = UUID.randomUUID();

    IssuedSession issued = store.issue(1L, userUuid);

    assertThat(issued.refreshToken()).hasSizeGreaterThanOrEqualTo(40);
    assertThat(redis.keys()).doesNotContain(issued.refreshToken());
    assertThat(redis.keys()).anyMatch(key -> key.contains(":session:"));
    assertThat(store.findActive(issued.session().id(), userUuid)).contains(issued.session());
  }

  @Test
  void rotatesOnceAndExactReplayRevokesTheWholeSessionFamily() {
    RedisRefreshSessionStore store = store();
    UUID userUuid = UUID.randomUUID();
    IssuedSession issued = store.issue(1L, userUuid);

    IssuedSession successor = store.rotate(issued.refreshToken());

    assertThatThrownBy(() -> store.rotate(issued.refreshToken()))
        .isInstanceOf(DomainException.class)
        .extracting(exception -> ((DomainException) exception).getErrorCode())
        .isEqualTo(AuthErrorCode.REFRESH_TOKEN_REUSED);
    assertThatThrownBy(() -> store.rotate(successor.refreshToken()))
        .isInstanceOf(DomainException.class)
        .extracting(exception -> ((DomainException) exception).getErrorCode())
        .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);
    assertThat(store.findActive(issued.session().id(), userUuid)).isEmpty();
  }

  @Test
  void forgedTokenCannotSelectOrRevokeAnotherSession() {
    RedisRefreshSessionStore store = store();
    UUID userUuid = UUID.randomUUID();
    IssuedSession issued = store.issue(1L, userUuid);

    assertThatThrownBy(() -> store.rotate("forged-token"))
        .isInstanceOf(DomainException.class)
        .extracting(exception -> ((DomainException) exception).getErrorCode())
        .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);

    assertThat(store.findActive(issued.session().id(), userUuid)).contains(issued.session());
  }

  @Test
  void rejectsRefreshTokenAfterIdleExpiryEvenBeforeRedisWallClockExpiry() {
    UUID userUuid = UUID.randomUUID();
    IssuedSession issued = store().issue(1L, userUuid);
    RedisRefreshSessionStore afterIdleExpiry =
        new RedisRefreshSessionStore(
            redis.template(), redis.namespace(), Clock.offset(fixedClock(), Duration.ofDays(8)));

    assertThatThrownBy(() -> afterIdleExpiry.rotate(issued.refreshToken()))
        .isInstanceOf(DomainException.class)
        .extracting(exception -> ((DomainException) exception).getErrorCode())
        .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);
  }

  @Test
  void allowsOnlyOneConcurrentRotationAndTreatsTheLoserAsReplay() throws Exception {
    RedisRefreshSessionStore store = store();
    IssuedSession issued = store.issue(1L, UUID.randomUUID());
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<AuthErrorCode>> results =
          executor.invokeAll(
              List.of(
                  rotateResult(store, issued.refreshToken()),
                  rotateResult(store, issued.refreshToken())));

      assertThat(results.stream().map(this::resultOf).filter(code -> code == null)).hasSize(1);
      assertThat(results.stream().map(this::resultOf)).contains(AuthErrorCode.REFRESH_TOKEN_REUSED);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void keepsConsumedDigestForAbsoluteLifetimeInsteadOfIdleLifetime() {
    IssuedSession issued = store().issue(1L, UUID.randomUUID());

    store().rotate(issued.refreshToken());

    String usedKey =
        redis.keys().stream().filter(key -> key.contains(":used:")).findFirst().orElseThrow();
    Long usedTtlMillis =
        redis.template().getExpire(usedKey, java.util.concurrent.TimeUnit.MILLISECONDS);
    assertThat(usedTtlMillis).isGreaterThan(Duration.ofDays(13).toMillis());
  }

  @Test
  void doesNotExposeSessionAcrossNamespacesOnTheSameRedisServer() {
    IssuedSession issued = store().issue(1L, UUID.randomUUID());
    try (RedisTestSupport other = RedisTestSupport.create()) {
      RedisRefreshSessionStore otherNamespace =
          new RedisRefreshSessionStore(other.template(), other.namespace(), fixedClock());

      assertThat(otherNamespace.findActive(issued.session().id(), issued.session().userUuid()))
          .isEmpty();
    }
  }

  @Test
  void mapsRedisConnectionFailureToAuthStateUnavailable() {
    LettuceConnectionFactory unavailableConnection =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration("127.0.0.1", 1),
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(100)).build());
    unavailableConnection.afterPropertiesSet();
    StringRedisTemplate unavailableTemplate = new StringRedisTemplate(unavailableConnection);
    unavailableTemplate.afterPropertiesSet();
    RedisRefreshSessionStore unavailableStore =
        new RedisRefreshSessionStore(unavailableTemplate, redis.namespace(), fixedClock());
    try {
      assertThatThrownBy(() -> unavailableStore.issue(1L, UUID.randomUUID()))
          .isInstanceOf(DomainException.class)
          .extracting(exception -> ((DomainException) exception).getErrorCode())
          .isEqualTo(AuthErrorCode.AUTH_STATE_UNAVAILABLE);
    } finally {
      unavailableConnection.destroy();
    }
  }

  @Test
  void doesNotRecreateExpiredSessionWhenConsumedTokenIsReplayedDuringRotation() {
    RedisRefreshSessionStore store = store();
    IssuedSession issued = store.issue(1L, UUID.randomUUID());
    store.rotate(issued.refreshToken());
    String sessionKey = sessionKey(issued);
    MutatingStringRedisTemplate template =
        new MutatingStringRedisTemplate(
            redis.connectionFactory(), () -> redis.template().delete(sessionKey));
    RedisRefreshSessionStore staleStore =
        new RedisRefreshSessionStore(template, redis.namespace(), fixedClock());
    assertThatThrownBy(() -> staleStore.rotate(issued.refreshToken()))
        .isInstanceOf(DomainException.class)
        .extracting(exception -> ((DomainException) exception).getErrorCode())
        .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);

    assertThat(redis.template().hasKey(sessionKey)).isFalse();
  }

  @Test
  void rejectsRotationWhenSessionOwnerChangesBetweenLookupAndLua() {
    RedisRefreshSessionStore store = store();
    IssuedSession issued = store.issue(1L, UUID.randomUUID());
    MutatingStringRedisTemplate template =
        new MutatingStringRedisTemplate(
            redis.connectionFactory(),
            () -> redis.template().opsForHash().put(sessionKey(issued), "userId", "999"));
    RedisRefreshSessionStore staleStore =
        new RedisRefreshSessionStore(template, redis.namespace(), fixedClock());
    assertThatThrownBy(() -> staleStore.rotate(issued.refreshToken()))
        .isInstanceOf(DomainException.class)
        .extracting(exception -> ((DomainException) exception).getErrorCode())
        .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);
  }

  @Test
  void neverExtendsSessionOrCurrentTokenBeyondFourteenDayAbsoluteExpiry() {
    UUID userUuid = UUID.randomUUID();
    IssuedSession first = store().issue(1L, userUuid);
    RedisRefreshSessionStore daySix = storeAt(Duration.ofDays(6));
    IssuedSession second = daySix.rotate(first.refreshToken());
    RedisRefreshSessionStore dayTwelve = storeAt(Duration.ofDays(12));
    IssuedSession third = dayTwelve.rotate(second.refreshToken());

    assertThat(third.session().expiresAt()).isEqualTo(third.session().absoluteExpiresAt());
    String currentKey =
        redis.keys().stream().filter(key -> key.contains(":current:")).findFirst().orElseThrow();
    Long currentTtl =
        redis.template().getExpire(currentKey, java.util.concurrent.TimeUnit.MILLISECONDS);
    assertThat(currentTtl).isLessThanOrEqualTo(Duration.ofDays(2).toMillis());
    assertThatThrownBy(
            () -> storeAt(Duration.ofDays(14).plusMillis(1)).rotate(third.refreshToken()))
        .isInstanceOf(DomainException.class)
        .extracting(exception -> ((DomainException) exception).getErrorCode())
        .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);
  }

  private RedisRefreshSessionStore store() {
    return storeAt(Duration.ZERO);
  }

  private RedisRefreshSessionStore storeAt(Duration offset) {
    return new RedisRefreshSessionStore(
        redis.template(), redis.namespace(), Clock.offset(fixedClock(), offset));
  }

  private Clock fixedClock() {
    return Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC);
  }

  private Callable<AuthErrorCode> rotateResult(
      RedisRefreshSessionStore store, String rawRefreshToken) {
    return () -> {
      try {
        store.rotate(rawRefreshToken);
        return null;
      } catch (DomainException exception) {
        return (AuthErrorCode) exception.getErrorCode();
      }
    };
  }

  private AuthErrorCode resultOf(Future<AuthErrorCode> result) {
    try {
      return result.get();
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private String sessionKey(IssuedSession issued) {
    return redis.namespace() + ":session:" + issued.session().id();
  }

  private static final class MutatingStringRedisTemplate extends StringRedisTemplate {

    private final Runnable mutation;
    private final AtomicBoolean armed = new AtomicBoolean(true);

    private MutatingStringRedisTemplate(
        LettuceConnectionFactory connectionFactory, Runnable mutation) {
      super(connectionFactory);
      this.mutation = mutation;
      afterPropertiesSet();
    }

    @Override
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
      if (armed.compareAndSet(true, false)) {
        mutation.run();
      }
      return super.execute(script, keys, args);
    }
  }
}

package org.kirikick.api.auth.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.auth.domain.IssuedSession;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.support.RedisTestSupport;
import org.kirikick.api.common.exception.DomainException;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
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
  @DisplayName("활성 상태가 아닌 Refresh 세션은 사용할 수 없다")
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
  @DisplayName("불투명한 Refresh 토큰을 발급하고 저장소에는 다이제스트 인덱스만 남긴다")
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
  @DisplayName("Refresh를 한 번 회전하고 같은 토큰이 다시 오면 세션 family 전체를 폐기한다")
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
  @DisplayName("위조 토큰으로 다른 사람의 세션을 선택하거나 폐기할 수 없다")
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
  @DisplayName("유휴 만료 시각이 지나면 Redis TTL이 남아 있어도 Refresh를 거절한다")
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
  @DisplayName("동시 회전 중 하나만 성공하고 다른 요청은 토큰 재사용으로 처리한다")
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
  @DisplayName("사용한 토큰 다이제스트는 유휴 만료와 무관하게 절대 만료까지 보관한다")
  void keepsConsumedDigestForAbsoluteLifetimeInsteadOfIdleLifetime() {
    IssuedSession issued = store().issue(1L, UUID.randomUUID());

    store().rotate(issued.refreshToken());

    String usedKey =
        redis.keys().stream().filter(key -> key.contains(":used:")).findFirst().orElseThrow();
    Long usedTtlMillis = redis.template().getExpire(usedKey, TimeUnit.MILLISECONDS);
    assertThat(usedTtlMillis).isGreaterThan(Duration.ofDays(13).toMillis());
  }

  @Test
  @DisplayName("같은 Redis 서버에서도 네임스페이스가 다르면 세션을 조회할 수 없다")
  void doesNotExposeSessionAcrossNamespacesOnTheSameRedisServer() {
    IssuedSession issued = store().issue(1L, UUID.randomUUID());
    try (RedisTestSupport other = RedisTestSupport.create()) {
      RedisRefreshSessionStore otherNamespace =
          new RedisRefreshSessionStore(other.template(), other.namespace(), fixedClock());

      assertThat(otherNamespace.findActive(issued.session().id(), issued.session().userUuid()))
          .isEmpty();
    }
  }

  @ParameterizedTest
  @DisplayName("Redis 장애 타입을 민감 정보 없이 경고 로그에 기록한다")
  @ValueSource(booleans = {false, true})
  void logsRedisFailureTypesAtWarnWithoutSensitiveData(boolean hasRootCause) {
    String token = "sensitive-refresh-token";
    String digest = "sensitive-digest";
    String sessionId = "sensitive-session-id";
    String redisKey = "sensitive-redis-key";
    String sensitiveMessage = String.join(" ", token, digest, sessionId, redisKey);
    DataAccessResourceFailureException failure =
        new DataAccessResourceFailureException(
            sensitiveMessage, hasRootCause ? new IllegalStateException(sensitiveMessage) : null);
    StringRedisTemplate failingRedis = mock(StringRedisTemplate.class);
    when(failingRedis.<Long>execute(any(), anyList())).thenThrow(failure);
    RedisRefreshSessionStore store =
        new RedisRefreshSessionStore(failingRedis, redisKey, fixedClock());
    Logger logger = (Logger) LoggerFactory.getLogger(RedisRefreshSessionStore.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertThatThrownBy(() -> store.revoke(sessionId))
          .isInstanceOf(DomainException.class)
          .extracting(exception -> ((DomainException) exception).getErrorCode())
          .isEqualTo(AuthErrorCode.AUTH_STATE_UNAVAILABLE);
      assertThat(appender.list)
          .singleElement()
          .satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                .contains("exceptionType=" + DataAccessResourceFailureException.class.getName())
                .contains(
                    "rootCauseType="
                        + (hasRootCause
                            ? IllegalStateException.class.getName()
                            : DataAccessResourceFailureException.class.getName()))
                .doesNotContain(token, digest, sessionId, redisKey);
            assertThat(event.getThrowableProxy()).isNull();
          });
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("Redis 연결 오류를 인증 상태 장애로 변환한다")
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
  @DisplayName("회전 중 사용한 토큰을 재사용해도 만료 세션은 재생성되지 않는다")
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
  @DisplayName("조회 후 Lua 실행 전에 세션 소유자가 바뀌면 회전을 거절한다")
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
  @DisplayName("세션과 현재 토큰을 14일의 절대 만료 시각 이후로 연장하지 않는다")
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
    Long currentTtl = redis.template().getExpire(currentKey, TimeUnit.MILLISECONDS);
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

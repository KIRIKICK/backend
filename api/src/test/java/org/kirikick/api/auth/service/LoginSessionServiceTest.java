package org.kirikick.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.CannotCreateTransactionException;

class LoginSessionServiceTest {
  @Test
  @DisplayName("회전한 세션 소유자가 DB의 활성 회원일 때만 Refresh를 완료한다")
  void refreshesOnlyAfterTheRotatedSessionOwnerIsAnActiveDatabaseUser() {
    Fixture fixture = fixture();
    IssuedSession issued = issued(fixture.uuid(), 17L);
    User user = activeUser(17L);
    when(fixture.sessions.rotate("old-refresh")).thenReturn(issued);
    when(fixture.users.findByUuid(fixture.uuid())).thenReturn(Optional.of(user));
    when(fixture.jwt.issue(issued.session())).thenReturn("access");

    LoginGrant grant = fixture.service().refresh("old-refresh");

    assertThat(grant.accessToken()).isEqualTo("access");
    InOrder order = inOrder(fixture.sessions, fixture.users, fixture.jwt);
    order.verify(fixture.sessions).rotate("old-refresh");
    order.verify(fixture.users).findByUuid(fixture.uuid());
    order.verify(fixture.jwt).issue(issued.session());
  }

  @Test
  @DisplayName("DB에 회원이 없으면 Refresh를 거절하고 회전한 세션을 폐기한다")
  void refreshRejectsMissingDatabaseUserAndRevokesTheRotatedSession() {
    Fixture fixture = fixture();
    IssuedSession issued = issued(fixture.uuid(), 17L);
    when(fixture.sessions.rotate("old-refresh")).thenReturn(issued);
    when(fixture.users.findByUuid(fixture.uuid())).thenReturn(Optional.empty());

    assertCode(
        () -> fixture.service().refresh("old-refresh"), AuthErrorCode.AUTHENTICATION_REQUIRED);

    verify(fixture.sessions).revoke(issued.session().id());
    verify(fixture.jwt, never()).issue(issued.session());
  }

  @Test
  @DisplayName("조회한 회원이 회전한 세션의 소유자와 다르면 Refresh를 거절한다")
  void refreshRejectsDatabaseUserThatDoesNotOwnTheRotatedSession() {
    Fixture fixture = fixture();
    IssuedSession issued = issued(fixture.uuid(), 17L);
    when(fixture.sessions.rotate("old-refresh")).thenReturn(issued);
    User differentOwner = activeUser(18L);
    when(fixture.users.findByUuid(fixture.uuid())).thenReturn(Optional.of(differentOwner));

    assertCode(
        () -> fixture.service().refresh("old-refresh"), AuthErrorCode.AUTHENTICATION_REQUIRED);

    verify(fixture.sessions).revoke(issued.session().id());
  }

  @ParameterizedTest
  @DisplayName("각 비활성 회원 상태에서 Refresh를 거절한다")
  @EnumSource(
      value = UserStatus.class,
      names = {"WITHDRAWN", "SUSPENDED", "BANNED"})
  void refreshRejectsEachInactiveDatabaseUserStatus(UserStatus status) {
    Fixture fixture = fixture();
    IssuedSession issued = issued(fixture.uuid(), 17L);
    User user = mock(User.class);
    when(user.getId()).thenReturn(17L);
    when(user.getStatus()).thenReturn(status);
    when(fixture.sessions.rotate("old-refresh")).thenReturn(issued);
    when(fixture.users.findByUuid(fixture.uuid())).thenReturn(Optional.of(user));

    assertCode(() -> fixture.service().refresh("old-refresh"), AuthErrorCode.ACCOUNT_INACTIVE);

    verify(fixture.sessions).revoke(issued.session().id());
  }

  @Test
  @DisplayName("지원하는 DB 가용성 오류만 서비스 이용 불가로 변환한다")
  void mapsOnlyRecognizedDatabaseAvailabilityFailuresToUnavailable() {
    Fixture fixture = fixture();
    IssuedSession issued = issued(fixture.uuid(), 17L);
    when(fixture.sessions.rotate("old-refresh")).thenReturn(issued);
    when(fixture.users.findByUuid(fixture.uuid()))
        .thenThrow(
            new DataAccessResourceFailureException(
                "private", new SQLException("private", "08006")));

    assertCode(
        () -> fixture.service().refresh("old-refresh"), AuthErrorCode.AUTH_STATE_UNAVAILABLE);

    verify(fixture.sessions).revoke(issued.session().id());
  }

  @Test
  @DisplayName("Refresh와 인가에서 SQLState가 없는 일시적 연결 오류를 장애로 변환한다")
  void mapsNullSqlStateTransientConnectionFailureFromRefreshAndAuthorizeToUnavailable() {
    Fixture fixture = fixture();
    IssuedSession issued = issued(fixture.uuid(), 17L);
    RuntimeException failure =
        new CannotCreateTransactionException(
            "private", new SQLTransientConnectionException("private"));
    when(fixture.sessions.rotate("old-refresh")).thenReturn(issued);
    when(fixture.users.findByUuid(fixture.uuid())).thenThrow(failure);

    assertCode(
        () -> fixture.service().refresh("old-refresh"), AuthErrorCode.AUTH_STATE_UNAVAILABLE);
    verify(fixture.sessions).revoke(issued.session().id());
    verify(fixture.jwt, never()).issue(issued.session());

    when(fixture.sessions.findActive("sid", fixture.uuid()))
        .thenReturn(Optional.of(issued.session()));
    Jwt token = token(fixture.uuid());
    assertCode(() -> fixture.service().authorize(token), AuthErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  @Test
  @DisplayName("가용성 오류의 근거가 없는 트랜잭션 시작 실패를 그대로 전파한다")
  void preservesTransactionCreationFailuresWithoutRecognizedAvailabilityEvidence() {
    for (RuntimeException failure :
        new RuntimeException[] {
          new CannotCreateTransactionException("private"),
          new CannotCreateTransactionException("private", new SQLException("private")),
          new CannotCreateTransactionException("private", new SQLTransientException("private")),
          new CannotCreateTransactionException("private", new IllegalStateException("private"))
        }) {
      Fixture fixture = fixture();
      IssuedSession issued = issued(fixture.uuid(), 17L);
      when(fixture.sessions.rotate("old-refresh")).thenReturn(issued);
      when(fixture.users.findByUuid(fixture.uuid())).thenThrow(failure);

      assertThatThrownBy(() -> fixture.service().refresh("old-refresh")).isSameAs(failure);
      verify(fixture.sessions).revoke(issued.session().id());
    }
  }

  @ParameterizedTest
  @DisplayName("지원하지 않는 SQLState가 있는 일시적 연결 오류를 그대로 전파한다")
  @ValueSource(strings = {"54000", "57014", ""})
  void preservesTransientConnectionFailureWithUnsupportedNonNullSqlState(String sqlState) {
    Fixture fixture = fixture();
    IssuedSession issued = issued(fixture.uuid(), 17L);
    RuntimeException failure =
        new CannotCreateTransactionException(
            "private", new SQLTransientConnectionException("private", sqlState));
    when(fixture.sessions.rotate("old-refresh")).thenReturn(issued);
    when(fixture.users.findByUuid(fixture.uuid())).thenThrow(failure);

    assertThatThrownBy(() -> fixture.service().refresh("old-refresh")).isSameAs(failure);
  }

  @Test
  @DisplayName("SQLState 없는 일시적 연결 오류 안의 도메인 실패를 유지한다")
  void preservesSemanticNegativeInsideNullSqlStateTransientConnectionFailure() {
    Fixture fixture = fixture();
    IssuedSession issued = issued(fixture.uuid(), 17L);
    RuntimeException failure =
        new CannotCreateTransactionException(
            "private",
            new SQLTransientConnectionException(
                "private", new DataIntegrityViolationException("private")));
    when(fixture.sessions.rotate("old-refresh")).thenReturn(issued);
    when(fixture.users.findByUuid(fixture.uuid())).thenThrow(failure);

    assertThatThrownBy(() -> fixture.service().refresh("old-refresh")).isSameAs(failure);
  }

  @Test
  @DisplayName("Spring 쿼리 시간 초과만 장애로 변환하고 원시 SQL 시간 초과는 유지한다")
  void mapsSpringQueryTimeoutButNotRawStatementTimeoutToUnavailable() {
    Fixture fixture = fixture();
    IssuedSession issued = issued(fixture.uuid(), 17L);
    when(fixture.sessions.rotate("old-refresh")).thenReturn(issued);
    when(fixture.users.findByUuid(fixture.uuid())).thenThrow(new QueryTimeoutException("private"));

    assertCode(
        () -> fixture.service().refresh("old-refresh"), AuthErrorCode.AUTH_STATE_UNAVAILABLE);

    Fixture rawSqlFixture = fixture();
    IssuedSession rawIssued = issued(rawSqlFixture.uuid(), 17L);
    RuntimeException raw = new RuntimeException(new SQLException("private", "57014"));
    when(rawSqlFixture.sessions.rotate("old-refresh")).thenReturn(rawIssued);
    when(rawSqlFixture.users.findByUuid(rawSqlFixture.uuid())).thenThrow(raw);

    assertThatThrownBy(() -> rawSqlFixture.service().refresh("old-refresh")).isSameAs(raw);
  }

  @Test
  @DisplayName("일반 리소스 오류에 감싸진 도메인 오류와 미지원 SQL 오류를 유지한다")
  void preservesSemanticAndUnsupportedSqlFailuresOverBroadResourceWrappers() {
    Fixture unsupported = fixture();
    IssuedSession unsupportedIssued = issued(unsupported.uuid(), 17L);
    RuntimeException unsupportedFailure =
        new DataAccessResourceFailureException("private", new SQLException("private", "54000"));
    when(unsupported.sessions.rotate("old-refresh")).thenReturn(unsupportedIssued);
    when(unsupported.users.findByUuid(unsupported.uuid())).thenThrow(unsupportedFailure);

    assertThatThrownBy(() -> unsupported.service().refresh("old-refresh"))
        .isSameAs(unsupportedFailure);

    Fixture semantic = fixture();
    IssuedSession semanticIssued = issued(semantic.uuid(), 17L);
    RuntimeException semanticFailure =
        new DataAccessResourceFailureException(
            "private", new InvalidDataAccessResourceUsageException("private"));
    when(semantic.sessions.rotate("old-refresh")).thenReturn(semanticIssued);
    when(semantic.users.findByUuid(semantic.uuid())).thenThrow(semanticFailure);

    assertThatThrownBy(() -> semantic.service().refresh("old-refresh")).isSameAs(semanticFailure);
  }

  @Test
  @DisplayName("SQLState나 도메인 거절이 없는 일반 리소스 오류를 장애로 변환한다")
  void mapsBroadResourceFailuresOnlyWithoutSqlStateOrSemanticNegative() {
    Fixture fixture = fixture();
    IssuedSession issued = issued(fixture.uuid(), 17L);
    when(fixture.sessions.rotate("old-refresh")).thenReturn(issued);
    when(fixture.users.findByUuid(fixture.uuid()))
        .thenThrow(new TransientDataAccessResourceException("private"));

    assertCode(
        () -> fixture.service().refresh("old-refresh"), AuthErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  @Test
  @DisplayName("알 수 없는 조회 오류와 JWT 오류를 그대로 전파한다")
  void leavesUnknownLookupFailuresAndJwtFailuresUntouched() {
    Fixture lookup = fixture();
    IssuedSession issued = issued(lookup.uuid(), 17L);
    RuntimeException lookupFailure = new IllegalStateException("private");
    when(lookup.sessions.rotate("old-refresh")).thenReturn(issued);
    when(lookup.users.findByUuid(lookup.uuid())).thenThrow(lookupFailure);

    assertThatThrownBy(() -> lookup.service().refresh("old-refresh")).isSameAs(lookupFailure);

    Fixture jwt = fixture();
    IssuedSession jwtIssued = issued(jwt.uuid(), 17L);
    RuntimeException jwtFailure = new IllegalStateException("private");
    when(jwt.sessions.rotate("old-refresh")).thenReturn(jwtIssued);
    User jwtUser = activeUser(17L);
    when(jwt.users.findByUuid(jwt.uuid())).thenReturn(Optional.of(jwtUser));
    when(jwt.jwt.issue(jwtIssued.session())).thenThrow(jwtFailure);

    assertThatThrownBy(() -> jwt.service().refresh("old-refresh")).isSameAs(jwtFailure);
  }

  @Test
  @DisplayName("보상 실패 시 최초 오류를 유지하며 명시적 세션 폐기 실패는 전파한다")
  void keepsThePrimaryFailureWhenCompensationFailsAndPropagatesExplicitRevokeFailure() {
    Fixture fixture = fixture();
    IssuedSession issued = issued(fixture.uuid(), 17L);
    RuntimeException primary = new IllegalStateException("primary-private");
    when(fixture.sessions.rotate("old-refresh")).thenReturn(issued);
    when(fixture.users.findByUuid(fixture.uuid())).thenThrow(primary);
    doThrow(new IllegalStateException("cleanup-private"))
        .when(fixture.sessions)
        .revoke(issued.session().id());

    assertThatThrownBy(() -> fixture.service().refresh("old-refresh")).isSameAs(primary);

    LoginGrant grant = new LoginGrant(issued.session(), "access", "refresh");
    RuntimeException explicit = new IllegalStateException("explicit-private");
    doThrow(explicit).when(fixture.sessions).revoke(issued.session().id());
    assertThatThrownBy(() -> fixture.service().revoke(grant)).isSameAs(explicit);
  }

  @Test
  @DisplayName("예외 객체와 민감값을 제외한 이벤트만 로그에 남긴다")
  void logsSanitizedEventsWithoutThrowableOrSensitiveValues() {
    Fixture fixture = fixture();
    IssuedSession issued = issued(fixture.uuid(), 17L);
    String secret = "token digest cookie sid user provider private-message";
    when(fixture.sessions.rotate(secret)).thenReturn(issued);
    when(fixture.users.findByUuid(fixture.uuid()))
        .thenThrow(new DataAccessResourceFailureException(secret));
    doThrow(new IllegalStateException(secret)).when(fixture.sessions).revoke(issued.session().id());
    Logger logger = (Logger) LoggerFactory.getLogger(LoginSessionService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertCode(() -> fixture.service().refresh(secret), AuthErrorCode.AUTH_STATE_UNAVAILABLE);
      assertThat(appender.list)
          .hasSize(2)
          .allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).doesNotContain(secret);
          });
      assertThat(appender.list.get(0).getFormattedMessage())
          .contains("auth-member-state-unavailable");
      assertThat(appender.list.get(1).getFormattedMessage())
          .contains("auth-session-cleanup-failed");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @ParameterizedTest
  @DisplayName("유효하지 않은 클레임은 세션이나 회원을 조회하기 전에 거절한다")
  @ValueSource(
      strings = {
        "null-token",
        "missing-subject",
        "invalid-subject",
        "missing-sid",
        "blank-sid",
        "invalid-sid-type"
      })
  void authorizeRejectsInvalidClaimsWithoutAccessingSessionOrMember(String invalidCase) {
    Fixture fixture = fixture();
    Jwt token = "null-token".equals(invalidCase) ? null : mock(Jwt.class);
    if (token != null) {
      String subject =
          switch (invalidCase) {
            case "missing-subject" -> null;
            case "invalid-subject" -> "not-a-uuid";
            default -> fixture.uuid().toString();
          };
      when(token.getSubject()).thenReturn(subject);
      if ("invalid-sid-type".equals(invalidCase)) {
        when(token.getClaimAsString("sid")).thenThrow(new IllegalArgumentException("invalid sid"));
      } else {
        when(token.getClaimAsString("sid"))
            .thenReturn("missing-sid".equals(invalidCase) ? null : " ");
      }
    }

    assertCode(() -> fixture.service().authorize(token), AuthErrorCode.AUTHENTICATION_REQUIRED);
    verifyNoInteractions(fixture.sessions(), fixture.users(), fixture.jwt());
  }

  @Test
  @DisplayName("인가의 예상치 못한 NullPointerException을 그대로 전파한다")
  void authorizeDoesNotHideUnexpectedNullPointerExceptions() {
    Fixture fixture = fixture();
    Jwt token = mock(Jwt.class);
    NullPointerException failure = new NullPointerException("unexpected failure");
    when(token.getSubject()).thenThrow(failure);

    assertThatThrownBy(() -> fixture.service().authorize(token)).isSameAs(failure);
    verifyNoInteractions(fixture.sessions(), fixture.users(), fixture.jwt());
  }

  @Test
  @DisplayName("활성 Redis 세션을 소유한 DB의 활성 회원만 인가한다")
  void authorizesOnlyAnActiveDatabaseUserOwningTheActiveRedisSession() {
    Fixture fixture = fixture();
    LoginSession session = session(fixture.uuid(), 17L);
    Jwt token = token(fixture.uuid());
    when(fixture.sessions.findActive("sid", fixture.uuid())).thenReturn(Optional.of(session));
    User active = activeUser(17L);
    when(fixture.users.findByUuid(fixture.uuid())).thenReturn(Optional.of(active));

    assertThatCode(() -> fixture.service().authorize(token)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("인가의 가용성 오류를 변환하고 Error는 그대로 전파한다")
  void mapsAvailabilityFailureFromAuthorizeButDoesNotCatchErrors() {
    Fixture fixture = fixture();
    Jwt token = token(fixture.uuid());
    when(fixture.sessions.findActive("sid", fixture.uuid()))
        .thenReturn(Optional.of(session(fixture.uuid(), 17L)));
    when(fixture.users.findByUuid(fixture.uuid())).thenThrow(new QueryTimeoutException("private"));

    assertCode(() -> fixture.service().authorize(token), AuthErrorCode.AUTH_STATE_UNAVAILABLE);

    Fixture errorFixture = fixture();
    Jwt errorToken = token(errorFixture.uuid());
    AssertionError error = new AssertionError("private");
    when(errorFixture.sessions.findActive("sid", errorFixture.uuid()))
        .thenReturn(Optional.of(session(errorFixture.uuid(), 17L)));
    when(errorFixture.users.findByUuid(errorFixture.uuid())).thenThrow(error);
    assertThatThrownBy(() -> errorFixture.service().authorize(errorToken)).isSameAs(error);
  }

  @Test
  @DisplayName("원인에 쿼리 시간 초과가 있어도 도메인 거절을 그대로 전파한다")
  void preservesSemanticNegativeEvenWhenTheChainAlsoContainsAQueryTimeout() {
    Fixture fixture = fixture();
    IssuedSession issued = issued(fixture.uuid(), 17L);
    RuntimeException failure =
        new QueryTimeoutException("private", new DataIntegrityViolationException("private"));
    when(fixture.sessions.rotate("old-refresh")).thenReturn(issued);
    when(fixture.users.findByUuid(fixture.uuid())).thenThrow(failure);

    assertThatThrownBy(() -> fixture.service().refresh("old-refresh")).isSameAs(failure);
  }

  @Test
  @DisplayName("허용된 SQLState를 인식하고 예외 원인의 순환 탐색을 종료한다")
  void recognizesAllAllowedSqlStatesAndTerminatesCauseCycles() {
    for (String sqlState : new String[] {"08006", "53300", "57P03"}) {
      Fixture fixture = fixture();
      IssuedSession issued = issued(fixture.uuid(), 17L);
      when(fixture.sessions.rotate("old-refresh")).thenReturn(issued);
      when(fixture.users.findByUuid(fixture.uuid()))
          .thenThrow(new RuntimeException(new SQLException("private", sqlState)));

      assertCode(
          () -> fixture.service().refresh("old-refresh"), AuthErrorCode.AUTH_STATE_UNAVAILABLE);
    }

    Fixture cycleFixture = fixture();
    IssuedSession cycleIssued = issued(cycleFixture.uuid(), 17L);
    RuntimeException first = new RuntimeException("private");
    RuntimeException second = new RuntimeException("private");
    first.initCause(second);
    second.initCause(first);
    when(cycleFixture.sessions.rotate("old-refresh")).thenReturn(cycleIssued);
    when(cycleFixture.users.findByUuid(cycleFixture.uuid())).thenThrow(first);

    assertThatThrownBy(() -> cycleFixture.service().refresh("old-refresh")).isSameAs(first);
  }

  @Test
  @DisplayName("로그인 중 최초 런타임 예외를 유지하고 Error는 그대로 전파한다")
  void loginPreservesItsPrimaryRuntimeFailureButDoesNotCatchErrors() {
    Fixture fixture = fixture();
    User user = mock(User.class);
    when(user.getId()).thenReturn(17L);
    when(user.getUuid()).thenReturn(fixture.uuid());
    when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
    IssuedSession issued = issued(fixture.uuid(), 17L);
    RuntimeException primary = new IllegalStateException("private");
    when(fixture.sessions.issue(17L, fixture.uuid())).thenReturn(issued);
    when(fixture.jwt.issue(issued.session())).thenThrow(primary);
    doThrow(new IllegalStateException("private"))
        .when(fixture.sessions)
        .revoke(issued.session().id());

    assertThatThrownBy(() -> fixture.service().login(user)).isSameAs(primary);

    Fixture errorFixture = fixture();
    User errorUser = mock(User.class);
    when(errorUser.getId()).thenReturn(17L);
    when(errorUser.getUuid()).thenReturn(errorFixture.uuid());
    when(errorUser.getStatus()).thenReturn(UserStatus.ACTIVE);
    IssuedSession errorIssued = issued(errorFixture.uuid(), 17L);
    AssertionError error = new AssertionError("private");
    when(errorFixture.sessions.issue(17L, errorFixture.uuid())).thenReturn(errorIssued);
    when(errorFixture.jwt.issue(errorIssued.session())).thenThrow(error);

    assertThatThrownBy(() -> errorFixture.service().login(errorUser)).isSameAs(error);
    verify(errorFixture.sessions, never()).revoke(errorIssued.session().id());
  }

  @Test
  @DisplayName("Hibernate 방언은 연결 오류와 쿼리 시간 초과를 변환한다")
  void hibernateDialectTranslatesConnectionAndQueryTimeoutExceptions() {
    HibernateJpaDialect dialect = new HibernateJpaDialect();

    assertThat(
            dialect.translateExceptionIfPossible(
                new JDBCConnectionException(
                    "private", new SQLException("private", "08006"), "sql")))
        .isInstanceOf(DataAccessResourceFailureException.class);
    assertThat(
            dialect.translateExceptionIfPossible(
                new org.hibernate.QueryTimeoutException(
                    "private", new SQLException("private", "57014"), "sql")))
        .isInstanceOf(QueryTimeoutException.class);
  }

  private static Fixture fixture() {
    RedisRefreshSessionStore sessions = mock(RedisRefreshSessionStore.class);
    JwtTokenService jwt = mock(JwtTokenService.class);
    UserRepository users = mock(UserRepository.class);
    return new Fixture(
        UUID.randomUUID(), sessions, jwt, users, new LoginSessionService(sessions, jwt, users));
  }

  private static IssuedSession issued(UUID uuid, long userId) {
    return new IssuedSession(session(uuid, userId), "new-refresh");
  }

  private static LoginSession session(UUID uuid, long userId) {
    return new LoginSession("sid", userId, uuid, Instant.EPOCH, Instant.MAX, Instant.MAX);
  }

  private static Jwt token(UUID uuid) {
    Jwt token = mock(Jwt.class);
    when(token.getSubject()).thenReturn(uuid.toString());
    when(token.getClaimAsString("sid")).thenReturn("sid");
    return token;
  }

  private static User activeUser(long id) {
    User user = mock(User.class);
    when(user.getId()).thenReturn(id);
    when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
    return user;
  }

  private static void assertCode(ThrowingCallable action, AuthErrorCode expected) {
    assertThatThrownBy(action)
        .isInstanceOf(DomainException.class)
        .extracting(exception -> ((DomainException) exception).getErrorCode())
        .isEqualTo(expected);
  }

  private record Fixture(
      UUID uuid,
      RedisRefreshSessionStore sessions,
      JwtTokenService jwt,
      UserRepository users,
      LoginSessionService service) {}
}

package org.kirikick.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

  @Test
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

package org.kirikick.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.domain.SignupApplication;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.repository.SignupApplicationRepository;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.support.JpaSliceTest;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-only transaction and locking proofs for signup completion. */
@JpaSliceTest
@Import({
  SignupApplicationCompletionService.class,
  SignupApplicationCompletionServicePostgresTest.ClockConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SignupApplicationCompletionServicePostgresTest {
  private static final Instant BEFORE_EXPIRY = Instant.parse("2030-01-01T00:00:00Z");

  @Autowired private SignupApplicationCompletionService service;
  @Autowired private SignupApplicationRepository applications;
  @Autowired private DataSource dataSource;
  @Autowired private CountingClock clock;

  private final String subjectPrefix = "signup-pg-" + UUID.randomUUID();
  private final List<String> subjects = new ArrayList<>();
  private final List<String> triggerNames = new ArrayList<>();
  private long initialUserCount;

  @BeforeEach
  void resetClockAndCaptureUserBaseline() throws SQLException {
    clock.reset(BEFORE_EXPIRY);
    initialUserCount = count("select count(*) from users");
  }

  @AfterEach
  void removeOnlyRowsCreatedByThisTest() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      for (String triggerName : triggerNames) {
        execute(connection, "drop trigger if exists " + triggerName + " on signup_applications");
        execute(connection, "drop function if exists " + triggerName + "_function()");
      }
      for (String subject : subjects) {
        List<Long> userIds =
            queryLongs(
                connection,
                "select user_id from oauth_identities where provider_subject = ?",
                subject);
        update(connection, "delete from signup_applications where provider_subject = ?", subject);
        update(connection, "delete from oauth_identities where provider_subject = ?", subject);
        for (Long userId : userIds) {
          update(connection, "delete from users where id = ?", userId);
        }
      }
    }
  }

  @Test
  void serializesSameApplicationAndCommitsOneCompletionThroughTheSpringProxy() throws Exception {
    SignupApplication application = pending("same-app");
    assertThat(AopUtils.isAopProxy(service)).isTrue();

    try (ExecutorService executor = Executors.newFixedThreadPool(2);
        Connection holder = holder(application.getId())) {
      long holderPid = backendPid(holder);
      Future<SignupApplicationCompletionService.CompletionResult> first =
          executor.submit(() -> complete(application, "Name"));
      Future<SignupApplicationCompletionService.CompletionResult> second =
          executor.submit(() -> complete(application, "Name"));
      awaitBlockedBy(holderPid, 2);
      holder.commit();

      SignupApplicationCompletionService.CompletionResult firstResult = get(first);
      SignupApplicationCompletionService.CompletionResult secondResult = get(second);
      assertThat(List.of(firstResult.newlyCompleted(), secondResult.newlyCompleted()))
          .containsExactlyInAnyOrder(true, false);
      assertThat(firstResult.userUuid()).isEqualTo(secondResult.userUuid());
    }

    assertThat(count("select count(*) from users")).isEqualTo(initialUserCount + 1);
    assertThat(
            count(
                "select count(*) from oauth_identities where provider_subject = ?",
                application.getProviderSubject()))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from signup_applications where id = ? and completed_user_id is not null",
                application.getId()))
        .isEqualTo(1);
  }

  @Test
  void rollsBackUserIdentityAndResultWhenFinalApplicationUpdateFails() throws Exception {
    SignupApplication application = pending("final-update");
    String triggerName = "signup_final_failure_" + UUID.randomUUID().toString().replace("-", "");
    triggerNames.add(triggerName);
    installFinalUpdateFailureTrigger(triggerName);

    assertThatThrownBy(() -> complete(application, "Name"))
        .isInstanceOf(RuntimeException.class)
        .satisfies(error ->
            assertThat(rootCause(error).getMessage())
                .contains("TEST_SIGNUP_FINAL_UPDATE_FAILURE")
                .doesNotContain("TEST_SIGNUP_FINAL_UPDATE_MISSING_USER")
                .doesNotContain("TEST_SIGNUP_FINAL_UPDATE_MISSING_IDENTITY"));

    assertThat(count("select count(*) from users")).isEqualTo(initialUserCount);
    assertThat(
            count(
                "select count(*) from oauth_identities where provider_subject = ?",
                application.getProviderSubject()))
        .isZero();
    assertThat(
            count(
                "select count(*) from signup_applications where id = ? and completed_user_id is not null",
                application.getId()))
        .isZero();
  }

  @Test
  void returnsTheOriginalResultWithoutCreatingRowsWhenTheCallerLostTheFirstResponse()
      throws Exception {
    SignupApplication application = pending("lost-response");
    SignupApplicationCompletionService.CompletionResult initial = complete(application, "Name");

    SignupApplicationCompletionService.CompletionResult retry = complete(application, "Name");

    assertThat(retry.applicationId()).isEqualTo(application.getId());
    assertThat(retry.userUuid()).isEqualTo(initial.userUuid());
    assertThat(retry.newlyCompleted()).isFalse();
    assertThat(count("select count(*) from users")).isEqualTo(initialUserCount + 1);
    assertThat(
            count(
                "select count(*) from oauth_identities where provider_subject = ?",
                application.getProviderSubject()))
        .isEqualTo(1);
  }

  @Test
  void rollsBackTheUniqueConstraintLoserForTwoApplicationsWithTheSameIdentity() throws Exception {
    String subject = subject("cross-app");
    SignupApplication held = pending(subject);
    SignupApplication winner = pending(subject);

    try (ExecutorService executor = Executors.newFixedThreadPool(2);
        Connection holder = holder(held.getId(), winner.getId())) {
      long holderPid = backendPid(holder);
      Future<SignupApplicationCompletionService.CompletionResult> first =
          executor.submit(() -> complete(held, "First"));
      Future<SignupApplicationCompletionService.CompletionResult> second =
          executor.submit(() -> complete(winner, "Second"));
      awaitBlockedBy(holderPid, 2);
      holder.commit();

      assertThat(completedFutureCount(List.of(first, second))).isEqualTo(1);
    }

    assertThat(count("select count(*) from users")).isEqualTo(initialUserCount + 1);
    assertThat(count("select count(*) from oauth_identities where provider_subject = ?", subject))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from signup_applications where id in (?, ?) and completed_user_id is not null",
                held.getId(),
                winner.getId()))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from signup_applications where id in (?, ?) and completed_user_id is null",
                held.getId(),
                winner.getId()))
        .isEqualTo(1);
  }

  @Test
  void samplesTheClockOnlyAfterTheRowLockAndRejectsAnApplicationThatExpiresWhileWaiting()
      throws Exception {
    SignupApplication application = pending("expires-while-waiting");
    clock.set(BEFORE_EXPIRY);

    try (ExecutorService executor = Executors.newSingleThreadExecutor();
        Connection holder = holder(application.getId())) {
      long holderPid = backendPid(holder);
      Future<SignupApplicationCompletionService.CompletionResult> completion =
          executor.submit(() -> complete(application, "Name"));
      awaitBlockedBy(holderPid, 1);
      assertThat(clock.calls()).isZero();
      clock.set(application.getExpiresAt());
      holder.commit();

      assertThatThrownBy(() -> get(completion))
          .isInstanceOf(DomainException.class)
          .extracting(error -> ((DomainException) error).getErrorCode())
          .isEqualTo(AuthErrorCode.SIGNUP_APPLICATION_EXPIRED);
    }

    assertThat(clock.calls()).isEqualTo(1);
    assertThat(count("select count(*) from users")).isEqualTo(initialUserCount);
    assertThat(
            count(
                "select count(*) from oauth_identities where provider_subject = ?",
                application.getProviderSubject()))
        .isZero();
  }

  @Test
  void rejectsWrongTrustedIdentityWithoutWriting() throws Exception {
    SignupApplication application = pending("identity-mismatch");

    assertThatThrownBy(() ->
            service.complete(
                new SignupApplicationCompletionService.CompletionCommand(
                    application.getId(),
                    OAuthProvider.APPLE,
                    application.getProviderSubject(),
                    "Name")))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).getErrorCode())
        .isEqualTo(AuthErrorCode.SIGNUP_APPLICATION_IDENTITY_MISMATCH);

    assertThat(count("select count(*) from users")).isEqualTo(initialUserCount);
    assertThat(
            count(
                "select count(*) from oauth_identities where provider_subject = ?",
                application.getProviderSubject()))
        .isZero();
  }

  @Test
  void preservesTheCompletedResultWhenTheRetryDisplayNameDiffersExactly() throws Exception {
    SignupApplication application = pending("exact-display-name");
    SignupApplicationCompletionService.CompletionResult initial = complete(application, "Name");

    assertThatThrownBy(() -> complete(application, "Name "))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).getErrorCode())
        .isEqualTo(AuthErrorCode.SIGNUP_APPLICATION_COMPLETED_INPUT_MISMATCH);

    assertThat(count("select count(*) from users")).isEqualTo(initialUserCount + 1);
    assertThat(
            count(
                "select count(*) from oauth_identities where provider_subject = ?",
                application.getProviderSubject()))
        .isEqualTo(1);
    assertThat(
            queryUuid(
                "select uuid from users where id = (select completed_user_id from signup_applications where id = ?)",
                application.getId()))
        .isEqualTo(initial.userUuid());
    assertThat(
            queryString(
                "select completed_display_name from signup_applications where id = ?",
                application.getId()))
        .isEqualTo("Name");
  }

  private SignupApplication pending(String suffix) {
    String subject = suffix.startsWith(subjectPrefix) ? suffix : subject(suffix);
    SignupApplication application =
        SignupApplication.open(
            UUID.randomUUID(), OAuthProvider.KAKAO, subject, BEFORE_EXPIRY.minusSeconds(1));
    applications.saveAndFlush(application);
    return application;
  }

  private String subject(String suffix) {
    String subject = subjectPrefix + "-" + suffix;
    subjects.add(subject);
    return subject;
  }

  private SignupApplicationCompletionService.CompletionResult complete(
      SignupApplication application, String displayName) {
    return service.complete(
        new SignupApplicationCompletionService.CompletionCommand(
            application.getId(),
            application.getProvider(),
            application.getProviderSubject(),
            displayName));
  }

  private Connection holder(UUID... applicationIds) throws SQLException {
    Connection connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    try (PreparedStatement statement =
        connection.prepareStatement("select id from signup_applications where id = ? for update")) {
      for (UUID applicationId : applicationIds) {
        statement.setObject(1, applicationId);
        statement.executeQuery();
      }
    }
    return connection;
  }

  private long backendPid(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("select pg_backend_pid()")) {
      result.next();
      return result.getLong(1);
    }
  }

  private void awaitBlockedBy(long holderPid, int expectedWaiters) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (lockWaitersBlockedBy(holderPid) >= expectedWaiters) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("No PostgreSQL lock waiter rooted at the test holder PID");
  }

  private long lockWaitersBlockedBy(long holderPid) throws SQLException {
    String sql =
        "with recursive blockers(waiter_pid, blocker_pid) as ("
            + " select a.pid, blocked.pid from pg_stat_activity a "
            + " cross join lateral unnest(pg_blocking_pids(a.pid)) blocked(pid) "
            + " where a.wait_event_type = 'Lock' and a.query like '%signup_applications%'"
            + " union select b.waiter_pid, blocked.pid from blockers b "
            + " cross join lateral unnest(pg_blocking_pids(b.blocker_pid)) blocked(pid)"
            + ") select count(distinct waiter_pid) from blockers where blocker_pid = ?";
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, holderPid);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private void installFinalUpdateFailureTrigger(String triggerName) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      execute(
          connection,
          "create function "
              + triggerName
              + "_function() returns trigger language plpgsql as $$ begin "
              + "if new.completed_user_id is not null then "
              + "if not exists (select 1 from users where id = new.completed_user_id) then "
              + "raise exception 'TEST_SIGNUP_FINAL_UPDATE_MISSING_USER'; end if; "
              + "if not exists (select 1 from oauth_identities where user_id = new.completed_user_id "
              + "and provider = new.provider and provider_subject = new.provider_subject) then "
              + "raise exception 'TEST_SIGNUP_FINAL_UPDATE_MISSING_IDENTITY'; end if; "
              + "raise exception 'TEST_SIGNUP_FINAL_UPDATE_FAILURE'; end if; return new; end $$");
      execute(
          connection,
          "create trigger "
              + triggerName
              + " before update of completed_user_id on signup_applications for each row execute function "
              + triggerName
              + "_function()");
    }
  }

  private long count(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private UUID queryUuid(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return (UUID) result.getObject(1);
      }
    }
  }

  private String queryString(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getString(1);
      }
    }
  }

  private List<Long> queryLongs(Connection connection, String sql, String parameter)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, parameter);
      try (ResultSet result = statement.executeQuery()) {
        List<Long> values = new ArrayList<>();
        while (result.next()) {
          values.add(result.getLong(1));
        }
        return values;
      }
    }
  }

  private void update(Connection connection, String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      statement.executeUpdate();
    }
  }

  private void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private void bind(PreparedStatement statement, Object... parameters) throws SQLException {
    for (int index = 0; index < parameters.length; index++) {
      statement.setObject(index + 1, parameters[index]);
    }
  }

  private <T> T get(Future<T> future) throws Exception {
    try {
      return future.get(10, TimeUnit.SECONDS);
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw exception;
    }
  }

  private int completedFutureCount(
      List<Future<SignupApplicationCompletionService.CompletionResult>> futures) throws Exception {
    int completed = 0;
    int uniqueConstraintFailures = 0;
    for (Future<SignupApplicationCompletionService.CompletionResult> future : futures) {
      try {
        assertThat(get(future).newlyCompleted()).isTrue();
        completed++;
      } catch (RuntimeException expectedUniqueConstraintFailure) {
        Throwable root = rootCause(expectedUniqueConstraintFailure);
        assertThat(root).isInstanceOf(SQLException.class);
        assertThat(((SQLException) root).getSQLState()).isEqualTo("23505");
        uniqueConstraintFailures++;
      }
    }
    assertThat(uniqueConstraintFailures).isEqualTo(1);
    return completed;
  }

  private Throwable rootCause(Throwable error) {
    Throwable root = error;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root;
  }

  @TestConfiguration
  static class ClockConfiguration {
    @Bean
    CountingClock clock() {
      return new CountingClock();
    }
  }

  static final class CountingClock extends Clock {
    private Instant instant = BEFORE_EXPIRY;
    private int calls;

    synchronized void set(Instant instant) {
      this.instant = instant;
    }

    synchronized int calls() {
      return calls;
    }

    @Override
    public synchronized Instant instant() {
      calls++;
      return instant;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    synchronized void reset(Instant instant) {
      this.instant = instant;
      calls = 0;
    }
  }
}

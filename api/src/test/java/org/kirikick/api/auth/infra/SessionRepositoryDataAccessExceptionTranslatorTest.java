package org.kirikick.api.auth.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;

class SessionRepositoryDataAccessExceptionTranslatorTest {

  @Test
  void translatesDelegateDataAccessFailuresForEverySessionOperation() {
    SessionRepository<MapSession> delegate = delegate();
    SessionRepository<MapSession> repository =
        new SessionRepositoryDataAccessExceptionTranslator<>(delegate);
    DataAccessResourceFailureException failure =
        new DataAccessResourceFailureException("redis down");

    when(delegate.createSession()).thenThrow(failure);
    assertUnavailable(() -> repository.createSession(), failure);

    when(delegate.findById("session")).thenThrow(failure);
    assertUnavailable(() -> repository.findById("session"), failure);

    doThrow(failure).when(delegate).save(any(MapSession.class));
    assertUnavailable(() -> repository.save(new MapSession()), failure);

    doThrow(failure).when(delegate).deleteById("session");
    assertUnavailable(() -> repository.deleteById("session"), failure);
  }

  @Test
  void leavesNonDataAccessFailuresUntouched() {
    SessionRepository<MapSession> delegate = delegate();
    SessionRepository<MapSession> repository =
        new SessionRepositoryDataAccessExceptionTranslator<>(delegate);
    IllegalStateException failure = new IllegalStateException("not redis");
    when(delegate.findById("session")).thenThrow(failure);

    assertThatThrownBy(() -> repository.findById("session")).isSameAs(failure);
  }

  private static SessionRepository<MapSession> delegate() {
    return mock(SessionRepository.class);
  }

  private static void assertUnavailable(ThrowingCallable call, Throwable cause) {
    assertThatThrownBy(call)
        .isInstanceOf(SessionStateUnavailableException.class)
        .satisfies(exception -> assertThat(exception.getCause()).isSameAs(cause));
  }
}

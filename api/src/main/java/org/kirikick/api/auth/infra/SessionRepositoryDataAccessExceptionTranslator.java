package org.kirikick.api.auth.infra;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

@RequiredArgsConstructor
public final class SessionRepositoryDataAccessExceptionTranslator<S extends Session>
    implements SessionRepository<S> {

  private final SessionRepository<S> delegate;

  @Override
  public S createSession() {
    try {
      return delegate.createSession();
    } catch (DataAccessException exception) {
      throw unavailable(exception);
    }
  }

  @Override
  public void save(S session) {
    try {
      delegate.save(session);
    } catch (DataAccessException exception) {
      throw unavailable(exception);
    }
  }

  @Override
  public S findById(String id) {
    try {
      return delegate.findById(id);
    } catch (DataAccessException exception) {
      throw unavailable(exception);
    }
  }

  @Override
  public void deleteById(String id) {
    try {
      delegate.deleteById(id);
    } catch (DataAccessException exception) {
      throw unavailable(exception);
    }
  }

  private SessionStateUnavailableException unavailable(DataAccessException exception) {
    return new SessionStateUnavailableException(exception);
  }
}

package org.kirikick.api.auth.service;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AuthStateAvailability {

  public static boolean isFailure(RuntimeException exception) {
    boolean queryTimeout = false;
    boolean allowedSqlState = false;
    boolean transientConnectionWithoutSqlState = false;
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
      transientConnectionWithoutSqlState |=
          current instanceof SQLTransientConnectionException connectionException
              && connectionException.getSQLState() == null;
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
    if (queryTimeout || allowedSqlState || transientConnectionWithoutSqlState) {
      return true;
    }
    return !unsupportedSqlState && resourceFailure;
  }

  private static boolean isSemanticNegative(Throwable exception) {
    return exception instanceof DataIntegrityViolationException
        || exception instanceof InvalidDataAccessResourceUsageException
        || exception instanceof InvalidDataAccessApiUsageException
        || exception instanceof ConcurrencyFailureException;
  }
}

package org.kirikick.api.publication.infra.model;

import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;

enum PostgresBackendError {
  ABSENT,
  PRESENT,
  UNAVAILABLE;

  private static final String PG_EXCEPTION = "org.postgresql.util.PSQLException";

  static PostgresBackendError from(SQLException exception) {
    if (exception == null) {
      return ABSENT;
    }

    ArrayDeque<Throwable> pending = new ArrayDeque<>();
    IdentityHashMap<Throwable, Boolean> visited = new IdentityHashMap<>();
    boolean unavailable = false;
    pending.add(exception);
    while (!pending.isEmpty()) {
      Throwable current = pending.removeFirst();
      if (visited.put(current, Boolean.TRUE) != null) {
        continue;
      }
      if (PG_EXCEPTION.equals(current.getClass().getName())) {
        try {
          Object error = current.getClass().getMethod("getServerErrorMessage").invoke(current);
          if (error != null) {
            return PRESENT;
          }
        } catch (IllegalAccessException
            | InvocationTargetException
            | NoSuchMethodException
            | SecurityException ignored) {
          unavailable = true;
        }
      }
      if (current.getCause() != null) {
        pending.addLast(current.getCause());
      }
      if (current instanceof SQLException sql && sql.getNextException() != null) {
        pending.addLast(sql.getNextException());
      }
    }
    return unavailable ? UNAVAILABLE : ABSENT;
  }
}

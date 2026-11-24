package org.kirikick.api.publication.infra;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Observes only the physical commit invoked while a publication transaction is active. */
public final class PublicationCommitObserver {
  private static final String PG_EXCEPTION = "org.postgresql.util.PSQLException";
  private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();

  static boolean isActive() {
    return ACTIVE.get() != null;
  }

  public <T> T observe(Supplier<T> transaction) {
    Objects.requireNonNull(transaction);
    if (ACTIVE.get() != null) {
      // shortcut: 현재 출제 경로는 관측 중 별도 중첩 트랜잭션을 커밋하지 않아 기존 동작을 유지한다.
      // 중첩 관측을 지원하려면 COMMIT 증거와 복구 멱등키의 소유권을 트랜잭션별로 분리해야 한다.
      return transaction.get();
    }

    Scope scope = new Scope();
    ACTIVE.set(scope);
    try {
      return transaction.get();
    } catch (RuntimeException exception) {
      Snapshot snapshot = scope.snapshot(exception);
      if (snapshot.isOutcomeUnknown()) {
        throw new OutcomeUnknownException(exception, snapshot);
      }
      throw exception;
    } finally {
      ACTIVE.remove();
    }
  }

  static void commitEntered() {
    Scope scope = ACTIVE.get();
    if (scope != null) {
      scope.entered = true;
    }
  }

  static void commitReturned() {
    Scope scope = ACTIVE.get();
    if (scope != null) {
      scope.returned = true;
    }
  }

  static void commitFailed(SQLException exception) {
    Scope scope = ACTIVE.get();
    if (scope != null) {
      scope.commitFailure = exception;
    }
  }

  public record Snapshot(
      boolean commitEntered,
      boolean commitReturned,
      boolean failureCorrelated,
      boolean transportFailure,
      boolean parsedBackendError,
      boolean backendErrorReadable) {
    private boolean isOutcomeUnknown() {
      return commitEntered
          && !commitReturned
          && failureCorrelated
          && transportFailure
          && !parsedBackendError
          && backendErrorReadable;
    }
  }

  public static final class OutcomeUnknownException extends RuntimeException {
    private final Snapshot snapshot;

    OutcomeUnknownException(RuntimeException cause, Snapshot snapshot) {
      super("출제 커밋 결과를 확인할 수 없습니다.", cause);
      this.snapshot = snapshot;
    }

    public Snapshot snapshot() {
      return snapshot;
    }
  }

  private static final class Scope {
    private boolean entered;
    private boolean returned;
    private SQLException commitFailure;

    private Snapshot snapshot(Throwable outerFailure) {
      boolean correlated = commitFailure != null && containsIdentity(outerFailure, commitFailure);
      boolean transport = commitFailure != null && containsIOException(commitFailure);
      BackendError backendError =
          commitFailure == null ? BackendError.ABSENT : backendError(commitFailure);
      return new Snapshot(
          entered,
          returned,
          correlated,
          transport,
          backendError == BackendError.PRESENT,
          backendError != BackendError.UNAVAILABLE);
    }
  }

  private enum BackendError {
    ABSENT,
    PRESENT,
    UNAVAILABLE
  }

  private static BackendError backendError(SQLException exception) {
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
            return BackendError.PRESENT;
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
    return unavailable ? BackendError.UNAVAILABLE : BackendError.ABSENT;
  }

  private static boolean containsIdentity(Throwable root, Throwable expected) {
    return walk(root, candidate -> candidate == expected);
  }

  private static boolean containsIOException(Throwable root) {
    return walk(root, IOException.class::isInstance);
  }

  private static boolean walk(Throwable root, Predicate<Throwable> match) {
    ArrayDeque<Throwable> pending = new ArrayDeque<>();
    IdentityHashMap<Throwable, Boolean> visited = new IdentityHashMap<>();
    if (root != null) {
      pending.add(root);
    }
    while (!pending.isEmpty()) {
      Throwable current = pending.removeFirst();
      if (visited.put(current, Boolean.TRUE) != null) {
        continue;
      }
      if (match.test(current)) {
        return true;
      }
      if (current.getCause() != null) {
        pending.addLast(current.getCause());
      }
      if (current instanceof SQLException sql && sql.getNextException() != null) {
        pending.addLast(sql.getNextException());
      }
    }
    return false;
  }
}

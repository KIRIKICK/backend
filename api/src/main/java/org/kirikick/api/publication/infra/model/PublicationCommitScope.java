package org.kirikick.api.publication.infra.model;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.function.Predicate;

public final class PublicationCommitScope {
  private boolean entered;
  private boolean returned;
  private SQLException commitFailure;

  public void commitEntered() {
    entered = true;
  }

  public void commitReturned() {
    returned = true;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The original JDBC exception is required for identity correlation.")
  public void commitFailed(SQLException exception) {
    commitFailure = exception;
  }

  public PublicationCommitSnapshot snapshot(Throwable outerFailure) {
    boolean correlated = commitFailure != null && containsIdentity(outerFailure, commitFailure);
    boolean transport = commitFailure != null && containsIOException(commitFailure);
    PostgresBackendError backendError = PostgresBackendError.from(commitFailure);
    return new PublicationCommitSnapshot(
        entered,
        returned,
        correlated,
        transport,
        backendError == PostgresBackendError.PRESENT,
        backendError != PostgresBackendError.UNAVAILABLE);
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

package org.kirikick.api.publication.infra;

import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;
import org.kirikick.api.publication.infra.exception.PublicationCommitOutcomeUnknownException;
import org.kirikick.api.publication.infra.model.PublicationCommitScope;
import org.kirikick.api.publication.infra.model.PublicationCommitSnapshot;

/** Observes only the physical commit invoked while a publication transaction is active. */
public final class PublicationCommitObserver {
  private static final ThreadLocal<PublicationCommitScope> ACTIVE = new ThreadLocal<>();

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

    PublicationCommitScope scope = new PublicationCommitScope();
    ACTIVE.set(scope);
    try {
      return transaction.get();
    } catch (RuntimeException exception) {
      PublicationCommitSnapshot snapshot = scope.snapshot(exception);
      if (snapshot.isOutcomeUnknown()) {
        throw new PublicationCommitOutcomeUnknownException(exception, snapshot);
      }
      throw exception;
    } finally {
      ACTIVE.remove();
    }
  }

  static void commitEntered() {
    PublicationCommitScope scope = ACTIVE.get();
    if (scope != null) {
      scope.commitEntered();
    }
  }

  static void commitReturned() {
    PublicationCommitScope scope = ACTIVE.get();
    if (scope != null) {
      scope.commitReturned();
    }
  }

  static void commitFailed(SQLException exception) {
    PublicationCommitScope scope = ACTIVE.get();
    if (scope != null) {
      scope.commitFailed(exception);
    }
  }
}

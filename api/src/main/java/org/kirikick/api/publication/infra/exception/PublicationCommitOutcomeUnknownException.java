package org.kirikick.api.publication.infra.exception;

import org.kirikick.api.publication.infra.model.PublicationCommitSnapshot;

public final class PublicationCommitOutcomeUnknownException extends RuntimeException {
  private final PublicationCommitSnapshot snapshot;

  public PublicationCommitOutcomeUnknownException(
      RuntimeException cause, PublicationCommitSnapshot snapshot) {
    super("출제 커밋 결과를 확인할 수 없습니다.", cause);
    this.snapshot = snapshot;
  }

  public PublicationCommitSnapshot snapshot() {
    return snapshot;
  }
}

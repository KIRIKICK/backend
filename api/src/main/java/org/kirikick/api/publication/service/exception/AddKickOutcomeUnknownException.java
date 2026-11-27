package org.kirikick.api.publication.service.exception;

import org.kirikick.api.publication.infra.exception.PublicationCommitOutcomeUnknownException;

public final class AddKickOutcomeUnknownException extends RuntimeException {

  public AddKickOutcomeUnknownException(PublicationCommitOutcomeUnknownException cause) {
    super("추가 출제 커밋 결과를 확인할 수 없습니다.", cause);
  }
}

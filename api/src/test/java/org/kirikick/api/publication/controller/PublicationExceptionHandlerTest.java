package org.kirikick.api.publication.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.kirikick.api.common.controller.dto.ErrorResponse;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class PublicationExceptionHandlerTest {

  private final PublicationExceptionHandler handler = new PublicationExceptionHandler();

  @Test
  void mapsUnreadablePublicationRecordSeparatelyFromGenericInternalFailure() {
    ResponseEntity<ErrorResponse> unreadable =
        handler.domainException(
            new DomainException(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD));
    ResponseEntity<ErrorResponse> generic = handler.unexpectedFailure();

    assertThat(unreadable.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(unreadable.getBody())
        .isEqualTo(
            new ErrorResponse("PUBLICATION_RECORD_UNAVAILABLE", "저장된 출제 식별 기록을 복원할 수 없습니다."));
    assertThat(generic.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(generic.getBody())
        .isEqualTo(new ErrorResponse("INTERNAL_ERROR", "요청을 처리하는 중 오류가 발생했습니다."));
  }

  @Test
  void usesTheApprovedConflictMessageInsteadOfThePersistenceDetail() {
    ResponseEntity<ErrorResponse> response =
        handler.domainException(new DomainException(PublicationErrorCode.IDEMPOTENCY_KEY_CONFLICT));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(new ErrorResponse("IDEMPOTENCY_KEY_CONFLICT", "같은 요청 키에 다른 출제 입력을 사용할 수 없습니다."));
  }
}

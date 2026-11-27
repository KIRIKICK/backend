package org.kirikick.api.publication.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.common.controller.dto.ErrorResponse;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AddKickExceptionMappingTest {
  private final PublicationExceptionHandler handler = new PublicationExceptionHandler();

  @Test
  @DisplayName("추가 출제의 업무 오류와 복원 불가 기록은 고정 상태와 본문으로 반환한다")
  void mapsKickErrorsToTheirFixedStatusAndKeepsRecordsSeparate() {
    assertStatus(PublicationErrorCode.ROOM_NOT_FOUND, HttpStatus.NOT_FOUND);
    assertStatus(PublicationErrorCode.PUBLICATION_FORBIDDEN, HttpStatus.FORBIDDEN);
    assertStatus(PublicationErrorCode.ROOM_INTAKE_CLOSED, HttpStatus.CONFLICT);
    assertStatus(PublicationErrorCode.QUESTION_ALREADY_PUBLISHED, HttpStatus.CONFLICT);
    ResponseEntity<ErrorResponse> record =
        handler.domainException(
            new DomainException(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD));
    assertThat(record.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(record.getBody().code()).isEqualTo("PUBLICATION_RECORD_UNAVAILABLE");
    assertThat(record.getBody().message())
        .isEqualTo(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD.getMessage());
  }

  @Test
  @DisplayName("첫 출제와 추가 출제의 결과 불명 메시지를 구분하고 재시도 헤더를 보내지 않는다")
  void keepsOpenRoomAndKickOutcomeUnknownMessagesDistinctWithoutRetryAfter() {
    ResponseEntity<ErrorResponse> openRoom = handler.outcomeUnknown();
    ResponseEntity<ErrorResponse> addKick = handler.addKickOutcomeUnknown();
    assertThat(openRoom.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(openRoom.getBody().message()).isEqualTo("출제 결과를 확인할 수 없습니다.");
    assertThat(addKick.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(addKick.getBody().message()).isEqualTo("추가 출제 결과를 확인할 수 없습니다.");
    assertThat(addKick.getHeaders()).doesNotContainKey("Retry-After");
    assertThat(addKick.getBody().code()).isEqualTo("PUBLICATION_UNAVAILABLE");
    assertThat(handler.unexpectedFailure().getBody())
        .isEqualTo(new ErrorResponse("INTERNAL_ERROR", "요청을 처리하는 중 오류가 발생했습니다."));
    assertThat(handler.unexpectedFailure().getStatusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  private void assertStatus(PublicationErrorCode code, HttpStatus status) {
    assertThat(handler.domainException(new DomainException(code)).getBody())
        .isEqualTo(new ErrorResponse(code.name(), code.getMessage()));
    assertThat(handler.domainException(new DomainException(code)).getStatusCode())
        .isEqualTo(status);
  }
}

package org.kirikick.api.publication.controller;

import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.common.controller.dto.ErrorResponse;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.config.PublicationEnabledCondition;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.kirikick.api.publication.infra.exception.PublicationCommitOutcomeUnknownException;
import org.kirikick.api.user.domain.exception.UserErrorCode;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = OpenRoomController.class)
@Conditional(PublicationEnabledCondition.class)
public class OpenRoomExceptionHandler {

  private static final String INTERNAL_ERROR = "INTERNAL_ERROR";
  private static final String INTERNAL_ERROR_MESSAGE = "요청을 처리하는 중 오류가 발생했습니다.";
  private static final String RECORD_UNAVAILABLE = "PUBLICATION_RECORD_UNAVAILABLE";
  private static final String OUTCOME_UNKNOWN = "PUBLICATION_UNAVAILABLE";
  private static final String OUTCOME_UNKNOWN_MESSAGE = "출제 결과를 확인할 수 없습니다.";

  @ExceptionHandler(DomainException.class)
  public ResponseEntity<ErrorResponse> domainException(DomainException exception) {
    if (exception.getErrorCode() == AuthErrorCode.ACCOUNT_INACTIVE) {
      return error(
          HttpStatus.FORBIDDEN, AuthErrorCode.ACCOUNT_INACTIVE.name(), exception.getMessage());
    }
    if (exception.getErrorCode() == UserErrorCode.ACCOUNT_NOT_FOUND) {
      return error(
          HttpStatus.NOT_FOUND, UserErrorCode.ACCOUNT_NOT_FOUND.name(), exception.getMessage());
    }
    if (!(exception.getErrorCode() instanceof PublicationErrorCode code)) {
      return internalError();
    }
    return switch (code) {
      case INVALID_PUBLICATION_REQUEST_KEY ->
          error(HttpStatus.BAD_REQUEST, code.name(), code.getMessage());
      case INVALID_PUBLICATION_TARGET, INVALID_PUBLICATION_CHOICES, INVALID_PUBLICATION_INPUT ->
          error(
              HttpStatus.BAD_REQUEST,
              PublicationErrorCode.INVALID_PUBLICATION_INPUT.name(),
              PublicationErrorCode.INVALID_PUBLICATION_INPUT.getMessage());
      case PRESET_NOT_FOUND -> error(HttpStatus.NOT_FOUND, code.name(), code.getMessage());
      case IDEMPOTENCY_KEY_CONFLICT ->
          error(HttpStatus.CONFLICT, code.name(), "같은 요청 키에 다른 출제 입력을 사용할 수 없습니다.");
      case OPEN_ROOM_EXISTS -> error(HttpStatus.CONFLICT, code.name(), code.getMessage());
      case UNREADABLE_PUBLICATION_RECORD ->
          error(HttpStatus.INTERNAL_SERVER_ERROR, RECORD_UNAVAILABLE, code.getMessage());
      default -> internalError();
    };
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> unreadableBody() {
    return invalidInput();
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> invalidBody() {
    return invalidInput();
  }

  private static ResponseEntity<ErrorResponse> invalidInput() {
    return error(
        HttpStatus.BAD_REQUEST,
        PublicationErrorCode.INVALID_PUBLICATION_INPUT.name(),
        PublicationErrorCode.INVALID_PUBLICATION_INPUT.getMessage());
  }

  @ExceptionHandler(PublicationCommitOutcomeUnknownException.class)
  public ResponseEntity<ErrorResponse> outcomeUnknown() {
    return error(HttpStatus.SERVICE_UNAVAILABLE, OUTCOME_UNKNOWN, OUTCOME_UNKNOWN_MESSAGE);
  }

  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<ErrorResponse> unexpectedFailure() {
    return internalError();
  }

  private static ResponseEntity<ErrorResponse> internalError() {
    return error(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
  }

  private static ResponseEntity<ErrorResponse> error(
      HttpStatus status, String code, String message) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .body(new ErrorResponse(code, message));
  }
}

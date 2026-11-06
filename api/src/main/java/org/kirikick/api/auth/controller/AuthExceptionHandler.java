package org.kirikick.api.auth.controller;

import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.common.controller.dto.ErrorResponse;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.common.exception.ErrorCode;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = AuthSessionController.class)
public class AuthExceptionHandler {

  @ExceptionHandler(DomainException.class)
  public ResponseEntity<ErrorResponse> domainException(DomainException exception) {
    HttpStatus status = status(exception.getErrorCode());
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .body(new ErrorResponse(exception.getErrorCode().toString(), exception.getMessage()));
  }

  private static HttpStatus status(ErrorCode errorCode) {
    if (!(errorCode instanceof AuthErrorCode code)) {
      return HttpStatus.BAD_REQUEST;
    }
    return switch (code) {
      case AUTH_STATE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
      case ACCOUNT_INACTIVE, AGE_CONFIRMATION_REQUIRED -> HttpStatus.FORBIDDEN;
      case OAUTH_IDENTITY_ALREADY_EXISTS, SIGNUP_APPLICATION_ALREADY_COMPLETED ->
          HttpStatus.CONFLICT;
      case INVALID_REFRESH_TOKEN,
          REFRESH_TOKEN_REUSED,
          AUTHENTICATION_REQUIRED,
          INVALID_SIGNUP_PROOF,
          SIGNUP_APPLICATION_NOT_FOUND,
          SIGNUP_APPLICATION_IDENTITY_MISMATCH,
          SIGNUP_APPLICATION_EXPIRED,
          SIGNUP_APPLICATION_COMPLETED_INPUT_MISMATCH,
          SIGNUP_ALREADY_CLAIMED,
          INVALID_OAUTH_STATE ->
          HttpStatus.UNAUTHORIZED;
      default -> HttpStatus.BAD_REQUEST;
    };
  }
}

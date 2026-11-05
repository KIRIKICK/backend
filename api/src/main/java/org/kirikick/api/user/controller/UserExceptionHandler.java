package org.kirikick.api.user.controller;

import org.kirikick.api.common.controller.dto.ErrorResponse;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.user.domain.exception.UserErrorCode;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = UserProfileController.class)
public class UserExceptionHandler {
  @ExceptionHandler(DomainException.class)
  public ResponseEntity<ErrorResponse> domainException(DomainException exception) {
    if (!(exception.getErrorCode() instanceof UserErrorCode code)) {
      throw exception;
    }
    HttpStatus status =
        switch (code) {
          case ACCOUNT_NOT_FOUND -> HttpStatus.NOT_FOUND;
          case PROFILE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
          case INVALID_DISPLAY_NAME -> HttpStatus.BAD_REQUEST;
        };
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .body(new ErrorResponse(code.name(), exception.getMessage()));
  }
}

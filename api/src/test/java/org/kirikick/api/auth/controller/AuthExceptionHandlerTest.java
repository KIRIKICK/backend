package org.kirikick.api.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.user.domain.exception.UserErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class AuthExceptionHandlerTest {
  @Test
  void handlesOnlyAuthControllers() {
    assertThat(
            AuthExceptionHandler.class
                .getAnnotation(RestControllerAdvice.class)
                .basePackageClasses())
        .containsExactly(AuthSessionController.class);
  }

  @Test
  void preservesUserValidationErrorsRaisedWithinAuthControllers() {
    var response =
        new AuthExceptionHandler()
            .domainException(new DomainException(UserErrorCode.INVALID_DISPLAY_NAME));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("INVALID_DISPLAY_NAME");
  }
}

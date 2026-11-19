package org.kirikick.api.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.user.domain.exception.UserErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class AuthExceptionHandlerTest {
  @Test
  @DisplayName("인증 예외 처리기는 인증 컨트롤러에만 적용된다")
  void handlesOnlyAuthControllers() {
    assertThat(
            AuthExceptionHandler.class
                .getAnnotation(RestControllerAdvice.class)
                .basePackageClasses())
        .containsExactly(AuthSessionController.class);
  }

  @Test
  @DisplayName("인증 컨트롤러에서도 회원 입력 검증 오류를 유지한다")
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

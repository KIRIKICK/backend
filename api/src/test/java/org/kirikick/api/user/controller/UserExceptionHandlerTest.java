package org.kirikick.api.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.common.exception.ErrorCode;
import org.kirikick.api.user.domain.exception.UserErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class UserExceptionHandlerTest {
  @Test
  void handlesOnlyUserControllers() {
    assertThat(
            UserExceptionHandler.class
                .getAnnotation(RestControllerAdvice.class)
                .basePackageClasses())
        .containsExactly(UserProfileController.class);
  }

  @Test
  void mapsUserErrorsWithoutChangingTheValidationErrorContract() {
    var handler = new UserExceptionHandler();
    var statuses =
        Map.of(
            UserErrorCode.ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND,
            UserErrorCode.PROFILE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
            UserErrorCode.INVALID_DISPLAY_NAME, HttpStatus.BAD_REQUEST);

    statuses.forEach((code, status) -> {
      var response = handler.domainException(new DomainException(code));

      assertThat(response.getStatusCode()).isEqualTo(status);
      assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().code()).isEqualTo(code.name());
      assertThat(response.getBody().message()).isEqualTo(code.getMessage());
    });
  }

  @Test
  void doesNotReclassifyOtherDomainErrors() {
    ErrorCode otherDomain = () -> "다른 도메인 오류";
    DomainException failure = new DomainException(otherDomain);

    assertThatThrownBy(() -> new UserExceptionHandler().domainException(failure)).isSameAs(failure);
  }
}

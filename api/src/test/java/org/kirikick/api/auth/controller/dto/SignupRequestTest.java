package org.kirikick.api.auth.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SignupRequestTest {

  @Test
  @DisplayName("Bean Validation에서 필수 요청값과 중첩값 누락을 거절한다")
  void beanValidationRejectsMissingRequiredAndNestedRequestValues() {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      assertThat(validator.validate(new SignupRequest(null, null, null)))
          .extracting(violation -> violation.getPropertyPath().toString())
          .containsExactlyInAnyOrder("signupApplicationId", "displayName", "consents");
      assertThat(
              validator.validate(
                  new SignupRequest(UUID.randomUUID(), " ", new SignupConsents(null, null, null))))
          .extracting(violation -> violation.getPropertyPath().toString())
          .containsExactlyInAnyOrder(
              "displayName", "consents.service", "consents.privacy", "consents.ageConfirmed");
    }
  }

  @Test
  @DisplayName("동의 정책과 표시 이름의 세부 규칙은 하위 계층에서 검증한다")
  void beanValidationLeavesConsentPolicyAndExactNameValidationToLowerLayers() {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      SignupRequest request =
          new SignupRequest(UUID.randomUUID(), " 민지 ", new SignupConsents(false, false, false));

      assertThat(factory.getValidator().validate(request)).isEmpty();
      assertThat(request.displayName()).isEqualTo(" 민지 ");
    }
  }
}

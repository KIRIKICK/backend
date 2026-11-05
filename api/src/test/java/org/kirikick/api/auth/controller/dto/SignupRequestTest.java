package org.kirikick.api.auth.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SignupRequestTest {

  @Test
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
  void beanValidationLeavesConsentPolicyAndExactNameValidationToLowerLayers() {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      SignupRequest request =
          new SignupRequest(UUID.randomUUID(), " 민지 ", new SignupConsents(false, false, false));

      assertThat(factory.getValidator().validate(request)).isEmpty();
      assertThat(request.displayName()).isEqualTo(" 민지 ");
    }
  }
}

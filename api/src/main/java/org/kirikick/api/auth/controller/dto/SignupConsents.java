package org.kirikick.api.auth.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SignupConsents(
    @NotNull @Schema(description = "서비스 이용약관 동의. 가입하려면 true여야 합니다.", example = "true")
        Boolean service,
    @NotNull @Schema(description = "개인정보 처리방침 동의. 가입하려면 true여야 합니다.", example = "true")
        Boolean privacy,
    @NotNull
        @Schema(
            description =
                "만 14세 이상 확인. 가입하려면 true여야 하며 false이면 403 AGE_CONFIRMATION_REQUIRED를 반환합니다.",
            example = "true")
        Boolean ageConfirmed) {}

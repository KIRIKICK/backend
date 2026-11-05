package org.kirikick.api.auth.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SignupRequest(
    @NotNull @Schema(description = "GET /auth/signup/context에서 받은 가입 신청 식별자", format = "uuid")
        UUID signupApplicationId,
    @NotBlank
        @Schema(
            description =
                "표시 이름은 Unicode 코드 포인트 기준 1~10자이며 한글(자모 포함)·영문·숫자·공백·_·-만 허용합니다. 한글·영문·숫자를 하나 이상 포함해야 하며 연속 공백은 허용하지 않습니다. 원문을 변환하지 않고 검증합니다.",
            maxLength = 10,
            example = "민지")
        String displayName,
    @NotNull @Valid @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SignupConsents consents) {}

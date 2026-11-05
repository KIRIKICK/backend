package org.kirikick.api.auth.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SignupRequest(
    @NotNull @Schema(description = "GET /auth/signup/context에서 받은 가입 신청 식별자", format = "uuid")
        UUID signupApplicationId,
    @NotBlank @Schema(description = "표시 이름. 서버는 원문을 변환하지 않고 검증합니다.", example = "민지")
        String displayName,
    @NotNull @Valid @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SignupConsents consents) {}

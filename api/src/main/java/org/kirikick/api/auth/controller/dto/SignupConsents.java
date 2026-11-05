package org.kirikick.api.auth.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record SignupConsents(
    @NotNull @Schema(description = "서비스 이용약관 동의", example = "true") Boolean service,
    @NotNull @Schema(description = "개인정보 처리방침 동의", example = "true") Boolean privacy,
    @NotNull @Schema(description = "만 14세 이상 확인", example = "true") Boolean ageConfirmed) {}

package org.kirikick.api.common.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ErrorResponse(
    @Schema(description = "오류 코드", requiredMode = Schema.RequiredMode.REQUIRED) String code,
    @Schema(description = "오류 사유", requiredMode = Schema.RequiredMode.REQUIRED) String message) {}

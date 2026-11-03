package org.kirikick.api.auth.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record AuthErrorResponse(
    @Schema(description = "인증 오류 코드", example = "INVALID_REFRESH_TOKEN") String code,
    @Schema(description = "인증 실패 사유", example = "유효하지 않은 Refresh Token입니다.") String message) {}

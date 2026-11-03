package org.kirikick.api.auth.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record TokenResponse(
    @Schema(description = "보호 API의 Authorization 헤더에 Bearer 형식으로 전달할 RS256 Access JWT")
        String accessToken,
    @Schema(description = "토큰 전달 형식", allowableValues = "Bearer", example = "Bearer")
        String tokenType,
    @Schema(description = "Access Token 유효기간(초)", example = "900") long expiresIn) {}

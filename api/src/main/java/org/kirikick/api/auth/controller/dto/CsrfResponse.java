package org.kirikick.api.auth.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record CsrfResponse(
    @Schema(description = "변경 요청의 X-XSRF-TOKEN 헤더에 전달할 마스킹된 CSRF 토큰", example = "masked-csrf-token")
        String token) {}

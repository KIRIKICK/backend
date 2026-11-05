package org.kirikick.api.auth.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record SignupContextResponse(
    @Schema(
            description = "가입 신청 식별자. POST /auth/signup에 그대로 제출해야 하며 권한 자체가 아닙니다.",
            requiredMode = Schema.RequiredMode.REQUIRED)
        UUID signupApplicationId,
    @Schema(
            description = "OAuth 제공자가 반환한 가입 추천 이름. 제공되지 않으면 null입니다.",
            types = {"string", "null"})
        String suggestedDisplayName) {}

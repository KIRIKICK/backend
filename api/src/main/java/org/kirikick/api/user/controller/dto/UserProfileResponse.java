package org.kirikick.api.user.controller.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public record UserProfileResponse(
    @Schema(
            description = "회원의 외부 UUID",
            requiredMode = Schema.RequiredMode.REQUIRED,
            format = "uuid")
        UUID uuid,
    @Schema(description = "저장된 전역 표시 이름", requiredMode = Schema.RequiredMode.REQUIRED)
        String displayName,
    @Schema(
            description = "저장된 프로필 이미지 URL. 없으면 필드를 생략하지 않고 null을 반환합니다.",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true)
        String profileImageUrl,
    @ArraySchema(
            arraySchema =
                @Schema(
                    description = "실제로 연결된 제공자 코드. 순서는 의미가 없으며 연결이 없으면 빈 배열입니다.",
                    requiredMode = Schema.RequiredMode.REQUIRED),
            schema =
                @Schema(
                    type = "string",
                    allowableValues = {"kakao", "apple"}))
        List<String> linkedProviders) {

  public UserProfileResponse {
    linkedProviders = List.copyOf(linkedProviders);
  }
}

package org.kirikick.api.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.common.controller.dto.ErrorResponse;
import org.kirikick.api.user.controller.dto.UserProfileResponse;
import org.kirikick.api.user.service.UserProfileService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/users/me/profile")
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT")
public class UserProfileController {

  private final UserProfileService profiles;

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "내 프로필 조회",
      description =
          """
          서비스 Access JWT로 인증된 본인의 저장된 프로필을 조회합니다. 조회 대상은 JWT의 sub에서 식별하며 회원 ID를 요청으로 받지 않습니다.
          Refresh 쿠키나 OAuth HttpSession만으로는 인증되지 않으며, GET 요청이므로 CSRF 헤더는 요구하지 않습니다.
          이미지 URL은 저장값 또는 null을 그대로 반환하고, 연결 제공자가 없으면 빈 배열을 반환합니다. 조회로 프로필이나 세션을 변경하지 않습니다.
          """)
  @SecurityRequirement(name = "bearerAuth")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "인증된 본인의 프로필",
        headers = @Header(name = "Cache-Control", description = "no-store"),
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = UserProfileResponse.class),
                examples =
                    @ExampleObject(
                        name = "PROFILE",
                        value =
                            """
                            {"uuid":"6e954986-36d9-430a-a987-82caa519e48e","displayName":"민지","profileImageUrl":null,"linkedProviders":["kakao"]}
                            """))),
    @ApiResponse(
        responseCode = "401",
        description = "JWT 또는 Redis 세션이 유효하지 않거나 인증 단계에서 회원이 존재하지 않음 (AUTHENTICATION_REQUIRED)",
        headers = @Header(name = "Cache-Control", description = "no-store"),
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "AUTHENTICATION_REQUIRED",
                        value =
                            """
                            {"code":"AUTHENTICATION_REQUIRED","message":"인증이 필요합니다."}
                            """))),
    @ApiResponse(
        responseCode = "403",
        description =
            "비활성 회원은 조회할 수 없습니다. (ACCOUNT_INACTIVE) CORS 사전 거절은 JSON 또는 no-store를 보장하지 않습니다.",
        headers =
            @Header(
                name = "Cache-Control",
                description = "애플리케이션 오류 응답은 no-store이며 CORS 사전 거절에는 없을 수 있습니다."),
        content = {
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class),
              examples =
                  @ExampleObject(
                      name = "ACCOUNT_INACTIVE",
                      value =
                          """
                          {"code":"ACCOUNT_INACTIVE","message":"사용할 수 없는 회원 상태입니다."}
                          """)),
          @Content(
              mediaType = "*/*",
              schema = @Schema(type = "string", example = "Invalid CORS request"))
        }),
    @ApiResponse(
        responseCode = "404",
        description = "인증 검증을 통과한 뒤 프로필 조회 시 회원이 존재하지 않음 (ACCOUNT_NOT_FOUND)",
        headers = @Header(name = "Cache-Control", description = "no-store"),
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "ACCOUNT_NOT_FOUND",
                        value =
                            """
                            {"code":"ACCOUNT_NOT_FOUND","message":"회원을 찾을 수 없습니다."}
                            """))),
    @ApiResponse(
        responseCode = "503",
        description =
            "인증 단계의 저장소 가용성 장애는 AUTH_STATE_UNAVAILABLE, 인증 이후 회원·연결 제공자 조회의 가용성 장애는 PROFILE_UNAVAILABLE을 반환합니다.",
        headers = @Header(name = "Cache-Control", description = "no-store"),
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "AUTH_STATE_UNAVAILABLE",
                      value =
                          """
                          {"code":"AUTH_STATE_UNAVAILABLE","message":"인증 상태를 확인할 수 없습니다."}
                          """),
                  @ExampleObject(
                      name = "PROFILE_UNAVAILABLE",
                      value =
                          """
                          {"code":"PROFILE_UNAVAILABLE","message":"프로필을 조회할 수 없습니다."}
                          """)
                }))
  })
  public ResponseEntity<UserProfileResponse> profile(@AuthenticationPrincipal Jwt jwt) {
    UserProfileService.UserProfile profile = profiles.getProfile(UUID.fromString(jwt.getSubject()));
    UserProfileResponse response =
        new UserProfileResponse(
            profile.uuid(),
            profile.displayName(),
            profile.profileImageUrl(),
            profile.linkedProviders().stream()
                .map(provider -> provider.name().toLowerCase(Locale.ROOT))
                .toList());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
  }
}

package org.kirikick.api.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.kirikick.api.auth.controller.dto.AuthErrorResponse;
import org.kirikick.api.auth.controller.dto.CsrfResponse;
import org.kirikick.api.auth.controller.dto.TokenResponse;
import org.kirikick.api.auth.domain.LoginGrant;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.infra.JwtTokenService;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.common.exception.DomainException;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@Tag(name = "인증 세션", description = "CSRF 토큰 조회와 Refresh Token을 이용한 Access Token 재발급")
public class AuthSessionController {

  private final LoginSessionService sessions;
  private final AuthHttpSupport http;

  public AuthSessionController(LoginSessionService sessions, AuthHttpSupport http) {
    this.sessions = sessions;
    this.http = http;
  }

  @Operation(
      summary = "CSRF 토큰 조회",
      description =
          """
          Access Token 없이 호출할 수 있으며, 마스킹된 CSRF 토큰과 HttpOnly CSRF 쿠키를 발급합니다.
          쿠키 값이 아니라 응답 본문의 token 값을 변경 요청의 X-XSRF-TOKEN 헤더에 전달해야 합니다.
          CSRF 쿠키는 운영 환경에서 __Host-csrf, local 환경에서 dev-csrf를 사용합니다.
          브라우저 요청에는 credentials: include가 필요합니다.
          """)
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "마스킹된 CSRF 토큰과 CSRF 쿠키 발급",
        headers = @Header(name = "Set-Cookie", description = "HttpOnly CSRF 쿠키"),
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = CsrfResponse.class))),
    @ApiResponse(
        responseCode = "401",
        description = "유효하지 않은 Bearer Token을 전달했거나 CSRF 토큰을 조회할 수 없음",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = AuthErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}")))
  })
  @GetMapping("/csrf")
  public CsrfResponse csrf(HttpServletRequest request) {
    Object attribute = request.getAttribute(CsrfToken.class.getName());
    if (!(attribute instanceof CsrfToken token)) {
      throw new DomainException(AuthErrorCode.AUTHENTICATION_REQUIRED);
    }
    return new CsrfResponse(token.getToken());
  }

  @Operation(
      summary = "Access Token 재발급",
      description =
          """
          Access Token 없이 호출할 수 있지만 유효한 Refresh Token 쿠키가 필요합니다.
          운영 환경은 __Host-refresh, local 환경은 dev-refresh 쿠키를 사용합니다.
          Origin은 AUTH_FE_ORIGIN과 일치해야 하며, GET /auth/csrf 응답의 마스킹된 토큰을 X-XSRF-TOKEN 헤더로 전달해야 합니다.
          성공 시 Refresh Token을 회전하고 새 쿠키와 Access Token을 반환합니다.
          소비한 정확한 Refresh Token을 재사용하면 해당 세션을 폐기합니다.
          브라우저는 credentials: include로 쿠키를 전달해야 합니다.
          Swagger UI에서는 브라우저가 Origin과 쿠키를 관리하므로 직접 지정할 수 없으며,
          UI의 Origin이 허용된 FE Origin과 다르면 Try it out 요청은 거절됩니다.
          """)
  @Parameter(
      name = "X-XSRF-TOKEN",
      in = ParameterIn.HEADER,
      required = true,
      description = "GET /auth/csrf 응답 본문의 마스킹된 token 값",
      schema = @Schema(type = "string"))
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Access Token 재발급 및 Refresh Token 쿠키 회전",
        headers =
            @Header(
                name = "Set-Cookie",
                description = "회전된 Refresh Token 쿠키. HttpOnly, SameSite=Strict이며 운영에서는 Secure 적용"),
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = TokenResponse.class))),
    @ApiResponse(
        responseCode = "401",
        description = "Refresh Token 누락·만료·폐기·재사용 또는 회전 후 회원 검증 실패",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = AuthErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "유효하지 않은 Refresh Token",
                      value =
                          "{\"code\":\"INVALID_REFRESH_TOKEN\",\"message\":\"유효하지 않은 Refresh Token입니다.\"}"),
                  @ExampleObject(
                      name = "Refresh Token 재사용",
                      value =
                          "{\"code\":\"REFRESH_TOKEN_REUSED\",\"message\":\"이미 사용된 Refresh Token입니다.\"}")
                })),
    @ApiResponse(
        responseCode = "403",
        description = "Origin 또는 CSRF 검증 실패, 또는 회전 후 회원 상태가 ACTIVE가 아님",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = AuthErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "Origin 또는 CSRF 검증 실패",
                      value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}"),
                  @ExampleObject(
                      name = "비활성 회원",
                      value = "{\"code\":\"ACCOUNT_INACTIVE\",\"message\":\"사용할 수 없는 회원 상태입니다.\"}")
                })),
    @ApiResponse(
        responseCode = "503",
        description = "Redis 인증 상태 또는 인증용 DB 회원 조회를 사용할 수 없음",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = AuthErrorResponse.class),
                examples =
                    @ExampleObject(
                        value =
                            "{\"code\":\"AUTH_STATE_UNAVAILABLE\",\"message\":\"인증 상태를 확인할 수 없습니다.\"}")))
  })
  @PostMapping(value = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
  public TokenResponse refresh(HttpServletRequest request, HttpServletResponse response) {
    String refreshToken = findCookie(request, http.refreshCookieName());
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new DomainException(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }
    LoginGrant grant = sessions.refresh(refreshToken);
    http.writeRefresh(response, grant);
    return new TokenResponse(grant.accessToken(), "Bearer", JwtTokenService.ACCESS_TOKEN_SECONDS);
  }

  private static String findCookie(HttpServletRequest request, String expectedName) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (expectedName.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }
}

package org.kirikick.api.auth.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
public class AuthSessionController {

  private final LoginSessionService sessions;
  private final AuthHttpSupport http;

  public AuthSessionController(LoginSessionService sessions, AuthHttpSupport http) {
    this.sessions = sessions;
    this.http = http;
  }

  @GetMapping("/csrf")
  public CsrfResponse csrf(HttpServletRequest request) {
    Object attribute = request.getAttribute(CsrfToken.class.getName());
    if (!(attribute instanceof CsrfToken token)) {
      throw new DomainException(AuthErrorCode.AUTHENTICATION_REQUIRED);
    }
    return new CsrfResponse(token.getToken());
  }

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

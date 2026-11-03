package org.kirikick.api.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.kirikick.api.auth.controller.dto.AuthErrorResponse;
import org.kirikick.api.auth.domain.LoginGrant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;

public final class AuthHttpSupport {
  private static final String HOST_REFRESH_COOKIE = "__Host-refresh";
  private static final String DEV_REFRESH_COOKIE = "dev-refresh";
  private final boolean secureCookies;

  public AuthHttpSupport(boolean secureCookies) {
    this.secureCookies = secureCookies;
  }

  public static void writeError(
      HttpServletResponse response, ObjectMapper objectMapper, int status, AuthErrorResponse error)
      throws IOException {
    response.setStatus(status);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    objectMapper.writeValue(response.getWriter(), error);
  }

  public void writeRefresh(HttpServletResponse response, LoginGrant grant) {
    ResponseCookie cookie = refreshCookie(grant.refreshToken(), Duration.ofDays(14));
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    response.setHeader("Pragma", "no-cache");
  }

  public void clearRefresh(HttpServletResponse response) {
    response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString());
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  public String refreshCookieName() {
    return secureCookies ? HOST_REFRESH_COOKIE : DEV_REFRESH_COOKIE;
  }

  private ResponseCookie refreshCookie(String value, Duration maxAge) {
    return ResponseCookie.from(refreshCookieName(), value)
        .httpOnly(true)
        .secure(secureCookies)
        .sameSite("Strict")
        .path("/")
        .maxAge(maxAge)
        .build();
  }
}

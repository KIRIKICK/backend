package org.kirikick.api.auth.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.kirikick.api.auth.controller.dto.AuthErrorResponse;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.common.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

public final class SessionAuthorizationFilter extends OncePerRequestFilter {

  private final LoginSessionService sessions;
  private final ObjectMapper objectMapper;

  public SessionAuthorizationFilter(LoginSessionService sessions, ObjectMapper objectMapper) {
    this.sessions = sessions;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken jwt) {
      try {
        sessions.authorize(jwt.getToken());
      } catch (DomainException exception) {
        SecurityContextHolder.clearContext();
        writeError(response, exception.getErrorCode());
        return;
      }
    }
    chain.doFilter(request, response);
  }

  private void writeError(HttpServletResponse response, ErrorCode code) throws IOException {
    int status =
        code == AuthErrorCode.AUTH_STATE_UNAVAILABLE
            ? HttpServletResponse.SC_SERVICE_UNAVAILABLE
            : code == AuthErrorCode.ACCOUNT_INACTIVE
                ? HttpServletResponse.SC_FORBIDDEN
                : HttpServletResponse.SC_UNAUTHORIZED;
    response.setStatus(status);
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    objectMapper.writeValue(
        response.getWriter(), new AuthErrorResponse(code.toString(), code.getMessage()));
  }
}

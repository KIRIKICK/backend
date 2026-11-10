package org.kirikick.api.auth.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.auth.controller.AuthHttpSupport;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.common.controller.dto.ErrorResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public final class OriginValidationFilter extends OncePerRequestFilter {
  private final AuthFrontendPolicy policy;
  private final ObjectMapper objectMapper;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return switch (request.getMethod()) {
      case "GET", "HEAD", "OPTIONS", "TRACE" -> true;
      default -> false;
    };
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String origin = request.getHeader(HttpHeaders.ORIGIN);
    if (origin == null || !policy.isAllowed(origin)) {
      AuthHttpSupport.writeError(
          response,
          objectMapper,
          HttpServletResponse.SC_FORBIDDEN,
          new ErrorResponse(AuthErrorCode.AUTHENTICATION_REQUIRED.name(), "허용되지 않은 출처입니다."));
      return;
    }
    chain.doFilter(request, response);
  }
}

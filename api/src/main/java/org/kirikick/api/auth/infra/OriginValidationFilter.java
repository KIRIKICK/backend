package org.kirikick.api.auth.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.kirikick.api.auth.controller.AuthHttpSupport;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.common.controller.dto.ErrorResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

public final class OriginValidationFilter extends OncePerRequestFilter {
  private final String allowedOrigin;
  private final ObjectMapper objectMapper;

  public OriginValidationFilter(String allowedOrigin, ObjectMapper objectMapper) {
    this.allowedOrigin = canonicalOrigin(allowedOrigin);
    this.objectMapper = objectMapper;
  }

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
    if (origin == null || !allowedOrigin.equals(canonicalOriginOrNull(origin))) {
      AuthHttpSupport.writeError(
          response,
          objectMapper,
          HttpServletResponse.SC_FORBIDDEN,
          new ErrorResponse(AuthErrorCode.AUTHENTICATION_REQUIRED.name(), "허용되지 않은 출처입니다."));
      return;
    }
    chain.doFilter(request, response);
  }

  public static String canonicalOriginOrNull(String value) {
    try {
      return canonicalOrigin(value);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  public static String canonicalOrigin(String value) {
    URI uri = URI.create(value);
    if (uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
        || !("https".equalsIgnoreCase(uri.getScheme())
            || "http".equalsIgnoreCase(uri.getScheme()))) {
      throw new IllegalArgumentException("not an origin");
    }
    int port = uri.getPort();
    boolean defaultPort =
        port == -1
            || (port == 443 && "https".equalsIgnoreCase(uri.getScheme()))
            || (port == 80 && "http".equalsIgnoreCase(uri.getScheme()));
    return uri.getScheme().toLowerCase()
        + "://"
        + uri.getHost().toLowerCase()
        + (defaultPort ? "" : ":" + port);
  }
}

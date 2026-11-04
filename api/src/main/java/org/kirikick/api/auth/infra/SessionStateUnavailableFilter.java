package org.kirikick.api.auth.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.auth.controller.AuthHttpSupport;
import org.kirikick.api.auth.controller.dto.AuthErrorResponse;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.oauth.OAuthStaleCompletion;
import org.springframework.http.HttpStatus;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public final class SessionStateUnavailableFilter extends OncePerRequestFilter {

  private final ObjectMapper objectMapper;
  private final SessionRepository<? extends Session> sessionRepository;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      filterChain.doFilter(request, response);
    } catch (SessionStateUnavailableException exception) {
      if (response.isCommitted()) {
        throw exception;
      }
      unavailable(response);
    } catch (IllegalStateException exception) {
      OAuthStaleCompletion.Marker marker = OAuthStaleCompletion.marker(request);
      if (response.isCommitted()
          || marker == null
          || response.getStatus() != HttpServletResponse.SC_SEE_OTHER
          || !"no-store".equals(response.getHeader("Cache-Control"))
          || !marker.failureLocation().equals(response.getHeader("Location"))) {
        throw exception;
      }
      try {
        if (sessionRepository.findById(marker.invalidatedSessionId()) != null) {
          throw exception;
        }
      } catch (SessionStateUnavailableException unavailable) {
        unavailable(response);
        return;
      }
      response.reset();
      response.setStatus(HttpServletResponse.SC_SEE_OTHER);
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("Location", marker.failureLocation());
    }
  }

  private void unavailable(HttpServletResponse response) throws IOException {
    response.reset();
    AuthHttpSupport.writeError(
        response,
        objectMapper,
        HttpStatus.SERVICE_UNAVAILABLE.value(),
        new AuthErrorResponse(
            AuthErrorCode.AUTH_STATE_UNAVAILABLE.name(),
            AuthErrorCode.AUTH_STATE_UNAVAILABLE.getMessage()));
  }
}

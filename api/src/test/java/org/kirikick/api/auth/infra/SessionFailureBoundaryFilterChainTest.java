package org.kirikick.api.auth.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.HeaderHttpSessionIdResolver;
import org.springframework.session.web.http.SessionRepositoryFilter;

class SessionFailureBoundaryFilterChainTest {

  private final SessionStateUnavailableFilter boundary =
      new SessionStateUnavailableFilter(new ObjectMapper(), delegate());

  @Test
  void createFailureBecomesUnavailableResponse() throws Exception {
    SessionRepository<MapSession> delegate = delegate();
    when(delegate.createSession()).thenThrow(failure());

    assertUnavailable(delegate, request -> request.getSession());
  }

  @Test
  void saveFailureBecomesUnavailableResponse() throws Exception {
    SessionRepository<MapSession> delegate = delegate();
    when(delegate.createSession()).thenReturn(new MapSession());
    doThrow(failure()).when(delegate).save(any(MapSession.class));

    assertUnavailable(delegate, request -> request.getSession());
  }

  @Test
  void findFailureBecomesUnavailableResponse() throws Exception {
    SessionRepository<MapSession> delegate = delegate();
    when(delegate.findById(anyString())).thenThrow(failure());
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Auth-Token", "stale");

    assertUnavailable(delegate, request, ignored -> ignored.getSession(false));
  }

  @Test
  void deleteFailureBecomesUnavailableResponse() throws Exception {
    SessionRepository<MapSession> delegate = delegate();
    when(delegate.createSession()).thenReturn(new MapSession());
    doThrow(failure()).when(delegate).deleteById(anyString());

    assertUnavailable(delegate, request -> request.getSession().invalidate());
  }

  private void assertUnavailable(
      SessionRepository<MapSession> delegate, Consumer<HttpServletRequest> action)
      throws Exception {
    assertUnavailable(delegate, new MockHttpServletRequest(), action);
  }

  private void assertUnavailable(
      SessionRepository<MapSession> delegate,
      MockHttpServletRequest request,
      Consumer<HttpServletRequest> action)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    SessionRepositoryFilter<MapSession> sessionFilter =
        new SessionRepositoryFilter<>(
            new SessionRepositoryDataAccessExceptionTranslator<>(delegate));
    sessionFilter.setHttpSessionIdResolver(HeaderHttpSessionIdResolver.xAuthToken());

    boundary.doFilter(
        request,
        response,
        (outerRequest, outerResponse) ->
            sessionFilter.doFilter(
                outerRequest,
                outerResponse,
                (innerRequest, innerResponse) -> {
                  HttpServletResponse httpResponse = (HttpServletResponse) innerResponse;
                  httpResponse.setHeader("Location", "https://frontend.example/callback");
                  httpResponse.addHeader("Set-Cookie", "dev-refresh=stale");
                  action.accept((HttpServletRequest) innerRequest);
                }));

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Location")).isNull();
    assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
    assertThat(response.getContentAsString()).contains("AUTH_STATE_UNAVAILABLE");
  }

  @SuppressWarnings("unchecked")
  private static SessionRepository<MapSession> delegate() {
    return mock(SessionRepository.class);
  }

  private static DataAccessResourceFailureException failure() {
    return new DataAccessResourceFailureException("redis down");
  }
}

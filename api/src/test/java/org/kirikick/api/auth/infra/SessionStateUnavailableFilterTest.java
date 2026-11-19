package org.kirikick.api.auth.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

class SessionStateUnavailableFilterTest {
  private final SessionStateUnavailableFilter filter =
      new SessionStateUnavailableFilter(new ObjectMapper(), sessions());

  @Test
  @DisplayName("준비된 리다이렉트와 쿠키를 서비스 이용 불가 응답으로 대체한다")
  void replacesPreparedRedirectAndCookieWithUnavailableResponse() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        new MockHttpServletRequest(),
        response,
        (request, servletResponse) -> {
          MockHttpServletResponse httpResponse = (MockHttpServletResponse) servletResponse;
          httpResponse.setStatus(302);
          httpResponse.setHeader("Location", "https://frontend.example/callback");
          httpResponse.addHeader("Set-Cookie", "dev-session=stale");
          throw new SessionStateUnavailableException(new RuntimeException("redis down"));
        });

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Location")).isNull();
    assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getContentAsString()).contains("AUTH_STATE_UNAVAILABLE");
  }

  @Test
  @DisplayName("서비스 이용 불가 응답이 이미 커밋되면 예외를 다시 던진다")
  void rethrowsWhenUnavailableResponseIsAlreadyCommitted() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    SessionStateUnavailableException failure =
        new SessionStateUnavailableException(new RuntimeException("redis down"));

    assertThatThrownBy(() ->
            filter.doFilter(
                new MockHttpServletRequest(),
                response,
                (request, servletResponse) -> {
                  MockHttpServletResponse httpResponse = (MockHttpServletResponse) servletResponse;
                  httpResponse.setHeader("Location", "https://frontend.example/callback");
                  httpResponse.flushBuffer();
                  throw failure;
                }))
        .isSameAs(failure);

    assertThat(response.getHeader("Location")).isEqualTo("https://frontend.example/callback");
    assertThat(response.getStatus()).isNotEqualTo(503);
  }

  @Test
  @DisplayName("세션 장애와 무관한 IllegalStateException은 응답을 바꾸지 않고 다시 던진다")
  void rethrowsAnUnrelatedIllegalStateExceptionWithoutRewritingTheResponse() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    IllegalStateException failure = new IllegalStateException("unrelated");

    assertThatThrownBy(() ->
            filter.doFilter(
                new MockHttpServletRequest(),
                response,
                (request, servletResponse) -> {
                  ((MockHttpServletResponse) servletResponse)
                      .setHeader("Location", "https://keep.example");
                  throw failure;
                }))
        .isSameAs(failure);

    assertThat(response.getHeader("Location")).isEqualTo("https://keep.example");
    assertThat(response.getStatus()).isNotEqualTo(503);
  }

  @Test
  @DisplayName("JPA 영속성 예외는 응답을 바꾸지 않고 다시 던진다")
  void rethrowsJpaPersistenceExceptionWithoutRewritingTheResponse() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    PersistenceException failure = new PersistenceException("database unavailable");

    assertThatThrownBy(() ->
            filter.doFilter(
                new MockHttpServletRequest(),
                response,
                (request, servletResponse) -> {
                  ((MockHttpServletResponse) servletResponse)
                      .setHeader("Location", "https://keep.example");
                  throw failure;
                }))
        .isSameAs(failure);

    assertThat(response.getHeader("Location")).isEqualTo("https://keep.example");
    assertThat(response.getStatus()).isNotEqualTo(503);
  }

  @SuppressWarnings("unchecked")
  private static SessionRepository<Session> sessions() {
    return mock(SessionRepository.class);
  }
}

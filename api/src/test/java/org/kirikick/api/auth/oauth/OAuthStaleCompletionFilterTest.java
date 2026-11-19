package org.kirikick.api.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.infra.SessionStateUnavailableException;
import org.kirikick.api.auth.infra.SessionStateUnavailableFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.MapSession;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

class OAuthStaleCompletionFilterTest {
  private static final String FAILURE_LOCATION =
      "https://frontend.example/auth/kakao/callback?result=ERROR&errorCode=OAUTH_FAILED";

  @Test
  @DisplayName("세션이 사라진 이전 콜백은 일치하는 표식이 있으면 고정 실패 경로로 리다이렉트한다")
  void replacesExactMarkedStaleCompletionWithFixedFailureRedirectWhenSessionIsGone()
      throws Exception {
    Fixture fixture = validFixture();
    SessionRepository<Session> sessions = sessions();
    when(sessions.findById(fixture.flow().sessionId())).thenReturn(null);
    MockHttpServletResponse response = fixedFailureResponse();

    assertThat(
            OAuthStaleCompletion.mark(
                fixture.request(), fixture.flow(), fixture.claimId(), FAILURE_LOCATION))
        .isTrue();

    filter(sessions)
        .doFilter(
            fixture.request(),
            response,
            (request, servletResponse) -> {
              throw staleFailure();
            });

    assertThat(response.getStatus()).isEqualTo(303);
    assertThat(response.getHeader("Location")).isEqualTo(FAILURE_LOCATION);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    assertThat(response.getHeader("Authorization")).isNull();
    assertThat(response.getContentAsString()).isEmpty();
  }

  @Test
  @DisplayName("흐름이 claim을 획득한 콜백과 다르면 표식을 남기지 않는다")
  void doesNotMarkWhenFlowDoesNotMatchTheClaimedCallback() {
    Fixture fixture = validFixture();
    OAuthFlowMetadata differentFlow =
        new OAuthFlowMetadata(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "state",
            Instant.now().plusSeconds(60),
            fixture.flow().sessionId());

    assertThat(
            OAuthStaleCompletion.mark(
                fixture.request(), differentFlow, fixture.claimId(), FAILURE_LOCATION))
        .isFalse();
    assertThat(OAuthStaleCompletion.marker(fixture.request())).isNull();
  }

  @Test
  @DisplayName("claim ID가 콜백과 다르면 표식을 남기지 않는다")
  void doesNotMarkWhenClaimIdDoesNotMatchTheClaimedCallback() {
    Fixture fixture = validFixture();

    assertThat(
            OAuthStaleCompletion.mark(
                fixture.request(), fixture.flow(), UUID.randomUUID(), FAILURE_LOCATION))
        .isFalse();
    assertThat(OAuthStaleCompletion.marker(fixture.request())).isNull();
  }

  @Test
  @DisplayName("요청 세션 ID가 claim 흐름과 연결되지 않으면 표식을 남기지 않는다")
  void doesNotMarkWhenRequestedSessionIdDoesNotBindTheClaimedFlow() {
    Fixture fixture = validFixture();
    fixture.request().setRequestedSessionId("different-session");

    assertThat(
            OAuthStaleCompletion.mark(
                fixture.request(), fixture.flow(), fixture.claimId(), FAILURE_LOCATION))
        .isFalse();
    assertThat(OAuthStaleCompletion.marker(fixture.request())).isNull();
  }

  @Test
  @DisplayName("준비된 응답의 상태 코드가 다르면 이전 완료 예외를 다시 던진다")
  void rethrowsMarkedStaleCompletionWhenPreparedResponseHasWrongStatus() {
    MockHttpServletResponse response = fixedFailureResponse();
    response.setStatus(302);

    assertRethrowsWithoutLookup(response);
  }

  @Test
  @DisplayName("준비된 응답에 no-store가 없으면 이전 완료 예외를 다시 던진다")
  void rethrowsMarkedStaleCompletionWhenPreparedResponseLacksNoStore() {
    MockHttpServletResponse response = fixedFailureResponse();
    response.setHeader("Cache-Control", "private");

    assertRethrowsWithoutLookup(response);
  }

  @Test
  @DisplayName("준비된 응답의 Location이 다르면 이전 완료 예외를 다시 던진다")
  void rethrowsMarkedStaleCompletionWhenPreparedResponseHasDifferentLocation() {
    MockHttpServletResponse response = fixedFailureResponse();
    response.setHeader("Location", "https://frontend.example/other");

    assertRethrowsWithoutLookup(response);
  }

  @Test
  @DisplayName("이전 세션이 남아 있으면 이전 완료 예외를 다시 던진다")
  void rethrowsMarkedStaleCompletionWhenTheOldSessionStillExists() {
    Fixture fixture = validFixture();
    SessionRepository<Session> sessions = sessions();
    when(sessions.findById(fixture.flow().sessionId())).thenReturn(new MapSession());
    MockHttpServletResponse response = fixedFailureResponse();
    IllegalStateException failure = staleFailure();
    assertThat(
            OAuthStaleCompletion.mark(
                fixture.request(), fixture.flow(), fixture.claimId(), FAILURE_LOCATION))
        .isTrue();

    assertThatThrownBy(() ->
            filter(sessions)
                .doFilter(
                    fixture.request(),
                    response,
                    (request, servletResponse) -> {
                      throw failure;
                    }))
        .isSameAs(failure);
    assertThat(response.getHeaders("Set-Cookie")).containsExactly("dev-refresh=prepared");
  }

  @Test
  @DisplayName("응답이 이미 커밋되면 이전 완료 예외를 다시 던진다")
  void rethrowsMarkedStaleCompletionWhenResponseIsAlreadyCommitted() throws Exception {
    Fixture fixture = validFixture();
    SessionRepository<Session> sessions = sessions();
    MockHttpServletResponse response = fixedFailureResponse();
    response.flushBuffer();
    IllegalStateException failure = staleFailure();
    assertThat(
            OAuthStaleCompletion.mark(
                fixture.request(), fixture.flow(), fixture.claimId(), FAILURE_LOCATION))
        .isTrue();

    assertThatThrownBy(() ->
            filter(sessions)
                .doFilter(
                    fixture.request(),
                    response,
                    (request, servletResponse) -> {
                      throw failure;
                    }))
        .isSameAs(failure);
    verifyNoInteractions(sessions);
  }

  @Test
  @DisplayName("표식에 대응하는 이전 세션의 조회에 실패하면 장애 응답을 반환한다")
  void rendersUnavailableWhenMarkedStaleSessionLookupFails() throws Exception {
    Fixture fixture = validFixture();
    SessionRepository<Session> sessions = sessions();
    IllegalStateException failure = staleFailure();
    when(sessions.findById(fixture.flow().sessionId()))
        .thenThrow(new SessionStateUnavailableException(new RuntimeException("redis down")));
    MockHttpServletResponse response = fixedFailureResponse();
    assertThat(
            OAuthStaleCompletion.mark(
                fixture.request(), fixture.flow(), fixture.claimId(), FAILURE_LOCATION))
        .isTrue();

    filter(sessions)
        .doFilter(
            fixture.request(),
            response,
            (request, servletResponse) -> {
              throw staleFailure();
            });

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Location")).isNull();
    assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getContentAsString()).contains("AUTH_STATE_UNAVAILABLE");
  }

  private static void assertRethrowsWithoutLookup(MockHttpServletResponse response) {
    Fixture fixture = validFixture();
    SessionRepository<Session> sessions = sessions();
    IllegalStateException failure = staleFailure();
    assertThat(
            OAuthStaleCompletion.mark(
                fixture.request(), fixture.flow(), fixture.claimId(), FAILURE_LOCATION))
        .isTrue();

    assertThatThrownBy(() ->
            filter(sessions)
                .doFilter(
                    fixture.request(),
                    response,
                    (request, servletResponse) -> {
                      throw failure;
                    }))
        .isSameAs(failure);
    verifyNoInteractions(sessions);
  }

  private static Fixture validFixture() {
    String sessionId = "session-" + UUID.randomUUID();
    OAuthFlowMetadata flow =
        new OAuthFlowMetadata(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "state",
            Instant.now().plusSeconds(60),
            sessionId);
    UUID claimId = UUID.randomUUID();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestedSessionId(sessionId);
    request.setAttribute(OAuthFlowAuthorizationRequestRepository.MATCHED_FLOW_ATTRIBUTE, flow);
    request.setAttribute(
        OAuthFlowAuthorizationRequestRepository.class.getName() + ".claim", claimId);
    return new Fixture(request, flow, claimId);
  }

  private static MockHttpServletResponse fixedFailureResponse() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(303);
    response.setHeader("Location", FAILURE_LOCATION);
    response.setHeader("Cache-Control", "no-store");
    response.addHeader("Set-Cookie", "dev-refresh=prepared");
    return response;
  }

  private static IllegalStateException staleFailure() {
    return new IllegalStateException("Session was invalidated");
  }

  private static SessionStateUnavailableFilter filter(SessionRepository<Session> sessions) {
    return new SessionStateUnavailableFilter(new ObjectMapper(), sessions);
  }

  @SuppressWarnings("unchecked")
  private static SessionRepository<Session> sessions() {
    return mock(SessionRepository.class);
  }

  private record Fixture(MockHttpServletRequest request, OAuthFlowMetadata flow, UUID claimId) {}
}

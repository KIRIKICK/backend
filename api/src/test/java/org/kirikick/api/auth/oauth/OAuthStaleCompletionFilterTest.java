package org.kirikick.api.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
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
  void doesNotMarkWhenClaimIdDoesNotMatchTheClaimedCallback() {
    Fixture fixture = validFixture();

    assertThat(
            OAuthStaleCompletion.mark(
                fixture.request(), fixture.flow(), UUID.randomUUID(), FAILURE_LOCATION))
        .isFalse();
    assertThat(OAuthStaleCompletion.marker(fixture.request())).isNull();
  }

  @Test
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
  void rethrowsMarkedStaleCompletionWhenPreparedResponseHasWrongStatus() {
    MockHttpServletResponse response = fixedFailureResponse();
    response.setStatus(302);

    assertRethrowsWithoutLookup(response);
  }

  @Test
  void rethrowsMarkedStaleCompletionWhenPreparedResponseLacksNoStore() {
    MockHttpServletResponse response = fixedFailureResponse();
    response.setHeader("Cache-Control", "private");

    assertRethrowsWithoutLookup(response);
  }

  @Test
  void rethrowsMarkedStaleCompletionWhenPreparedResponseHasDifferentLocation() {
    MockHttpServletResponse response = fixedFailureResponse();
    response.setHeader("Location", "https://frontend.example/other");

    assertRethrowsWithoutLookup(response);
  }

  @Test
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

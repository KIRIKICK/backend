package org.kirikick.api.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.auth.controller.dto.SignupConsents;
import org.kirikick.api.auth.controller.dto.SignupContextResponse;
import org.kirikick.api.auth.controller.dto.SignupRequest;
import org.kirikick.api.auth.domain.LoginGrant;
import org.kirikick.api.auth.domain.LoginSession;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.infra.RedisFlowClaimGuard;
import org.kirikick.api.auth.onboarding.OnboardingSessionProofs;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.service.SignupApplicationCompletionService;
import org.kirikick.api.auth.service.SignupApplicationProofBindingValidator;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.config.AuthRequestJsonConfiguration;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SignupControllerTest {

  private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

  private final RedisFlowClaimGuard claims = mock(RedisFlowClaimGuard.class);
  private final SignupApplicationCompletionService completion =
      mock(SignupApplicationCompletionService.class);
  private final SignupApplicationProofBindingValidator bindings =
      mock(SignupApplicationProofBindingValidator.class);
  private final LoginSessionService sessions = mock(LoginSessionService.class);
  private final UserRepository users = mock(UserRepository.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private SignupController controller;
  private MockHttpSession session;
  private UUID slot;
  private UUID signupApplicationId;

  @BeforeEach
  void setUp() {
    controller =
        new SignupController(
            claims,
            completion,
            bindings,
            sessions,
            users,
            new AuthHttpSupport(false, false),
            clock);
    session = new MockHttpSession();
    slot = UUID.randomUUID();
    signupApplicationId = UUID.randomUUID();
    OnboardingSessionProofs.publish(
        session,
        slot,
        OAuthProvider.KAKAO.name(),
        "subject",
        "추천 이름",
        signupApplicationId,
        NOW,
        NOW.plusSeconds(300));
    when(claims.inspectProof(slot, signupApplicationId, NOW.plusSeconds(300), NOW))
        .thenReturn(RedisFlowClaimGuard.InspectProofResult.CURRENT);
  }

  @Test
  void contextReturnsOnlySignupApplicationIdAndSuggestedDisplayName() {
    SignupContextResponse response = controller.context(request());

    assertThat(response.signupApplicationId()).isEqualTo(signupApplicationId);
    assertThat(response.suggestedDisplayName()).isEqualTo("추천 이름");
  }

  @ParameterizedTest
  @MethodSource("invalidSignupRequests")
  void httpBindingRejectsMissingMalformedCoercedAndExtraValuesBeforeSignup(String payload)
      throws Exception {
    signupMvc()
        .perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("code").value(AuthErrorCode.INVALID_SIGNUP_REQUEST.name()))
        .andExpect(jsonPath("message").value(AuthErrorCode.INVALID_SIGNUP_REQUEST.getMessage()));

    verifyNoInteractions(claims, bindings, completion, sessions, users);
  }

  @ParameterizedTest
  @ValueSource(strings = {"application/json", "application/vnd.kirikick+json"})
  void httpBindingAcceptsTypedRequestForSupportedJsonMediaTypes(String mediaType) throws Exception {
    signupMvc()
        .perform(
            post("/auth/signup")
                .session(session)
                .contentType(mediaType)
                .content(requestJson(UUID.randomUUID())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("code").value(AuthErrorCode.INVALID_SIGNUP_PROOF.name()));

    verify(claims).inspectProof(slot, signupApplicationId, NOW.plusSeconds(300), NOW);
    verifyNoInteractions(completion, sessions, users);
  }

  private static Stream<String> invalidSignupRequests() {
    UUID id = UUID.randomUUID();
    String payload = requestJson(id);
    return Stream.of(
        "",
        "null",
        "[]",
        "{",
        "{}",
        payload.replace("\"" + id + "\"", "null"),
        payload.replace(id.toString(), "not-a-uuid"),
        payload.replace("\"" + id + "\"", "123"),
        payload.replace("\"민지\"", "null"),
        payload.replace("\"민지\"", "\" \""),
        payload.replace("\"민지\"", "123"),
        payload.replace("\"민지\"", "1.2"),
        payload.replace("\"민지\"", "true"),
        payload.replace("\"consents\":{", "\"consents\":{\"extra\":true,"),
        payload.replace("\"displayName\":", "\"extra\":true,\"displayName\":"),
        payload.replace(
            "\"consents\":{\"service\":true,\"privacy\":true,\"ageConfirmed\":true}",
            "\"consents\":null"),
        payload.replace("\"service\":true,", ""),
        payload.replace("\"privacy\":true,", ""),
        payload.replace(",\"ageConfirmed\":true", ""),
        payload.replace("\"service\":true", "\"service\":null"),
        payload.replace("\"privacy\":true", "\"privacy\":null"),
        payload.replace("\"ageConfirmed\":true", "\"ageConfirmed\":null"),
        payload.replace("\"service\":true", "\"service\":\"true\""),
        payload.replace("\"privacy\":true", "\"privacy\":1"),
        payload.replace("\"ageConfirmed\":true", "\"ageConfirmed\":1.0"));
  }

  private MockMvc signupMvc() {
    List<HttpMessageConverter<?>> converters =
        new ArrayList<>(List.of(new MappingJackson2HttpMessageConverter()));
    new AuthRequestJsonConfiguration().extendMessageConverters(converters);
    return MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new AuthExceptionHandler())
        .setMessageConverters(converters.toArray(HttpMessageConverter<?>[]::new))
        .build();
  }

  @Test
  void rejectsGuessedSignupApplicationIdBeforeClaimOrCompletion() throws Exception {
    assertThatThrownBy(() ->
            controller.signup(
                signupRequest(UUID.randomUUID()), request(), new MockHttpServletResponse()))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).getErrorCode())
        .isEqualTo(AuthErrorCode.INVALID_SIGNUP_PROOF);

    verify(claims, never()).claimSignup(any(), any(), any(), any(), any());
    verify(completion, never()).complete(any());
  }

  @Test
  void completedApplicationDoesNotIssueCredentials() throws Exception {
    when(claims.claimSignup(any(), any(), any(), any(), any()))
        .thenReturn(RedisFlowClaimGuard.ClaimSignupResult.CLAIMED);
    when(completion.complete(any()))
        .thenReturn(
            new SignupApplicationCompletionService.CompletionResult(
                signupApplicationId, UUID.randomUUID(), false));
    when(claims.finishSignup(any(), any(), any(), any(), any()))
        .thenReturn(RedisFlowClaimGuard.FinishSignupResult.FINISHED_CLEANUP_PERMIT);

    assertThatThrownBy(() ->
            controller.signup(
                signupRequest(signupApplicationId), request(), new MockHttpServletResponse()))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).getErrorCode())
        .isEqualTo(AuthErrorCode.SIGNUP_APPLICATION_ALREADY_COMPLETED);

    verify(sessions, never()).login(any());
    verify(sessions, never()).revoke(any());
  }

  @Test
  void expiredClaimCleansOnlyTheExactProofAndDoesNotComplete() throws Exception {
    when(claims.claimSignup(any(), any(), any(), any(), any()))
        .thenReturn(RedisFlowClaimGuard.ClaimSignupResult.EXPIRED_CLEANUP_PERMIT);

    assertThatThrownBy(() ->
            controller.signup(
                signupRequest(signupApplicationId), request(), new MockHttpServletResponse()))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).getErrorCode())
        .isEqualTo(AuthErrorCode.INVALID_SIGNUP_PROOF);

    verify(completion, never()).complete(any());
    verify(sessions, never()).login(any());
  }

  @Test
  void ageConfirmationFailureCleansProofBeforeAnyCompletion() throws Exception {
    when(claims.finishProof(any(), any(), any(), any()))
        .thenReturn(RedisFlowClaimGuard.FinishProofResult.CLEANUP_PERMIT);

    assertThatThrownBy(() ->
            controller.signup(
                new SignupRequest(signupApplicationId, "민지", new SignupConsents(true, true, false)),
                request(),
                new MockHttpServletResponse()))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).getErrorCode())
        .isEqualTo(AuthErrorCode.AGE_CONFIRMATION_REQUIRED);

    verify(completion, never()).complete(any());
    verify(sessions, never()).login(any());
  }

  @Test
  void completionFailureIsPreservedWhenClaimCleanupFails() throws Exception {
    RuntimeException original = new IllegalStateException("completion failed");
    when(claims.claimSignup(any(), any(), any(), any(), any()))
        .thenReturn(RedisFlowClaimGuard.ClaimSignupResult.CLAIMED);
    when(completion.complete(any())).thenThrow(original);
    when(claims.finishSignup(any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("cleanup failed"));

    assertThatThrownBy(() ->
            controller.signup(
                signupRequest(signupApplicationId), request(), new MockHttpServletResponse()))
        .isSameAs(original);

    verify(sessions, never()).login(any());
  }

  @Test
  void normalizesExpiryDetectedAfterClaimToInvalidProofWithoutLogin() throws Exception {
    when(claims.claimSignup(any(), any(), any(), any(), any()))
        .thenReturn(RedisFlowClaimGuard.ClaimSignupResult.CLAIMED);
    when(completion.complete(any()))
        .thenThrow(new DomainException(AuthErrorCode.SIGNUP_APPLICATION_EXPIRED));
    when(claims.finishSignup(any(), any(), any(), any(), any()))
        .thenReturn(RedisFlowClaimGuard.FinishSignupResult.FINISHED_CLEANUP_PERMIT);

    assertThatThrownBy(() ->
            controller.signup(
                signupRequest(signupApplicationId), request(), new MockHttpServletResponse()))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).getErrorCode())
        .isEqualTo(AuthErrorCode.INVALID_SIGNUP_PROOF);

    verify(sessions, never()).login(any());
  }

  @Test
  void finishFailureAfterGrantRevokesOnlyTheIssuedGrantAndPreservesFailure() throws Exception {
    UUID userUuid = UUID.randomUUID();
    User member = User.create("민지", null);
    // Completion returns the persisted UUID; use the aggregate UUID in the repository stub.
    userUuid = member.getUuid();
    LoginGrant grant =
        new LoginGrant(
            new LoginSession(
                "session", 1L, userUuid, NOW, NOW.plusSeconds(900), NOW.plusSeconds(900)),
            "access-token",
            "refresh-token");
    RuntimeException original = new IllegalStateException("finish failed");
    when(claims.claimSignup(any(), any(), any(), any(), any()))
        .thenReturn(RedisFlowClaimGuard.ClaimSignupResult.CLAIMED);
    when(completion.complete(any()))
        .thenReturn(
            new SignupApplicationCompletionService.CompletionResult(
                signupApplicationId, userUuid, true));
    when(users.findByUuid(userUuid)).thenReturn(Optional.of(member));
    when(sessions.login(member)).thenReturn(grant);
    when(claims.finishSignup(any(), any(), any(), any(), any())).thenThrow(original);

    assertThatThrownBy(() ->
            controller.signup(
                signupRequest(signupApplicationId), request(), new MockHttpServletResponse()))
        .isSameAs(original);

    verify(sessions).revoke(grant);
  }

  @Test
  void committedCompletionUserReloadAvailabilityFailureReturnsUnavailableWithoutLogin()
      throws Exception {
    UUID userUuid = UUID.randomUUID();
    when(claims.claimSignup(any(), any(), any(), any(), any()))
        .thenReturn(RedisFlowClaimGuard.ClaimSignupResult.CLAIMED);
    when(completion.complete(any()))
        .thenReturn(
            new SignupApplicationCompletionService.CompletionResult(
                signupApplicationId, userUuid, true));
    when(users.findByUuid(userUuid))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));
    when(claims.finishSignup(any(), any(), any(), any(), any()))
        .thenReturn(RedisFlowClaimGuard.FinishSignupResult.FINISHED_CLEANUP_PERMIT);

    assertThatThrownBy(() ->
            controller.signup(
                signupRequest(signupApplicationId), request(), new MockHttpServletResponse()))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).getErrorCode())
        .isEqualTo(AuthErrorCode.AUTH_STATE_UNAVAILABLE);

    verify(sessions, never()).login(any());
  }

  private HttpServletRequest request() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getSession(false)).thenReturn(session);
    return request;
  }

  private static SignupRequest signupRequest(UUID id) {
    return new SignupRequest(id, "민지", new SignupConsents(true, true, true));
  }

  private static String requestJson(UUID id) {
    return """
    {"signupApplicationId":"%s","displayName":"민지","consents":{"service":true,"privacy":true,"ageConfirmed":true}}
    """
        .formatted(id);
  }
}

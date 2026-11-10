package org.kirikick.api.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.auth.controller.dto.SignupContextResponse;
import org.kirikick.api.auth.controller.dto.SignupRequest;
import org.kirikick.api.auth.controller.dto.TokenResponse;
import org.kirikick.api.auth.domain.LoginGrant;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.infra.JwtTokenService;
import org.kirikick.api.auth.infra.RedisFlowClaimGuard;
import org.kirikick.api.auth.onboarding.OnboardingProof;
import org.kirikick.api.auth.onboarding.OnboardingProofResolution;
import org.kirikick.api.auth.onboarding.OnboardingSessionProofs;
import org.kirikick.api.auth.service.AuthStateAvailability;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.service.SignupApplicationCompletionService;
import org.kirikick.api.auth.service.SignupApplicationProofBindingValidator;
import org.kirikick.api.common.controller.dto.ErrorResponse;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/auth/signup")
@Tag(name = "회원가입", description = "OAuth 가입 증명으로 명시적 회원가입을 완료합니다.")
public class SignupController {

  private static final Logger log = LoggerFactory.getLogger(SignupController.class);
  private static final String DB_UNAVAILABLE_LOG = "auth-signup-state-unavailable exceptionType={}";

  private final RedisFlowClaimGuard claims;
  private final SignupApplicationCompletionService completion;
  private final SignupApplicationProofBindingValidator validator;
  private final LoginSessionService sessions;
  private final UserRepository users;
  private final AuthHttpSupport http;
  private final Clock clock;

  @GetMapping("/context")
  @Operation(
      summary = "가입 컨텍스트 조회",
      description =
          """
          현재 브라우저의 유효한 OAuth 가입 증명에 연결된 가입 신청만 반환합니다. Access Token은 필요하지 않습니다.
          OAuth 콜백에서 발급된 임시 세션 쿠키(운영 __Host-onboarding, local dev-onboarding)가 필요합니다. onboarding 쿠키는 기본 SameSite=Lax이며, DEV cross-site opt-in에서는 SameSite=None; Secure를 사용합니다.
          브라우저 요청에는 credentials: include를 사용해야 하며, 반환된 signupApplicationId만으로 가입할 수는 없습니다.
          """)
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = SignupContextResponse.class))),
    @ApiResponse(
        responseCode = "401",
        description =
            "가입 증명 또는 연결된 가입 신청이 유효하지 않음 (INVALID_SIGNUP_PROOF). 유효하지 않은 Bearer Token을 전달한 경우 AUTHENTICATION_REQUIRED",
        headers = {@Header(name = "Cache-Control", description = "no-store")},
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "INVALID_SIGNUP_PROOF",
                      value =
                          "{\"code\":\"INVALID_SIGNUP_PROOF\",\"message\":\"유효하지 않은 가입 증명입니다.\"}")
                })),
    @ApiResponse(
        responseCode = "403",
        description =
            "선택적으로 전달한 Bearer Token의 회원이 비활성 상태임 (ACCOUNT_INACTIVE). CORS에서 먼저 거절된 요청은 JSON이 아닌 403 Invalid CORS request를 반환할 수 있으며 Cache-Control 헤더가 없을 수 있습니다.",
        headers = {
          @Header(
              name = "Cache-Control",
              description = "애플리케이션 오류 응답은 no-store. CORS 사전 거절에는 없을 수 있습니다.")
        },
        content = {
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class),
              examples = {
                @ExampleObject(
                    name = "ACCOUNT_INACTIVE",
                    value = "{\"code\":\"ACCOUNT_INACTIVE\",\"message\":\"사용할 수 없는 회원 상태입니다.\"}")
              }),
          @Content(
              mediaType = MediaType.ALL_VALUE,
              schema =
                  @Schema(
                      type = "string",
                      description = "Spring CORS 사전 거절 응답. Content-Type은 지정되지 않을 수 있습니다."),
              examples = @ExampleObject(value = "Invalid CORS request"))
        }),
    @ApiResponse(
        responseCode = "503",
        description = "인증 상태 저장소 가용성 장애 (AUTH_STATE_UNAVAILABLE)",
        headers = {@Header(name = "Cache-Control", description = "no-store")},
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "AUTH_STATE_UNAVAILABLE",
                      value =
                          "{\"code\":\"AUTH_STATE_UNAVAILABLE\",\"message\":\"인증 상태를 확인할 수 없습니다.\"}")
                }))
  })
  public SignupContextResponse context(HttpServletRequest request) {
    OnboardingProof proof = currentProof(existingSession(request));
    validator.requireCurrent(proof.proofId(), provider(proof), proof.subject(), proof.expiresAt());
    return new SignupContextResponse(proof.proofId(), proof.suggestedDisplayName());
  }

  @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "회원가입 완료",
      description =
          """
          가입 신청·브라우저 증명·OAuth 신원이 모두 일치할 때만 회원을 생성합니다. Access Token은 필요하지 않습니다.
          GET /auth/signup/context에서 받은 signupApplicationId와 임시 세션 쿠키(운영 __Host-onboarding, local dev-onboarding)를 함께 전달합니다. onboarding 쿠키는 기본 SameSite=Lax이며, DEV cross-site opt-in에서는 SameSite=None; Secure를 사용합니다.
          AUTH_FE_ORIGIN과 DEV 전용 AUTH_DEV_ADDITIONAL_FE_ORIGINS의 exact allowlist에 포함된 Origin, CSRF 쿠키와 GET /auth/csrf에서 받은 X-XSRF-TOKEN 헤더가 필요합니다.
          브라우저 요청에는 credentials: include를 사용해야 합니다.
          성공하면 Access Token을 본문으로, Refresh Token을 HttpOnly 쿠키로 발급하고 임시 가입 세션을 종료합니다.
          이미 완료된 신청은 토큰을 재발급하지 않습니다. 응답 유실 시 새 OAuth 로그인으로 다시 시작해야 합니다.
          """)
  @Parameter(
      name = "X-XSRF-TOKEN",
      in = ParameterIn.HEADER,
      required = true,
      description = "GET /auth/csrf 응답 본문의 마스킹된 token 값")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "회원가입 완료 및 로그인 토큰 발급",
        headers = {
          @Header(
              name = "Set-Cookie",
              description =
                  "HttpOnly Refresh 쿠키(운영 __Host-refresh, local dev-refresh, 기본 SameSite=Strict, DEV cross-site opt-in SameSite=None; Secure)를 발급합니다. 여러 Set-Cookie 헤더가 반환될 수 있습니다."),
          @Header(name = "Cache-Control", description = "no-store"),
          @Header(name = "Pragma", description = "no-cache")
        },
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = TokenResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description =
            "필수값 누락·타입/추가 필드 오류·필수 약관 미동의 (INVALID_SIGNUP_REQUEST) 또는 이름 정책 위반 (INVALID_DISPLAY_NAME)",
        headers = {@Header(name = "Cache-Control", description = "no-store")},
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "INVALID_SIGNUP_REQUEST",
                      value =
                          "{\"code\":\"INVALID_SIGNUP_REQUEST\",\"message\":\"유효하지 않은 가입 요청입니다.\"}")
                })),
    @ApiResponse(
        responseCode = "401",
        description =
            "가입 증명·신청 불일치 또는 만료 (INVALID_SIGNUP_PROOF). 유효하지 않은 Bearer Token을 전달한 경우 AUTHENTICATION_REQUIRED",
        headers = {@Header(name = "Cache-Control", description = "no-store")},
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "INVALID_SIGNUP_PROOF",
                      value =
                          "{\"code\":\"INVALID_SIGNUP_PROOF\",\"message\":\"유효하지 않은 가입 증명입니다.\"}")
                })),
    @ApiResponse(
        responseCode = "403",
        description =
            "Origin·CSRF 검증 실패 (AUTHENTICATION_REQUIRED), 14세 이상 미확인 (AGE_CONFIRMATION_REQUIRED) 또는 비활성 회원 (ACCOUNT_INACTIVE). CORS에서 먼저 거절된 요청은 JSON이 아닌 403 Invalid CORS request를 반환할 수 있으며 Cache-Control 헤더가 없을 수 있습니다.",
        headers = {
          @Header(
              name = "Cache-Control",
              description = "애플리케이션 오류 응답은 no-store. CORS 사전 거절에는 없을 수 있습니다.")
        },
        content = {
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class),
              examples = {
                @ExampleObject(
                    name = "AUTHENTICATION_REQUIRED",
                    value = "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"인증이 필요합니다.\"}"),
                @ExampleObject(
                    name = "AGE_CONFIRMATION_REQUIRED",
                    value =
                        "{\"code\":\"AGE_CONFIRMATION_REQUIRED\",\"message\":\"14세 이상 확인이 필요합니다.\"}"),
                @ExampleObject(
                    name = "ACCOUNT_INACTIVE",
                    value = "{\"code\":\"ACCOUNT_INACTIVE\",\"message\":\"사용할 수 없는 회원 상태입니다.\"}")
              }),
          @Content(
              mediaType = MediaType.ALL_VALUE,
              schema =
                  @Schema(
                      type = "string",
                      description = "Spring CORS 사전 거절 응답. Content-Type은 지정되지 않을 수 있습니다."),
              examples = @ExampleObject(value = "Invalid CORS request"))
        }),
    @ApiResponse(
        responseCode = "409",
        description =
            "이미 완료된 가입 신청 (SIGNUP_APPLICATION_ALREADY_COMPLETED, 토큰 재발급 없음) 또는 이미 연결된 OAuth 신원 (OAUTH_IDENTITY_ALREADY_EXISTS)",
        headers = {@Header(name = "Cache-Control", description = "no-store")},
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "SIGNUP_APPLICATION_ALREADY_COMPLETED",
                      value =
                          "{\"code\":\"SIGNUP_APPLICATION_ALREADY_COMPLETED\",\"message\":\"이미 완료된 가입 신청입니다.\"}"),
                  @ExampleObject(
                      name = "OAUTH_IDENTITY_ALREADY_EXISTS",
                      value =
                          "{\"code\":\"OAUTH_IDENTITY_ALREADY_EXISTS\",\"message\":\"이미 연결된 OAuth 계정입니다.\"}")
                })),
    @ApiResponse(
        responseCode = "503",
        description = "인증 상태 저장소 가용성 장애 (AUTH_STATE_UNAVAILABLE)",
        headers = {@Header(name = "Cache-Control", description = "no-store")},
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "AUTH_STATE_UNAVAILABLE",
                      value =
                          "{\"code\":\"AUTH_STATE_UNAVAILABLE\",\"message\":\"인증 상태를 확인할 수 없습니다.\"}")
                }))
  })
  public TokenResponse signup(
      @Valid @RequestBody SignupRequest requestBody,
      HttpServletRequest request,
      HttpServletResponse response) {
    HttpSession session = existingSession(request);
    OnboardingProof proof = currentProof(session);
    if (!proof.proofId().equals(requestBody.signupApplicationId())) {
      throw new DomainException(AuthErrorCode.INVALID_SIGNUP_PROOF);
    }
    validator.requireCurrent(
        requestBody.signupApplicationId(), provider(proof), proof.subject(), proof.expiresAt());
    if (!requestBody.consents().service() || !requestBody.consents().privacy()) {
      throw new DomainException(AuthErrorCode.INVALID_SIGNUP_REQUEST);
    }
    if (!requestBody.consents().ageConfirmed()) {
      finishProofOrReject(session, proof, AuthErrorCode.AGE_CONFIRMATION_REQUIRED);
    }

    // Preserve the User aggregate's exact-value validation without creating a member here.
    User.create(requestBody.displayName(), null);

    UUID claimId = UUID.randomUUID();
    RedisFlowClaimGuard.ClaimSignupResult claimed =
        claims.claimSignup(
            proof.slotId(), proof.proofId(), proof.expiresAt(), claimId, clock.instant());
    if (claimed != RedisFlowClaimGuard.ClaimSignupResult.CLAIMED) {
      if (claimed == RedisFlowClaimGuard.ClaimSignupResult.EXPIRED_CLEANUP_PERMIT
          || claimed == RedisFlowClaimGuard.ClaimSignupResult.EXPIRED_CLAIM_CLEANUP_PERMIT) {
        OnboardingSessionProofs.invalidateIfExact(session, proof.slotId(), proof.proofId());
      }
      throw new DomainException(AuthErrorCode.INVALID_SIGNUP_PROOF);
    }

    SignupApplicationCompletionService.CompletionResult completed;
    try {
      completed =
          completion.complete(
              new SignupApplicationCompletionService.CompletionCommand(
                  requestBody.signupApplicationId(),
                  provider(proof),
                  proof.subject(),
                  requestBody.displayName()));
    } catch (RuntimeException exception) {
      finishFailedClaim(session, proof, claimId);
      throw normalizeCompletionFailure(exception);
    }

    if (!completed.newlyCompleted()) {
      finishCompletedClaimOrReject(session, proof, claimId);
      throw new DomainException(AuthErrorCode.SIGNUP_APPLICATION_ALREADY_COMPLETED);
    }

    LoginGrant grant = null;
    try {
      User member =
          users
              .findByUuid(completed.userUuid())
              .orElseThrow(() -> new IllegalStateException("완료된 가입 회원을 찾을 수 없습니다."));
      grant = sessions.login(member);
      finishCompletedClaimOrReject(session, proof, claimId);
      http.writeRefresh(response, grant);
      return new TokenResponse(grant.accessToken(), "Bearer", JwtTokenService.ACCESS_TOKEN_SECONDS);
    } catch (RuntimeException exception) {
      if (grant == null) {
        finishFailedClaim(session, proof, claimId);
      } else {
        bestEffortRevoke(grant, response);
      }
      throw unavailableIfRecognized(exception);
    }
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  public ResponseEntity<ErrorResponse> invalidRequest() {
    AuthErrorCode code = AuthErrorCode.INVALID_SIGNUP_REQUEST;
    return ResponseEntity.badRequest()
        .cacheControl(CacheControl.noStore())
        .body(new ErrorResponse(code.name(), code.getMessage()));
  }

  private OnboardingProof currentProof(HttpSession session) {
    OnboardingProofResolution local = OnboardingSessionProofs.resolve(session, clock);
    if (local.state() == OnboardingProofResolution.State.MISSING || local.proof() == null) {
      throw new DomainException(AuthErrorCode.INVALID_SIGNUP_PROOF);
    }
    OnboardingProof proof = local.proof();
    if (local.state() == OnboardingProofResolution.State.BINDING_MISMATCH) {
      finishProofOrReject(session, proof, AuthErrorCode.INVALID_SIGNUP_PROOF);
    }
    RedisFlowClaimGuard.InspectProofResult inspected =
        claims.inspectProof(proof.slotId(), proof.proofId(), proof.expiresAt(), clock.instant());
    if (local.state() == OnboardingProofResolution.State.VALID
        && inspected == RedisFlowClaimGuard.InspectProofResult.CURRENT) {
      return proof;
    }
    if (inspected == RedisFlowClaimGuard.InspectProofResult.EXPIRED_CLEANUP_PERMIT
        || inspected == RedisFlowClaimGuard.InspectProofResult.EXPIRED_CLAIM_CLEANUP_PERMIT) {
      OnboardingSessionProofs.invalidateIfExact(session, proof.slotId(), proof.proofId());
    }
    throw new DomainException(AuthErrorCode.INVALID_SIGNUP_PROOF);
  }

  private void finishProofOrReject(
      HttpSession session, OnboardingProof proof, AuthErrorCode error) {
    RedisFlowClaimGuard.FinishProofResult result =
        claims.finishProof(proof.slotId(), proof.proofId(), proof.expiresAt(), clock.instant());
    if (result == RedisFlowClaimGuard.FinishProofResult.CLEANUP_PERMIT) {
      OnboardingSessionProofs.invalidateIfExact(session, proof.slotId(), proof.proofId());
      throw new DomainException(error);
    }
    throw new DomainException(AuthErrorCode.INVALID_SIGNUP_PROOF);
  }

  private void finishCompletedClaimOrReject(
      HttpSession session, OnboardingProof proof, UUID claimId) {
    RedisFlowClaimGuard.FinishSignupResult result =
        claims.finishSignup(
            proof.slotId(), proof.proofId(), proof.expiresAt(), claimId, clock.instant());
    if (result != RedisFlowClaimGuard.FinishSignupResult.FINISHED_CLEANUP_PERMIT
        || !OnboardingSessionProofs.invalidateIfExact(session, proof.slotId(), proof.proofId())) {
      throw new DomainException(AuthErrorCode.INVALID_SIGNUP_PROOF);
    }
  }

  private void finishFailedClaim(HttpSession session, OnboardingProof proof, UUID claimId) {
    try {
      RedisFlowClaimGuard.FinishSignupResult result =
          claims.finishSignup(
              proof.slotId(), proof.proofId(), proof.expiresAt(), claimId, clock.instant());
      if (result == RedisFlowClaimGuard.FinishSignupResult.FINISHED_CLEANUP_PERMIT
          || result == RedisFlowClaimGuard.FinishSignupResult.EXPIRED_CLEANUP_PERMIT) {
        OnboardingSessionProofs.invalidateIfExact(session, proof.slotId(), proof.proofId());
      }
    } catch (RuntimeException ignored) {
      // Preserve the failure that caused this best-effort cleanup.
    }
  }

  private void bestEffortRevoke(LoginGrant grant, HttpServletResponse response) {
    try {
      sessions.revoke(grant);
    } catch (RuntimeException ignored) {
      // Redis TTL bounds an uncertain revocation result.
    }
    try {
      http.clearRefresh(response);
    } catch (RuntimeException ignored) {
      // Preserve the original publication failure.
    }
  }

  private static HttpSession existingSession(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      throw new DomainException(AuthErrorCode.INVALID_SIGNUP_PROOF);
    }
    return session;
  }

  private static OAuthProvider provider(OnboardingProof proof) {
    try {
      return OAuthProvider.valueOf(proof.provider());
    } catch (IllegalArgumentException exception) {
      throw new DomainException(AuthErrorCode.INVALID_SIGNUP_PROOF);
    }
  }

  private RuntimeException unavailableIfRecognized(RuntimeException exception) {
    if (!AuthStateAvailability.isFailure(exception)) {
      return exception;
    }
    log.warn(DB_UNAVAILABLE_LOG, exception.getClass().getName());
    return new DomainException(AuthErrorCode.AUTH_STATE_UNAVAILABLE);
  }

  private RuntimeException normalizeCompletionFailure(RuntimeException exception) {
    if (exception instanceof DomainException domainException
        && domainException.getErrorCode() instanceof AuthErrorCode code
        && switch (code) {
          case SIGNUP_APPLICATION_NOT_FOUND,
              SIGNUP_APPLICATION_IDENTITY_MISMATCH,
              SIGNUP_APPLICATION_EXPIRED,
              SIGNUP_APPLICATION_COMPLETED_INPUT_MISMATCH ->
              true;
          default -> false;
        }) {
      return new DomainException(AuthErrorCode.INVALID_SIGNUP_PROOF);
    }
    return unavailableIfRecognized(exception);
  }
}

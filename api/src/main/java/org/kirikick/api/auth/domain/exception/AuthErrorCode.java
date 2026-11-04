package org.kirikick.api.auth.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.common.exception.ErrorCode;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {
  INVALID_USER_ID("회원 ID는 양수여야 합니다."),
  INVALID_PROVIDER("OAuth 제공자는 필수입니다."),
  INVALID_PROVIDER_SUBJECT("OAuth 제공자의 사용자 식별자는 비어 있을 수 없으며 255자 이하여야 합니다."),
  AUTH_STATE_UNAVAILABLE("인증 상태를 확인할 수 없습니다."),
  INVALID_REFRESH_TOKEN("유효하지 않은 Refresh Token입니다."),
  REFRESH_TOKEN_REUSED("이미 사용된 Refresh Token입니다."),
  AUTHENTICATION_REQUIRED("인증이 필요합니다."),
  ACCOUNT_INACTIVE("사용할 수 없는 회원 상태입니다."),
  INVALID_SIGNUP_PROOF("유효하지 않은 가입 증명입니다."),
  INVALID_SIGNUP_APPLICATION("유효하지 않은 가입 신청입니다."),
  INVALID_SIGNUP_APPLICATION_COMPLETION("가입 신청 완료 정보가 유효하지 않습니다."),
  SIGNUP_APPLICATION_ALREADY_COMPLETED("이미 완료된 가입 신청입니다."),
  SIGNUP_APPLICATION_NOT_FOUND("가입 신청을 찾을 수 없습니다."),
  SIGNUP_APPLICATION_IDENTITY_MISMATCH("가입 신청의 OAuth 식별 정보가 일치하지 않습니다."),
  SIGNUP_APPLICATION_EXPIRED("가입 신청이 만료되었습니다."),
  SIGNUP_APPLICATION_COMPLETED_INPUT_MISMATCH("이미 완료된 가입 신청의 입력 정보가 일치하지 않습니다."),
  SIGNUP_ALREADY_CLAIMED("이미 처리된 가입 요청입니다."),
  OAUTH_FAILED("OAuth 인증에 실패했습니다."),
  INVALID_OAUTH_STATE("유효하지 않은 OAuth 상태입니다."),
  INVALID_SIGNUP_REQUEST("유효하지 않은 가입 요청입니다."),
  AGE_CONFIRMATION_REQUIRED("14세 이상 확인이 필요합니다."),
  OAUTH_IDENTITY_ALREADY_EXISTS("이미 연결된 OAuth 계정입니다."),
  INVALID_DISPLAY_NAME("유효하지 않은 표시 이름입니다.");

  private final String message;
}

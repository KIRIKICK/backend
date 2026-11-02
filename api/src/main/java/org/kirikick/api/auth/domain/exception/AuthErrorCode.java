package org.kirikick.api.auth.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.common.exception.ErrorCode;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {
  INVALID_USER_ID("회원 ID는 양수여야 합니다."),
  INVALID_PROVIDER("OAuth 제공자는 필수입니다."),
  INVALID_PROVIDER_SUBJECT("OAuth 제공자의 사용자 식별자는 비어 있을 수 없으며 255자 이하여야 합니다.");

  private final String message;
}

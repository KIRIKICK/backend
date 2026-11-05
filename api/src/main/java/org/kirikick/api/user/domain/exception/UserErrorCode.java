package org.kirikick.api.user.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.common.exception.ErrorCode;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {
  INVALID_DISPLAY_NAME("표시 이름은 비어 있을 수 없습니다."),
  ACCOUNT_NOT_FOUND("회원을 찾을 수 없습니다."),
  PROFILE_UNAVAILABLE("프로필을 조회할 수 없습니다.");

  private final String message;
}

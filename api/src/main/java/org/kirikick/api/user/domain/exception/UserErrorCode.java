package org.kirikick.api.user.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.common.exception.ErrorCode;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {
  INVALID_DISPLAY_NAME("표시 이름은 비어 있을 수 없습니다.");

  private final String message;
}

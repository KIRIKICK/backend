package org.kirikick.api.room.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.common.exception.ErrorCode;

@Getter
@RequiredArgsConstructor
public enum RoomErrorCode implements ErrorCode {
  INVALID_ROOM("방장 ID와 방 이름이 필요합니다.");

  private final String message;
}

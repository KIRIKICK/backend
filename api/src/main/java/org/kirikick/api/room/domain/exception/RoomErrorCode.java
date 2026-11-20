package org.kirikick.api.room.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.common.exception.ErrorCode;

@Getter
@RequiredArgsConstructor
public enum RoomErrorCode implements ErrorCode {
  ROOM_ALREADY_PUBLISHED("조립 중인 방만 공개할 수 있습니다."),
  INVALID_CLOSING_TIME("마감 시각은 공개 시각에서 24시간 뒤여야 합니다."),
  INCOMPLETE_PUBLICATION_TIME("공개 시각이 불완전한 방은 접수 종료할 수 없습니다.");

  private final String message;
}

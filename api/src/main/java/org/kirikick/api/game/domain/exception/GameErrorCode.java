package org.kirikick.api.game.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.common.exception.ErrorCode;

@Getter
@RequiredArgsConstructor
public enum GameErrorCode implements ErrorCode {
  INVALID_GAME("방·출제자 ID, 게임 종류와 마감 시각이 필요합니다."),
  INVALID_PARTICIPANT("게임·회원 ID와 유효한 시작 시각이 필요합니다."),
  INVALID_ANSWER("참여자와 하나의 유효한 답변, 접수 시각이 필요합니다.");

  private final String message;
}

package org.kirikick.api.game.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.common.exception.ErrorCode;

@Getter
@RequiredArgsConstructor
public enum GameErrorCode implements ErrorCode {
  CORRECT_CHOICE_ALREADY_INITIALIZED("정답은 이미 연결되어 있습니다."),
  INVALID_CORRECT_CHOICE("정답은 저장된 같은 Game의 선택지여야 합니다."),
  INCOMPLETE_GAME("Game 공개에는 저장된 선택지 4개와 연결된 정답이 필요합니다."),
  INVALID_CHOICE("선택지는 같은 질문의 원본과 0~3의 위치가 필요합니다.");

  private final String message;
}

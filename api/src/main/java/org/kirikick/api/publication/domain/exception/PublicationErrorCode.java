package org.kirikick.api.publication.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.common.exception.ErrorCode;

@Getter
@RequiredArgsConstructor
public enum PublicationErrorCode implements ErrorCode {
  INVALID_PUBLICATION_REQUEST_KEY("유효하지 않은 출제 요청 키입니다."),
  INVALID_PUBLICATION_TARGET("출제 종류에 맞는 대상과 질문이 필요합니다."),
  INVALID_PUBLICATION_CHOICES("서로 다른 선택지 4개와 그중 정답이 필요합니다."),
  PUBLICATION_CLAIM_COMPLETION_FAILED("선점한 출제 식별 기록 1건을 의도한 값으로 완성하지 못했습니다."),
  UNREADABLE_PUBLICATION_RECORD("저장된 출제 식별 기록을 복원할 수 없습니다."),
  PUBLICATION_INPUT_SERIALIZATION_FAILED("출제 의미 입력을 저장할 수 없습니다.");

  private final String message;
}

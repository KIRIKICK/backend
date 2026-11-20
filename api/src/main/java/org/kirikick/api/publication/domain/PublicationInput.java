package org.kirikick.api.publication.domain;

import java.util.List;

public record PublicationInput(
    Kind kind,
    Long roomId,
    Long rewardPresetId,
    Long questionPresetId,
    List<Long> choiceIds,
    Long correctChoiceId) {

  public PublicationInput {
    if (kind == null
        || !isId(questionPresetId)
        || (kind == Kind.NK_CREATE && (roomId != null || !isId(rewardPresetId)))
        || (kind == Kind.NK_KICK_ADD && (!isId(roomId) || rewardPresetId != null))) {
      throw new IllegalArgumentException("출제 종류에 맞는 대상과 질문이 필요합니다.");
    }
    if (choiceIds == null
        || choiceIds.size() != 4
        || choiceIds.stream().anyMatch(id -> !isId(id))
        || choiceIds.stream().distinct().count() != 4
        || !isId(correctChoiceId)
        || !choiceIds.contains(correctChoiceId)) {
      throw new IllegalArgumentException("서로 다른 선택지 4개와 그중 정답이 필요합니다.");
    }
    choiceIds = List.copyOf(choiceIds.stream().sorted().toList());
  }

  private static boolean isId(Long id) {
    return id != null && id > 0;
  }

  public enum Kind {
    NK_CREATE,
    NK_KICK_ADD
  }
}

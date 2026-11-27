package org.kirikick.api.publication.domain;

import java.util.List;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;

public record PublicationInput(
    PublicationKind kind,
    Long roomId,
    Long rewardPresetId,
    Long questionPresetId,
    List<Long> choiceIds,
    Long correctChoiceId) {

  public PublicationInput {
    if (kind == null
        || !isId(questionPresetId)
        || (kind == PublicationKind.NK_CREATE && (roomId != null || !isId(rewardPresetId)))
        || (kind == PublicationKind.NK_KICK_ADD && (!isId(roomId) || rewardPresetId != null))) {
      throw new DomainException(PublicationErrorCode.INVALID_PUBLICATION_TARGET);
    }
    if (choiceIds == null
        || choiceIds.size() != 4
        || choiceIds.stream().anyMatch(id -> !isId(id))
        || choiceIds.stream().distinct().count() != 4
        || !isId(correctChoiceId)
        || !choiceIds.contains(correctChoiceId)) {
      throw new DomainException(PublicationErrorCode.INVALID_PUBLICATION_CHOICES);
    }
    choiceIds = List.copyOf(choiceIds.stream().sorted().toList());
  }

  private static boolean isId(Long id) {
    return id != null && id > 0;
  }
}

package org.kirikick.api.publication.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.domain.PublicationKind;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;

/** Strict wire representation for adding a kick to an existing room. */
public record NkKickAddRequest(
    @NotBlank @Pattern(regexp = "[1-9][0-9]*") String questionPresetId,
    @NotNull @Size(min = 4, max = 4)
        List<@NotBlank @Pattern(regexp = "[1-9][0-9]*") String> choiceIds,
    @NotBlank @Pattern(regexp = "[1-9][0-9]*") String correctChoiceId) {
  public NkKickAddRequest {
    choiceIds = choiceIds == null ? null : List.copyOf(choiceIds);
  }

  public PublicationInput toInput(long roomId) {
    return new PublicationInput(
        PublicationKind.NK_KICK_ADD,
        roomId,
        null,
        parseId(questionPresetId),
        choiceIds.stream().map(NkKickAddRequest::parseId).toList(),
        parseId(correctChoiceId));
  }

  private static Long parseId(String value) {
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException exception) {
      throw new DomainException(PublicationErrorCode.INVALID_PUBLICATION_INPUT);
    }
  }
}

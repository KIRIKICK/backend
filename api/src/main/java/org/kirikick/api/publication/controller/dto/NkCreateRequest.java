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

/** Strict wire representation: IDs must be canonical positive decimal strings. */
public record NkCreateRequest(
    @NotBlank @Pattern(regexp = "[1-9][0-9]*") String questionPresetId,
    @NotNull @Size(min = 4, max = 4)
        List<@NotBlank @Pattern(regexp = "[1-9][0-9]*") String> choiceIds,
    @NotBlank @Pattern(regexp = "[1-9][0-9]*") String correctChoiceId,
    @NotBlank @Pattern(regexp = "[1-9][0-9]*") String rewardPresetId) {
  public NkCreateRequest {
    choiceIds = choiceIds == null ? null : List.copyOf(choiceIds);
  }

  public PublicationInput toInput() {
    return new PublicationInput(
        PublicationKind.NK_CREATE,
        null,
        parseId(rewardPresetId),
        parseId(questionPresetId),
        choiceIds.stream().map(NkCreateRequest::parseId).toList(),
        parseId(correctChoiceId));
  }

  private static Long parseId(String value) {
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException exception) {
      throw invalidInput();
    }
  }

  private static DomainException invalidInput() {
    return new DomainException(PublicationErrorCode.INVALID_PUBLICATION_INPUT);
  }
}

package org.kirikick.api.publication.service.model;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.kirikick.api.room.repository.projection.PublicationState;

public record PublicationWindow(Instant openedAt, Instant closesAt) {

  public static PublicationWindow from(PublicationState state) {
    if (!state.getOpenedAtPresent()
        || !state.getClosesAtPresent()
        || !state.getOpenedAtFinite()
        || !state.getClosesAtFinite()) {
      throw new DomainException(PublicationErrorCode.ROOM_NOT_FOUND);
    }
    try {
      Instant openedAt = OffsetDateTime.parse(state.getRawOpenedAt().replace(" ", "T")).toInstant();
      Instant closesAt = OffsetDateTime.parse(state.getRawClosesAt().replace(" ", "T")).toInstant();
      if (!openedAt.plus(Duration.ofHours(24)).equals(closesAt)) {
        throw new DomainException(PublicationErrorCode.ROOM_NOT_FOUND);
      }
      return new PublicationWindow(openedAt, closesAt);
    } catch (DateTimeException | ArithmeticException exception) {
      throw new DomainException(PublicationErrorCode.ROOM_NOT_FOUND);
    }
  }
}

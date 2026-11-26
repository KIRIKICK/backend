package org.kirikick.api.publication.repository.model;

import java.util.UUID;
import org.kirikick.api.publication.domain.PublicationInput;

public record PublicationClaim(
    long userId, String requestKey, PublicationInput input, String xid, UUID id)
    implements LockedReservation {

  @Override
  public String toString() {
    return "PublicationClaim[kind=" + (input == null ? null : input.kind()) + "]";
  }
}

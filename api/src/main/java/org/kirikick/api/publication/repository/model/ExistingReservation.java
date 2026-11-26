package org.kirikick.api.publication.repository.model;

import java.util.UUID;

/** A locked but deliberately undecoded existing row. */
public record ExistingReservation(
    long userId,
    String requestKey,
    String rawInput,
    Long gameId,
    String rawExpiry,
    boolean finiteExpiry,
    UUID claimId)
    implements LockedReservation {

  @Override
  public String toString() {
    return "ExistingReservation[hasGameId="
        + (gameId != null)
        + ", hasExpiry="
        + (rawExpiry != null)
        + ", finiteExpiry="
        + finiteExpiry
        + "]";
  }
}

package org.kirikick.api.publication.domain;

import java.time.Instant;
import java.util.UUID;

public record PublicationIdempotencyRecord(
    long userId,
    String requestKey,
    PublicationInput normalizedInput,
    Long gameId,
    Instant recordExpiresAt,
    UUID claimId) {

  public boolean isComplete() {
    return gameId != null && recordExpiresAt != null;
  }
}

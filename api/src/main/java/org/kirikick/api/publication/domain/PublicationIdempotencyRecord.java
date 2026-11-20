package org.kirikick.api.publication.domain;

import java.time.Instant;

public record PublicationIdempotencyRecord(
    long userId,
    String requestKey,
    PublicationInput normalizedInput,
    Long gameId,
    Instant recordExpiresAt) {

  public boolean isComplete() {
    return gameId != null && recordExpiresAt != null;
  }
}

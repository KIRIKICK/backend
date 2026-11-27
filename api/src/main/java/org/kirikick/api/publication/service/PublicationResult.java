package org.kirikick.api.publication.service;

import java.time.Instant;

public record PublicationResult(
    boolean created, long roomId, long gameId, Instant openedAt, Instant closesAt) {

  public static PublicationResult created(
      long roomId, long gameId, Instant openedAt, Instant closesAt) {
    return new PublicationResult(true, roomId, gameId, openedAt, closesAt);
  }

  public static PublicationResult replayed(
      long roomId, long gameId, Instant openedAt, Instant closesAt) {
    return new PublicationResult(false, roomId, gameId, openedAt, closesAt);
  }
}

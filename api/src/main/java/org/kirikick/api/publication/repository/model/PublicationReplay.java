package org.kirikick.api.publication.repository.model;

import java.time.Instant;

public record PublicationReplay(long roomId, long gameId, Instant openedAt, Instant closesAt)
    implements PublicationReservation {}

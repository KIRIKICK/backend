package org.kirikick.api.auth.domain;

import java.time.Instant;
import java.util.UUID;

public record LoginSession(
    String id,
    Long userId,
    UUID userUuid,
    Instant createdAt,
    Instant absoluteExpiresAt,
    Instant expiresAt) {}

package org.kirikick.api.auth.onboarding;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import org.springframework.lang.Nullable;

public record OnboardingProof(
    UUID slotId,
    String provider,
    String subject,
    @Nullable String suggestedDisplayName,
    UUID proofId,
    Instant issuedAt,
    Instant expiresAt,
    String sessionId)
    implements Serializable {}

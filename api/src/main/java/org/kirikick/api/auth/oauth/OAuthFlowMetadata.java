package org.kirikick.api.auth.oauth;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

record OAuthFlowMetadata(
    UUID slotId, UUID flowId, String state, Instant expiresAt, String sessionId)
    implements Serializable {}

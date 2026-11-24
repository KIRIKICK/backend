package org.kirikick.api.publication.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record NkCreateResponse(
    @Schema(example = "8402", requiredMode = Schema.RequiredMode.REQUIRED) String roomId,
    @Schema(example = "8509", requiredMode = Schema.RequiredMode.REQUIRED) String gameId,
    @Schema(example = "2030-06-12T09:15:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
        Instant openedAt,
    @Schema(example = "2030-06-13T09:15:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
        Instant closesAt) {}

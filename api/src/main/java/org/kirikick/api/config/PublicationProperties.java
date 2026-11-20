package org.kirikick.api.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "publication")
public record PublicationProperties(@NotNull Duration grace) {

  @AssertTrue(message = "Grace는 음수가 아닌 마이크로초 단위 기간이어야 합니다.")
  public boolean isGraceValid() {
    return grace != null && !grace.isNegative() && grace.getNano() % 1_000 == 0;
  }

  public Instant recordExpiresAt(Instant openedAt) {
    return openedAt.plus(Duration.ofHours(24).plusMinutes(30).plus(grace));
  }
}

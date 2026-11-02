package org.kirikick.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class JpaAuditingConfigTest {

  @Test
  void providesCurrentTimeAsInstant() {
    Instant before = Instant.now();
    assertThat(new JpaAuditingConfig().auditingDateTimeProvider().getNow())
        .hasValueSatisfying(now ->
            assertThat(now)
                .isInstanceOfSatisfying(
                    Instant.class,
                    instant -> assertThat(instant).isBetween(before, Instant.now())));
  }
}

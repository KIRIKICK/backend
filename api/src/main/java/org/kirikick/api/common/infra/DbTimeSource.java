package org.kirikick.api.common.infra;

import java.time.Instant;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DbTimeSource {

  private final JdbcClient jdbc;

  public Instant readCurrentTime() {
    return jdbc.sql("select clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
  }
}

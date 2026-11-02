package org.kirikick.testsupport.postgres;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class PostgresSpringTestSupport {

  @DynamicPropertySource
  static void configurePostgres(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> PostgresTestDatabase.container().getJdbcUrl());
    registry.add(
        "spring.datasource.username", () -> PostgresTestDatabase.container().getUsername());
    registry.add(
        "spring.datasource.password", () -> PostgresTestDatabase.container().getPassword());
  }
}

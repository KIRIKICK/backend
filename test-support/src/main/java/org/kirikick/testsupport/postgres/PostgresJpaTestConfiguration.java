package org.kirikick.testsupport.postgres;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

@TestConfiguration(proxyBeanMethods = false)
class PostgresJpaTestConfiguration {

  @Bean
  DynamicPropertyRegistrar postgresProperties() {
    return registry -> {
      registry.add("spring.datasource.url", () -> PostgresTestDatabase.container().getJdbcUrl());
      registry.add(
          "spring.datasource.username", () -> PostgresTestDatabase.container().getUsername());
      registry.add(
          "spring.datasource.password", () -> PostgresTestDatabase.container().getPassword());
      registry.add(
          "spring.datasource.hikari.jdbc-url", () -> PostgresTestDatabase.container().getJdbcUrl());
      registry.add(
          "spring.datasource.hikari.username",
          () -> PostgresTestDatabase.container().getUsername());
      registry.add(
          "spring.datasource.hikari.password",
          () -> PostgresTestDatabase.container().getPassword());
    };
  }
}

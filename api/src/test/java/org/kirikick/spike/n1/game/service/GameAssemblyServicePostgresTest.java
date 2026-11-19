package org.kirikick.spike.n1.game.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.ApiApplication;
import org.kirikick.api.auth.domain.SignupApplication;
import org.kirikick.api.support.JpaSliceTest;
import org.kirikick.api.user.domain.User;
import org.kirikick.spike.n1.N1JpaTestConfiguration;
import org.kirikick.spike.n1.game.domain.Game;
import org.kirikick.spike.n1.reward.domain.RewardPreset;
import org.kirikick.spike.n1.room.domain.Room;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real Flyway schema; the test-only schema is discarded with the PostgreSQL container. */
@ContextConfiguration(classes = {ApiApplication.class, N1JpaTestConfiguration.class})
@JpaSliceTest
@EntityScan(
    basePackageClasses = {
      User.class,
      SignupApplication.class,
      Room.class,
      Game.class,
      RewardPreset.class
    })
class GameAssemblyServicePostgresTest extends GameAssemblyContract {

  private static final String SCHEMA =
      "n1_assembly_" + UUID.randomUUID().toString().replace("-", "");

  @Autowired private EntityManager entityManager;
  @Autowired private DataSource dataSource;
  @Autowired private Flyway flyway;

  @DynamicPropertySource
  static void migratedSchema(DynamicPropertyRegistry registry) {
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.schemas", () -> SCHEMA);
    registry.add("spring.flyway.default-schema", () -> SCHEMA);
    registry.add(
        "spring.flyway.locations", () -> "classpath:db/spike/baseline,classpath:db/spike/n1");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    registry.add(
        "spring.datasource.hikari.connection-init-sql", () -> "set search_path to " + SCHEMA);
  }

  @Test
  @DisplayName("H2·create-drop 대체 없이 Flyway가 적용한 PostgreSQL에서 조립 계약을 검증한다")
  void usesTheMigratedPostgresSchema() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
      assertThat(connection.getSchema()).isEqualTo(SCHEMA);
    }
    assertThat(entityManager.getEntityManagerFactory().getProperties())
        .containsEntry("hibernate.hbm2ddl.auto", "validate");
    assertThat(flyway.info().applied())
        .filteredOn(info -> info.getVersion() != null)
        .extracting(info -> info.getVersion().getVersion())
        .containsExactly("1", "2", "9000");
    flyway.validate();
  }
}

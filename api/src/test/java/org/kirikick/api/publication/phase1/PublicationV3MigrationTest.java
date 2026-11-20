package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.game.domain.Choice;
import org.kirikick.api.game.domain.Game;
import org.kirikick.api.question.domain.QuestionPreset;
import org.kirikick.api.question.domain.QuestionPresetChoice;
import org.kirikick.api.reward.domain.RewardPreset;
import org.kirikick.api.room.domain.Room;
import org.kirikick.api.user.domain.User;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;

class PublicationV3MigrationTest {

  private final String schema = "publication_v3_" + UUID.randomUUID().toString().replace("-", "");
  private final String url = PostgresTestDatabase.container().getJdbcUrl();
  private final String username = PostgresTestDatabase.container().getUsername();
  private final String password = PostgresTestDatabase.container().getPassword();

  @AfterEach
  void dropOnlyThisSchema() throws SQLException {
    try (var connection = connect();
        var statement = connection.createStatement()) {
      statement.execute("drop schema if exists " + schema + " cascade");
    }
  }

  @Test
  @DisplayName("V2를 V3까지만 업그레이드해 매핑과 방장당 OPEN 방 제한을 확인한다")
  void upgradesAndValidatesPublicationCoreWithoutV4() throws SQLException {
    assertThat(flyway("2").migrate().migrationsExecuted).isEqualTo(2);
    var v3 = flyway("3");
    assertThat(v3.migrate().migrationsExecuted).isEqualTo(1);
    assertThat(v3.info().current().getVersion().getVersion()).isEqualTo("3");
    validateMappings();

    try (var connection = connect();
        var statement = connection.createStatement()) {
      connection.setSchema(schema);
      statement.executeUpdate(
          """
          with host as (
              insert into users (uuid, display_name, status, created_at, updated_at)
              values (gen_random_uuid(), 'host', 'ACTIVE', now(), now()) returning id
          ), reward as (
              insert into reward_presets (title, description)
              values ('reward', 'description') returning id
          )
          insert into rooms (host_user_id, selected_reward_preset_id, status, created_at)
          select host.id, reward.id, 'OPEN', now() from host cross join reward
          """);
      assertThatThrownBy(() ->
              statement.executeUpdate(
                  """
                  insert into rooms (host_user_id, selected_reward_preset_id, status, created_at)
                  select host_user_id, selected_reward_preset_id, 'OPEN', now() from rooms
                  """))
          .isInstanceOfSatisfying(
              SQLException.class,
              exception -> {
                assertThat(exception.getSQLState()).isEqualTo("23505");
                assertThat(exception.getMessage()).contains("uk_rooms_open_per_host");
              });
    }
  }

  private Flyway flyway(String target) {
    return Flyway.configure()
        .dataSource(url, username, password)
        .schemas(schema)
        .defaultSchema(schema)
        .createSchemas(true)
        .cleanDisabled(true)
        .locations("classpath:db/migration")
        .target(target)
        .load();
  }

  private void validateMappings() {
    var registry =
        new StandardServiceRegistryBuilder()
            .applySettings(
                Map.of(
                    AvailableSettings.JAKARTA_JDBC_URL, url,
                    AvailableSettings.JAKARTA_JDBC_USER, username,
                    AvailableSettings.JAKARTA_JDBC_PASSWORD, password,
                    AvailableSettings.DEFAULT_SCHEMA, schema,
                    AvailableSettings.HBM2DDL_AUTO, "validate",
                    AvailableSettings.PHYSICAL_NAMING_STRATEGY,
                        CamelCaseToUnderscoresNamingStrategy.class))
            .build();
    try (SessionFactory ignored =
        new MetadataSources(registry)
            .addAnnotatedClass(User.class)
            .addAnnotatedClass(RewardPreset.class)
            .addAnnotatedClass(QuestionPreset.class)
            .addAnnotatedClass(QuestionPresetChoice.class)
            .addAnnotatedClass(Room.class)
            .addAnnotatedClass(Game.class)
            .addAnnotatedClass(Choice.class)
            .buildMetadata()
            .buildSessionFactory()) {
      // Building the SessionFactory performs Hibernate validate.
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
  }

  private Connection connect() throws SQLException {
    return DriverManager.getConnection(url, username, password);
  }
}

package org.kirikick.api.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.user.domain.User;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class UserAuthSchemaMigrationTest {

  private final String url = System.getenv("TEST_POSTGRES_URL");
  private final String username =
      System.getenv().getOrDefault("TEST_POSTGRES_USERNAME", "postgres");
  private final String password = System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", "");
  private final String schema = "user_auth_" + UUID.randomUUID().toString().replace("-", "");

  @AfterEach
  void dropOnlyTheSchemaCreatedByThisTest() throws SQLException {
    try (Connection connection = DriverManager.getConnection(url, username, password);
        Statement statement = connection.createStatement()) {
      statement.execute("drop schema if exists " + schema + " cascade");
    }
  }

  @Test
  void migratesAndValidatesUserAndOAuthIdentityMappings() {
    Flyway flyway = migrate();

    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    validateHibernateMappings();

    try (Connection connection = connectionInSchema()) {
      assertThat(columnExists(connection, "users", "birth_year")).isTrue();
      assertThat(columnExists(connection, "oauth_identities", "updated_at")).isFalse();
      assertThat(columnExists(connection, "users", "age_band")).isFalse();
      assertThat(columnExists(connection, "users", "linked_providers")).isFalse();
    } catch (SQLException exception) {
      throw new IllegalStateException(exception);
    }
  }

  @Test
  void createsOnlyTheAgreedColumnsWithTheirDatabaseContracts() throws SQLException {
    migrate();

    try (Connection connection = connectionInSchema()) {
      assertThat(tableNames(connection))
          .containsExactlyInAnyOrder("flyway_schema_history", "oauth_identities", "users");
      assertThat(columns(connection, "users"))
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(
                  "id", new ColumnDefinition("bigint", false, null, true),
                  "uuid", new ColumnDefinition("uuid", false, null, false),
                  "display_name", new ColumnDefinition("text", false, null, false),
                  "profile_image_url", new ColumnDefinition("text", true, null, false),
                  "birth_year", new ColumnDefinition("integer", true, null, false),
                  "status", new ColumnDefinition("character varying", false, 20, false),
                  "withdrawn_at",
                      new ColumnDefinition("timestamp with time zone", true, null, false),
                  "created_at",
                      new ColumnDefinition("timestamp with time zone", false, null, false),
                  "updated_at",
                      new ColumnDefinition("timestamp with time zone", false, null, false)));
      assertThat(columns(connection, "oauth_identities"))
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(
                  "id", new ColumnDefinition("bigint", false, null, true),
                  "user_id", new ColumnDefinition("bigint", false, null, false),
                  "provider", new ColumnDefinition("character varying", false, 20, false),
                  "provider_subject", new ColumnDefinition("character varying", false, 255, false),
                  "created_at",
                      new ColumnDefinition("timestamp with time zone", false, null, false)));
    }
  }

  @Test
  void enforcesNamedConstraintsAndNullableFields() throws SQLException {
    migrate();
    try (Connection connection = connectionInSchema()) {
      UUID firstUuid = UUID.randomUUID();
      long firstUserId = insertUser(connection, firstUuid, "ACTIVE", null, null, null);
      long secondUserId =
          insertUser(
              connection, UUID.randomUUID(), "WITHDRAWN", null, 2000, "2026-01-01T00:00:00Z");
      long thirdUserId = insertUser(connection, UUID.randomUUID(), "ACTIVE", null, null, null);
      long fourthUserId = insertUser(connection, UUID.randomUUID(), "ACTIVE", null, null, null);

      assertSqlFails(
          () -> insertUser(connection, UUID.randomUUID(), "PENDING", null, null, null),
          "23514",
          "ck_users_status");
      assertSqlFails(
          () -> insertUser(connection, firstUuid, "ACTIVE", null, null, null),
          "23505",
          "uk_users_uuid");
      assertSqlFails(
          () ->
              execute(
                  connection,
                  "insert into users (display_name, status, created_at, updated_at) "
                      + "values ('missing-uuid', 'ACTIVE', now(), now())"),
          "23502",
          "uuid");
      assertSqlFails(
          () -> insertIdentity(connection, 999_999L, "KAKAO", "missing-user"),
          "23503",
          "fk_oauth_identities_user");
      assertSqlFails(
          () ->
              execute(
                  connection,
                  "insert into oauth_identities (provider, provider_subject, created_at) "
                      + "values ('KAKAO', 'missing-user-id', now())"),
          "23502",
          "user_id");
      assertSqlFails(
          () ->
              execute(
                  connection,
                  "insert into oauth_identities (user_id, provider, created_at) "
                      + "values ("
                      + firstUserId
                      + ", 'KAKAO', now())"),
          "23502",
          "provider_subject");

      insertIdentity(connection, firstUserId, "KAKAO", "same-subject");
      assertSqlFails(
          () -> insertIdentity(connection, secondUserId, "KAKAO", "same-subject"),
          "23505",
          "uk_oauth_identities_provider_subject");
      assertSqlFails(
          () -> insertIdentity(connection, firstUserId, "KAKAO", "different-subject"),
          "23505",
          "uk_oauth_identities_user_provider");
      insertIdentity(connection, firstUserId, "APPLE", "same-subject");
      insertIdentity(connection, thirdUserId, "KAKAO", "AbC");
      insertIdentity(connection, fourthUserId, "KAKAO", "abc");

      assertSqlFails(
          () -> insertIdentity(connection, secondUserId, "UNKNOWN", "unknown-provider"),
          "23514",
          "ck_oauth_identities_provider");
      assertSqlFails(
          () -> execute(connection, "delete from users where id = " + firstUserId),
          "23503",
          "fk_oauth_identities_user");
      assertThat(queryString(connection, "select uuid::text from users where id = " + firstUserId))
          .isEqualTo(firstUuid.toString());
      assertThat(
              queryLong(
                  connection,
                  "select count(*) from oauth_identities where user_id = " + firstUserId))
          .isEqualTo(2);
    }
  }

  @Test
  void persistsAllFourStatusValuesWithoutAddingAgeOrSessionColumns() throws SQLException {
    migrate();

    try (Connection connection = connectionInSchema()) {
      for (String status : List.of("ACTIVE", "WITHDRAWN", "SUSPENDED", "BANNED")) {
        UUID uuid = UUID.randomUUID();
        insertUser(connection, uuid, status, "https://example.test/profile.png", 2000, null);
        assertThat(
                queryString(
                    connection,
                    "select status from users where uuid = " + quote(uuid.toString()) + "::uuid"))
            .isEqualTo(status);
      }
    }
  }

  @Test
  void preservesWithdrawnUserFieldsAndOAuthIdentityAfterReconnect() throws SQLException {
    migrate();
    UUID uuid = UUID.randomUUID();

    long userId;
    try (Connection connection = connectionInSchema()) {
      userId =
          insertUser(
              connection,
              uuid,
              "WITHDRAWN",
              "https://example.test/withdrawn.png",
              2000,
              "2026-01-01T00:00:00Z");
      insertIdentity(connection, userId, "KAKAO", "withdrawn-subject");
    }

    try (Connection connection = connectionInSchema()) {
      assertThat(queryString(connection, "select uuid::text from users where id = " + userId))
          .isEqualTo(uuid.toString());
      assertThat(
              queryString(connection, "select profile_image_url from users where id = " + userId))
          .isEqualTo("https://example.test/withdrawn.png");
      assertThat(queryString(connection, "select birth_year::text from users where id = " + userId))
          .isEqualTo("2000");
      assertThat(
              queryString(
                  connection,
                  "select (withdrawn_at = '2026-01-01T00:00:00Z'::timestamptz)::text "
                      + "from users where id = "
                      + userId))
          .isEqualTo("true");
      assertThat(
              queryLong(
                  connection,
                  "select user_id from oauth_identities where provider = 'KAKAO' "
                      + "and provider_subject = 'withdrawn-subject'"))
          .isEqualTo(userId);
    }
  }

  @Test
  void rejectsBothUniqueConstraintRaces() throws Exception {
    migrate();
    try (Connection connection = connectionInSchema()) {
      long firstUserId = insertUser(connection, UUID.randomUUID(), "ACTIVE", null, null, null);
      long secondUserId = insertUser(connection, UUID.randomUUID(), "ACTIVE", null, null, null);

      assertOneSuccessAndNamedUniqueFailure(
          () -> {
            insertIdentityInNewConnection(firstUserId, "KAKAO", "racing-subject");
            return null;
          },
          () -> {
            insertIdentityInNewConnection(secondUserId, "KAKAO", "racing-subject");
            return null;
          },
          "uk_oauth_identities_provider_subject");
      assertOneSuccessAndNamedUniqueFailure(
          () -> {
            insertIdentityInNewConnection(firstUserId, "APPLE", "first-apple-subject");
            return null;
          },
          () -> {
            insertIdentityInNewConnection(firstUserId, "APPLE", "second-apple-subject");
            return null;
          },
          "uk_oauth_identities_user_provider");
    }
  }

  private Flyway migrate() {
    Flyway flyway =
        Flyway.configure()
            .dataSource(url, username, password)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .cleanDisabled(true)
            .load();
    flyway.migrate();
    return flyway;
  }

  private void validateHibernateMappings() {
    StandardServiceRegistry registry =
        new StandardServiceRegistryBuilder()
            .applySettings(
                Map.of(
                    "hibernate.connection.url", url,
                    "hibernate.connection.username", username,
                    "hibernate.connection.password", password,
                    "hibernate.connection.driver_class", "org.postgresql.Driver",
                    "hibernate.default_schema", schema,
                    "hibernate.hbm2ddl.auto", "validate",
                    "hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"))
            .build();
    try (SessionFactory ignored =
        new MetadataSources(registry)
            .addAnnotatedClass(User.class)
            .addAnnotatedClass(OAuthIdentity.class)
            .buildMetadata()
            .buildSessionFactory()) {
      // Building the SessionFactory performs Hibernate validate.
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
  }

  private Connection connectionInSchema() throws SQLException {
    Connection connection = DriverManager.getConnection(url, username, password);
    execute(connection, "set search_path to " + schema);
    return connection;
  }

  private long insertUser(
      Connection connection,
      UUID uuid,
      String status,
      String profileImageUrl,
      Integer birthYear,
      String withdrawnAt)
      throws SQLException {
    String profileValue = profileImageUrl == null ? "null" : quote(profileImageUrl);
    String birthYearValue = birthYear == null ? "null" : birthYear.toString();
    String withdrawnAtValue = withdrawnAt == null ? "null" : quote(withdrawnAt) + "::timestamptz";
    try (Statement statement = connection.createStatement()) {
      var resultSet =
          statement.executeQuery(
              "insert into users (uuid, display_name, profile_image_url, birth_year, status, withdrawn_at, created_at, updated_at) "
                  + "values ("
                  + quote(uuid.toString())
                  + "::uuid, 'display-name', "
                  + profileValue
                  + ", "
                  + birthYearValue
                  + ", "
                  + quote(status)
                  + ", "
                  + withdrawnAtValue
                  + ", now(), now()) returning id");
      resultSet.next();
      return resultSet.getLong(1);
    }
  }

  private void insertIdentity(Connection connection, long userId, String provider, String subject)
      throws SQLException {
    execute(
        connection,
        "insert into oauth_identities (user_id, provider, provider_subject, created_at) values ("
            + userId
            + ", "
            + quote(provider)
            + ", "
            + quote(subject)
            + ", now())");
  }

  private void insertIdentityInNewConnection(long userId, String provider, String subject)
      throws SQLException {
    try (Connection connection = connectionInSchema()) {
      insertIdentity(connection, userId, provider, subject);
    }
  }

  private boolean columnExists(Connection connection, String tableName, String columnName)
      throws SQLException {
    try (var resultSet = connection.getMetaData().getColumns(null, schema, tableName, columnName)) {
      return resultSet.next();
    }
  }

  private Set<String> tableNames(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "select table_name from information_schema.tables where table_schema = "
                    + quote(schema)
                    + " and table_type = 'BASE TABLE'")) {
      Set<String> tableNames = new java.util.HashSet<>();
      while (resultSet.next()) {
        tableNames.add(resultSet.getString(1));
      }
      return tableNames;
    }
  }

  private Map<String, ColumnDefinition> columns(Connection connection, String tableName)
      throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "select column_name, data_type, is_nullable, character_maximum_length, is_identity "
                    + "from information_schema.columns where table_schema = "
                    + quote(schema)
                    + " and table_name = "
                    + quote(tableName))) {
      Map<String, ColumnDefinition> columns = new HashMap<>();
      while (resultSet.next()) {
        columns.put(
            resultSet.getString("column_name"),
            new ColumnDefinition(
                resultSet.getString("data_type"),
                "YES".equals(resultSet.getString("is_nullable")),
                (Integer) resultSet.getObject("character_maximum_length"),
                "YES".equals(resultSet.getString("is_identity"))));
      }
      return columns;
    }
  }

  private long queryLong(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      resultSet.next();
      return resultSet.getLong(1);
    }
  }

  private String queryString(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      resultSet.next();
      return resultSet.getString(1);
    }
  }

  private void assertOneSuccessAndNamedUniqueFailure(
      Callable<Void> first, Callable<Void> second, String constraintName) throws Exception {
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Void> firstResult = executor.submit(first);
      Future<Void> secondResult = executor.submit(second);
      int successes = 0;
      int failures = 0;
      for (Future<Void> result : List.of(firstResult, secondResult)) {
        try {
          result.get();
          successes++;
        } catch (Exception exception) {
          Throwable cause = exception.getCause();
          assertThat(cause).isInstanceOf(SQLException.class);
          SQLException sqlException = (SQLException) cause;
          assertThat(sqlException.getSQLState()).isEqualTo("23505");
          assertThat(sqlException.getMessage()).contains(constraintName);
          failures++;
        }
      }
      assertThat(successes).isEqualTo(1);
      assertThat(failures).isEqualTo(1);
    }
  }

  private void assertSqlFails(SqlAction action, String sqlState, String constraintName) {
    assertThatThrownBy(action::run)
        .isInstanceOf(SQLException.class)
        .satisfies(throwable -> {
          SQLException exception = (SQLException) throwable;
          assertThat(exception.getSQLState()).isEqualTo(sqlState);
          assertThat(exception.getMessage()).contains(constraintName);
        });
  }

  private void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private String quote(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  @FunctionalInterface
  private interface SqlAction {
    void run() throws SQLException;
  }

  private record ColumnDefinition(
      String dataType, boolean nullable, Integer length, boolean identity) {}
}

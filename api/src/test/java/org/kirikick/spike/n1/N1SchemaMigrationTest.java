package org.kirikick.spike.n1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.SignupApplication;
import org.kirikick.api.user.domain.User;
import org.kirikick.spike.n1.game.domain.Choice;
import org.kirikick.spike.n1.game.domain.Game;
import org.kirikick.spike.n1.reward.domain.RewardPreset;
import org.kirikick.spike.n1.room.domain.Room;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;

class N1SchemaMigrationTest {

  private final String url = PostgresTestDatabase.container().getJdbcUrl();
  private final String username = PostgresTestDatabase.container().getUsername();
  private final String password = PostgresTestDatabase.container().getPassword();
  private final String schema = "n1_schema_" + UUID.randomUUID().toString().replace("-", "");

  @AfterEach
  void dropOnlyTheSchemaCreatedByThisTest() throws SQLException {
    try (Connection connection = DriverManager.getConnection(url, username, password);
        Statement statement = connection.createStatement()) {
      statement.execute("drop schema if exists " + schema + " cascade");
    }
  }

  @Test
  @DisplayName("N1 테이블은 선택한 최소 컬럼과 조립 nullable을 가진다")
  void createsN1TablesWithTheAgreedAssemblyColumns() throws SQLException {
    Flyway flyway = migrate();
    assertThat(flyway.info().applied())
        .filteredOn(info -> info.getVersion() != null)
        .extracting(info -> info.getVersion().getVersion())
        .containsExactly("1", "2", "9000");
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    validateHibernateMappings(
        User.class,
        OAuthIdentity.class,
        SignupApplication.class,
        RewardPreset.class,
        Room.class,
        Game.class,
        Choice.class);

    try (Connection connection = connectionInSchema()) {
      assertThat(columnExists(connection, "rooms", "opened_at")).isTrue();
      assertThat(columnExists(connection, "games", "correct_choice_id")).isTrue();
      assertThat(tableNames(connection))
          .containsExactlyInAnyOrder(
              "flyway_schema_history",
              "oauth_identities",
              "signup_applications",
              "users",
              "reward_presets",
              "rooms",
              "games",
              "choices");
      assertThat(columns(connection, "reward_presets"))
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(
                  "id", new ColumnDefinition("bigint", false, null, true),
                  "title", new ColumnDefinition("text", false, null, false),
                  "description", new ColumnDefinition("text", false, null, false)));
      assertThat(columns(connection, "rooms"))
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(
                  "id", new ColumnDefinition("bigint", false, null, true),
                  "host_user_id", new ColumnDefinition("bigint", false, null, false),
                  "selected_reward_preset_id", new ColumnDefinition("bigint", false, null, false),
                  "created_at",
                      new ColumnDefinition("timestamp with time zone", false, null, false),
                  "opened_at", new ColumnDefinition("timestamp with time zone", true, null, false),
                  "confirmed_early_end_at",
                      new ColumnDefinition("timestamp with time zone", true, null, false)));
      assertThat(columns(connection, "games"))
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(
                  "id", new ColumnDefinition("bigint", false, null, true),
                  "room_id", new ColumnDefinition("bigint", false, null, false),
                  "ordinal", new ColumnDefinition("integer", false, null, false),
                  "question_text_snapshot", new ColumnDefinition("text", false, null, false),
                  "correct_choice_id", new ColumnDefinition("bigint", true, null, false),
                  "created_at",
                      new ColumnDefinition("timestamp with time zone", false, null, false)));
      assertThat(columns(connection, "choices"))
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(
                  "id", new ColumnDefinition("bigint", false, null, true),
                  "game_id", new ColumnDefinition("bigint", false, null, false),
                  "position", new ColumnDefinition("integer", false, null, false),
                  "text_snapshot", new ColumnDefinition("text", false, null, false)));
    }
  }

  @Test
  @DisplayName("선택한 초기 보상 세 개와 N1 일반 FK 계약을 적용한다")
  void seedsRewardsAndEnforcesN1ForeignKeysWithoutSameGameConstraint() throws SQLException {
    migrate();

    try (Connection connection = connectionInSchema()) {
      assertThat(
              queryString(
                  connection,
                  "select string_agg(title || ':' || description, '|' order by id) from"
                      + " reward_presets"))
          .isEqualTo(
              "리액션 한가득:방장이 1등에게 신나게 리액션해 줘요.|칭찬 세 마디:방장이 1등에게 칭찬 세 마디를 들려줘요.|메뉴 결정권:방장과 밥 먹을 때, 1등이"
                  + " 메뉴를 골라요.");

      long hostId = insertUser(connection, UUID.randomUUID(), "ACTIVE", null, null, null);
      long rewardPresetId =
          queryLong(connection, "select id from reward_presets order by id limit 1");
      assertSqlFails(
          () ->
              execute(
                  connection,
                  "insert into rooms (host_user_id, selected_reward_preset_id, created_at) values"
                      + " (999999, "
                      + rewardPresetId
                      + ", now())"),
          "23503",
          "fk_rooms_host_user");
      assertSqlFails(
          () ->
              execute(
                  connection,
                  "insert into rooms (host_user_id, selected_reward_preset_id, created_at) values ("
                      + hostId
                      + ", 999999, now())"),
          "23503",
          "fk_rooms_selected_reward_preset");
      assertSqlFails(
          () ->
              execute(
                  connection,
                  "insert into games (room_id, ordinal, question_text_snapshot, created_at) values"
                      + " (999999, 1, '질문', now())"),
          "23503",
          "fk_games_room");
      assertSqlFails(
          () ->
              execute(
                  connection,
                  "insert into choices (game_id, position, text_snapshot) values (999999, 1,"
                      + " '선택지')"),
          "23503",
          "fk_choices_game");
      long roomId = insertRoom(connection, hostId, rewardPresetId, null);
      long firstGameId = insertGame(connection, roomId, 1, "첫 질문");
      long firstChoiceId = insertChoice(connection, firstGameId, 1, "첫 선택지");
      long secondGameId = insertGame(connection, roomId, 2, "둘째 질문");
      long secondChoiceId = insertChoice(connection, secondGameId, 1, "둘째 선택지");

      execute(
          connection,
          "update games set correct_choice_id = " + firstChoiceId + " where id = " + firstGameId);
      assertThat(
              queryLong(
                  connection, "select correct_choice_id from games where id = " + firstGameId))
          .isEqualTo(firstChoiceId);
      execute(
          connection,
          "update games set correct_choice_id = " + secondChoiceId + " where id = " + firstGameId);
      assertThat(
              queryLong(
                  connection, "select correct_choice_id from games where id = " + firstGameId))
          .isEqualTo(secondChoiceId);

      assertSqlFails(
          () ->
              execute(
                  connection,
                  "update games set correct_choice_id = 999999 where id = " + firstGameId),
          "23503",
          "fk_games_correct_choice");
      assertSqlFails(
          () -> execute(connection, "delete from users where id = " + hostId),
          "23503",
          "fk_rooms_host_user");
      assertSqlFails(
          () -> execute(connection, "delete from reward_presets where id = " + rewardPresetId),
          "23503",
          "fk_rooms_selected_reward_preset");
      assertSqlFails(
          () -> execute(connection, "delete from rooms where id = " + roomId),
          "23503",
          "fk_games_room");
      assertSqlFails(
          () -> execute(connection, "delete from games where id = " + firstGameId),
          "23503",
          "fk_choices_game");
      assertSqlFails(
          () -> execute(connection, "delete from choices where id = " + secondChoiceId),
          "23503",
          "fk_games_correct_choice");
    }
  }

  @Test
  @DisplayName("V2 데이터는 N1 마이그레이션 뒤에도 보존한다")
  void upgradesV2DataToN1SchemaWithoutChangingExistingUserAndAuthRows() throws SQLException {
    Flyway versionTwo = migrateTo("2");
    assertThat(versionTwo.info().current().getVersion().getVersion()).isEqualTo("2");
    UUID uuid = UUID.randomUUID();
    UUID signupApplicationId = UUID.randomUUID();
    long userId;
    try (Connection connection = connectionInSchema()) {
      userId = insertUser(connection, uuid, "ACTIVE", null, null, null);
      insertIdentity(connection, userId, "KAKAO", "v2-subject");
      execute(
          connection,
          "insert into signup_applications "
              + "(id, provider, provider_subject, expires_at, created_at, updated_at) values ("
              + quote(signupApplicationId.toString())
              + "::uuid, 'KAKAO', 'v2-pending-subject', now(), now(), now())");
    }

    Flyway upgraded = migrate();

    assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("9000");
    try (Connection connection = connectionInSchema()) {
      assertThat(queryString(connection, "select uuid::text from users where id = " + userId))
          .isEqualTo(uuid.toString());
      assertThat(
              queryString(
                  connection,
                  "select provider_subject from oauth_identities where user_id = " + userId))
          .isEqualTo("v2-subject");
      assertThat(
              queryString(
                  connection,
                  "select provider_subject from signup_applications where id = "
                      + quote(signupApplicationId.toString())
                      + "::uuid"))
          .isEqualTo("v2-pending-subject");
      assertThat(queryLong(connection, "select count(*) from reward_presets")).isEqualTo(3);
    }
  }

  private Flyway migrate() {
    Flyway flyway = configuredFlyway();
    flyway.migrate();
    return flyway;
  }

  private Flyway migrateTo(String targetVersion) {
    Flyway flyway =
        Flyway.configure()
            .dataSource(url, username, password)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .cleanDisabled(true)
            .target(targetVersion)
            .load();
    flyway.migrate();
    return flyway;
  }

  private Flyway configuredFlyway() {
    Flyway flyway =
        Flyway.configure()
            .dataSource(url, username, password)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .cleanDisabled(true)
            // Only this spike schema opts into V9000; product migrations remain separate.
            .locations("classpath:db/spike/baseline", "classpath:db/spike/n1")
            .load();
    return flyway;
  }

  private void validateHibernateMappings(Class<?>... entities) {
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
    MetadataSources mappings = new MetadataSources(registry);
    for (Class<?> entity : entities) {
      mappings.addAnnotatedClass(entity);
    }
    try (SessionFactory ignored = mappings.buildMetadata().buildSessionFactory()) {
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
              "insert into users (uuid, display_name, profile_image_url, birth_year, status,"
                  + " withdrawn_at, created_at, updated_at) values ("
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

  private long insertRoom(
      Connection connection, long hostUserId, long rewardPresetId, String openedAt)
      throws SQLException {
    String openedAtValue = openedAt == null ? "null" : quote(openedAt) + "::timestamptz";
    try (Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "insert into rooms (host_user_id, selected_reward_preset_id, created_at, opened_at)"
                    + " values ("
                    + hostUserId
                    + ", "
                    + rewardPresetId
                    + ", now(), "
                    + openedAtValue
                    + ") returning id")) {
      resultSet.next();
      return resultSet.getLong(1);
    }
  }

  private long insertGame(Connection connection, long roomId, int ordinal, String questionText)
      throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "insert into games (room_id, ordinal, question_text_snapshot, created_at) values ("
                    + roomId
                    + ", "
                    + ordinal
                    + ", "
                    + quote(questionText)
                    + ", now()) returning id")) {
      resultSet.next();
      return resultSet.getLong(1);
    }
  }

  private long insertChoice(Connection connection, long gameId, int position, String text)
      throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "insert into choices (game_id, position, text_snapshot) values ("
                    + gameId
                    + ", "
                    + position
                    + ", "
                    + quote(text)
                    + ") returning id")) {
      resultSet.next();
      return resultSet.getLong(1);
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
      Set<String> tableNames = new HashSet<>();
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

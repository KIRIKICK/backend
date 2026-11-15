package org.kirikick.api.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.SignupApplication;
import org.kirikick.api.game.domain.Game;
import org.kirikick.api.game.domain.GameAnswer;
import org.kirikick.api.game.domain.GameParticipant;
import org.kirikick.api.room.domain.Room;
import org.kirikick.api.user.domain.User;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;

class GameSchemaMigrationTest {

  private final String url = PostgresTestDatabase.container().getJdbcUrl();
  private final String username = PostgresTestDatabase.container().getUsername();
  private final String password = PostgresTestDatabase.container().getPassword();
  private final String schema = "game_" + UUID.randomUUID().toString().replace("-", "");

  @AfterEach
  void dropOnlyThisTestsSchema() throws SQLException {
    try (Connection connection = DriverManager.getConnection(url, username, password)) {
      execute(connection, "drop schema if exists " + schema + " cascade");
    }
  }

  @Test
  void upgradesExistingUsersAndValidatesAllProductionMappings() throws SQLException {
    migrate("2");
    long userId;
    try (Connection connection = connection()) {
      userId = user(connection);
    }

    Flyway flyway = migrate("3");
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    validateMappings();

    try (Connection connection = connection()) {
      assertThat(queryLong(connection, "select count(*) from users where id = " + userId))
          .isEqualTo(1);
      long room = room(connection, userId);
      long game = game(connection, room, userId, "NAZAL");
      long participant = participant(connection, game, user(connection));
      long answer = insert(connection, optionSql(game, participant));
      assertThat(queryLong(connection, "select count(*) from game_answers where id = " + answer))
          .isEqualTo(1);
    }
  }

  @Test
  void enforcesParticipantAndAnswerUniqueness() throws SQLException {
    migrate("3");
    try (Connection connection = connection()) {
      Fixture f = fixture(connection);
      assertSqlState(connection, participantSql(f.game(), f.firstUser()), "23505");
      insert(connection, voteSql(f.game(), f.first(), f.second()));
      assertSqlState(connection, voteSql(f.game(), f.first(), f.second()), "23505");
      // A different game can have the same user; uniqueness is game-scoped.
      assertThat(
              queryLong(
                  connection,
                  "select count(*) from game_participants where user_id = " + f.firstUser()))
          .isEqualTo(2);
    }
  }

  @Test
  void rejectsCrossGameReferencesMissingParentsAndCascadingHistoryLoss() throws SQLException {
    migrate("3");
    try (Connection connection = connection()) {
      Fixture f = fixture(connection);
      assertSqlState(connection, voteSql(f.game(), f.first(), f.other()), "23503");
      assertSqlState(connection, voteSql(f.game(), f.other(), f.second()), "23503");
      assertSqlState(connection, participantSql(Long.MAX_VALUE, f.firstUser()), "23503");
      assertSqlState(connection, participantSql(f.game(), Long.MAX_VALUE), "23503");
      assertSqlState(connection, "delete from rooms where id = " + f.room(), "23503");
      assertSqlState(connection, "delete from games where id = " + f.game(), "23503");
      assertSqlState(connection, "delete from users where id = " + f.firstUser(), "23503");
      insert(connection, voteSql(f.game(), f.first(), f.second()));
      assertSqlState(connection, "delete from game_participants where id = " + f.first(), "23503");
      assertSqlState(connection, "delete from game_participants where id = " + f.second(), "23503");
    }
  }

  @Test
  void rejectsInvalidEnumsEmptyNamesAndAmbiguousOrSelfAnswers() throws SQLException {
    migrate("3");
    try (Connection connection = connection()) {
      Fixture f = fixture(connection);
      assertSqlState(connection, "update rooms set name = ' ' where id = " + f.room(), "23514");
      assertSqlState(
          connection, "update games set type = 'UNKNOWN' where id = " + f.game(), "23514");
      assertSqlState(
          connection, "update games set status = 'UNKNOWN' where id = " + f.game(), "23514");
      assertSqlState(connection, answerSql(f.game(), f.first(), "null", "null"), "23514");
      assertSqlState(connection, answerSql(f.game(), f.first(), "'5'", "null"), "23514");
      assertSqlState(
          connection, answerSql(f.game(), f.first(), "'1'", Long.toString(f.second())), "23514");
      assertSqlState(connection, voteSql(f.game(), f.first(), f.first()), "23514");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void concurrentWritersCannotDuplicateParticipationOrAnswer(boolean answer) throws Exception {
    migrate("3");
    String firstSql;
    String secondSql;
    try (Connection connection = connection()) {
      Fixture f = fixture(connection);
      long newUser = user(connection);
      firstSql =
          answer ? voteSql(f.game(), f.first(), f.second()) : participantSql(f.game(), newUser);
      secondSql =
          answer ? voteSql(f.game(), f.first(), f.second()) : participantSql(f.game(), newUser);
    }

    CyclicBarrier barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(writeAfterBarrier(firstSql, barrier));
      var second = executor.submit(writeAfterBarrier(secondSql, barrier));
      assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("saved", "23505");
    }
  }

  private Callable<String> writeAfterBarrier(String sql, CyclicBarrier barrier) {
    return () -> {
      try (Connection connection = connection()) {
        execute(connection, "set statement_timeout = '10s'");
        barrier.await(10, TimeUnit.SECONDS);
        try {
          insert(connection, sql);
          return "saved";
        } catch (SQLException exception) {
          return exception.getSQLState();
        }
      }
    };
  }

  private Flyway migrate(String version) {
    Flyway flyway =
        Flyway.configure()
            .dataSource(url, username, password)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .cleanDisabled(true)
            .target(version)
            .load();
    flyway.migrate();
    return flyway;
  }

  private void validateMappings() {
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
            .addAnnotatedClass(SignupApplication.class)
            .addAnnotatedClass(Room.class)
            .addAnnotatedClass(Game.class)
            .addAnnotatedClass(GameParticipant.class)
            .addAnnotatedClass(GameAnswer.class)
            .buildMetadata()
            .buildSessionFactory()) {
      // Creating the SessionFactory validates all production mappings against Flyway V1-V3.
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
  }

  private Connection connection() throws SQLException {
    Connection connection = DriverManager.getConnection(url, username, password);
    connection.setSchema(schema);
    return connection;
  }

  private Fixture fixture(Connection c) throws SQLException {
    long firstUser = user(c);
    long secondUser = user(c);
    long room = room(c, firstUser);
    long game = game(c, room, firstUser, "VOTE");
    long otherGame = game(c, room, firstUser, "VOTE");
    return new Fixture(
        room,
        game,
        firstUser,
        participant(c, game, firstUser),
        participant(c, game, secondUser),
        participant(c, otherGame, firstUser));
  }

  private long user(Connection c) throws SQLException {
    return insert(
        c,
        "insert into users (uuid, display_name, status, created_at, updated_at) values ('"
            + UUID.randomUUID()
            + "', '친구', 'ACTIVE', now(), now()) returning id");
  }

  private long room(Connection c, long owner) throws SQLException {
    return insert(
        c,
        "insert into rooms (uuid, owner_id, name, created_at, updated_at) values ('"
            + UUID.randomUUID()
            + "', "
            + owner
            + ", '친구방', now(), now()) returning id");
  }

  private long game(Connection c, long room, long creator, String type) throws SQLException {
    return insert(
        c,
        "insert into games (uuid, room_id, creator_id, type, status, expires_at, created_at, updated_at) values ('"
            + UUID.randomUUID()
            + "', "
            + room
            + ", "
            + creator
            + ", '"
            + type
            + "', 'IN_PROGRESS', now() + interval '2 hours', now(), now()) returning id");
  }

  private long participant(Connection c, long game, long user) throws SQLException {
    return insert(c, participantSql(game, user));
  }

  private String participantSql(long game, long user) {
    return "insert into game_participants (uuid, game_id, user_id, created_at, updated_at) values ('"
        + UUID.randomUUID()
        + "', "
        + game
        + ", "
        + user
        + ", now(), now()) returning id";
  }

  private String optionSql(long game, long participant) {
    return answerSql(game, participant, "'2'", "null");
  }

  private String voteSql(long game, long participant, long target) {
    return answerSql(game, participant, "null", Long.toString(target));
  }

  private String answerSql(long game, long participant, String option, String target) {
    return "insert into game_answers (uuid, game_id, participant_id, selected_option_key, target_participant_id, accepted_at, created_at) values ('"
        + UUID.randomUUID()
        + "', "
        + game
        + ", "
        + participant
        + ", "
        + option
        + ", "
        + target
        + ", now(), now()) returning id";
  }

  private void assertSqlState(Connection connection, String sql, String state) {
    assertThatThrownBy(() -> execute(connection, sql))
        .isInstanceOfSatisfying(
            SQLException.class, exception -> assertThat(exception.getSQLState()).isEqualTo(state));
  }

  private void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private long insert(Connection connection, String sql) throws SQLException {
    return queryLong(connection, sql);
  }

  private long queryLong(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    }
  }

  private record Fixture(
      long room, long game, long firstUser, long first, long second, long other) {}
}

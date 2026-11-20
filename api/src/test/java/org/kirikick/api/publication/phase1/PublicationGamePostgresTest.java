package org.kirikick.api.publication.phase1;

import static java.time.ZoneOffset.UTC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.game.domain.Choice;
import org.kirikick.api.game.domain.Game;
import org.kirikick.api.game.domain.exception.GameErrorCode;
import org.kirikick.api.game.repository.ChoiceRepository;
import org.kirikick.api.game.repository.GameRepository;
import org.kirikick.api.question.domain.QuestionPreset;
import org.kirikick.api.question.domain.QuestionPresetChoice;
import org.kirikick.api.reward.domain.RewardPreset;
import org.kirikick.api.room.domain.Room;
import org.kirikick.api.room.repository.RoomRepository;
import org.kirikick.api.user.domain.User;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PublicationGamePostgresTest {
  private static final PublicationPhase1Postgres POSTGRES = new PublicationPhase1Postgres();

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void closePostgres() {
    POSTGRES.close();
  }

  @Test
  @DisplayName("P1-03: V3/V4 FK 9개의 비지연 NO ACTION과 V1/V2 RESTRICT를 확인한다")
  void hasExactlyNineV3V4ForeignKeysAndPreservesV1V2Restrict() throws SQLException {
    POSTGRES.inMigratedSchema(
        "P1-03-catalog",
        (context, dataSource) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          List<Map<String, Object>> fks =
              jdbc.sql(
                      """
                      select con.conname, con.confdeltype::text, con.confmatchtype::text,
                             con.condeferrable, child.relname::text as child_table,
                             parent.relname::text as parent_table, ns.nspname::text as parent_schema,
                             pg_get_constraintdef(con.oid) as def
                      from pg_constraint con
                      join pg_class child on child.oid = con.conrelid
                      join pg_class parent on parent.oid = con.confrelid
                      join pg_namespace ns on ns.oid = parent.relnamespace
                      where con.contype = 'f' and con.connamespace = current_schema()::regnamespace
                      order by con.conname
                      """)
                  .query()
                  .listOfRows();
          assertThat(fks).hasSize(11);
          var old =
              fks.stream()
                  .filter(row ->
                      List.of("oauth_identities", "signup_applications")
                          .contains(row.get("child_table")))
                  .toList();
          assertThat(old)
              .extracting(row -> row.get("conname"))
              .containsExactly("fk_oauth_identities_user", "fk_signup_applications_completed_user");
          assertThat(old).allSatisfy(row -> assertThat(row).containsEntry("confdeltype", "r"));

          var v3v4 = fks.stream().filter(row -> !old.contains(row)).toList();
          assertThat(v3v4)
              .extracting(row -> row.get("conname"))
              .containsExactly(
                  "fk_choices_game",
                  "fk_games_correct_choice",
                  "fk_games_question_preset",
                  "fk_games_room",
                  "fk_publication_idempotency_game",
                  "fk_publication_idempotency_user",
                  "fk_question_preset_choices_question",
                  "fk_rooms_host_user",
                  "fk_rooms_selected_reward_preset");
          var definitions =
              List.of(
                  "FOREIGN KEY (game_id) REFERENCES games(id)",
                  "FOREIGN KEY (id, correct_choice_id) REFERENCES choices(game_id, id)",
                  "FOREIGN KEY (question_preset_id) REFERENCES question_presets(id)",
                  "FOREIGN KEY (room_id) REFERENCES rooms(id)",
                  "FOREIGN KEY (game_id) REFERENCES games(id)",
                  "FOREIGN KEY (user_id) REFERENCES users(id)",
                  "FOREIGN KEY (question_preset_id) REFERENCES question_presets(id)",
                  "FOREIGN KEY (host_user_id) REFERENCES users(id)",
                  "FOREIGN KEY (selected_reward_preset_id) REFERENCES reward_presets(id)");
          assertThat(v3v4).extracting(row -> row.get("def")).containsExactlyElementsOf(definitions);
          assertThat(v3v4)
              .allSatisfy(row ->
                  assertThat(row)
                      .containsEntry("confdeltype", "a")
                      .containsEntry("confmatchtype", "s")
                      .containsEntry("condeferrable", false)
                      .containsEntry("parent_schema", dataSource.getPoolName()));
          assertThat(
                  jdbc.sql(
                          """
                          select column_name from information_schema.columns
                          where table_schema = current_schema() and table_name = 'choices'
                          """)
                      .query(String.class)
                      .list())
              .containsExactlyInAnyOrder("id", "game_id", "position", "text_snapshot");
          assertThat(
                  jdbc.sql(
                          """
                          select pg_get_constraintdef(oid) from pg_constraint
                          where conrelid = 'choices'::regclass and conname='uk_choices_game_id'
                          """)
                      .query(String.class)
                      .single())
              .isEqualTo("UNIQUE (game_id, id)");
          System.out.printf("P1-03 catalog fks=%s old=%s%n", v3v4, old);
        });
  }

  @Test
  @DisplayName("P1-03: NULL 정답 Game과 Choice 네 개를 저장한 뒤 같은 Game의 정답을 연결한다")
  void persistsNullAnswerThenFourCopiesAndSameGameAnswerUpdate() throws SQLException {
    POSTGRES.inMigratedSchema(
        "P1-03-hibernate",
        (context, dataSource) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Fixture fixture = fixture(jdbc, "hibernate");
          long copyQuestion = insertQuestion(jdbc, "copy-question");
          List<Long> copySources =
              List.of("first", "second", "third", "fourth").stream()
                  .map(text ->
                      jdbc.sql(
                              """
                              insert into question_preset_choices (question_preset_id, choice_text)
                              values (:question, :text) returning id
                              """)
                          .param("question", copyQuestion)
                          .param("text", text)
                          .query(Long.class)
                          .single())
                  .toList();
          TransactionTemplate transaction = transaction(context);
          transaction.executeWithoutResult(status -> {
            EntityManager entityManager = entityManager(context);
            Room room = entityManager.getReference(Room.class, fixture.roomId());
            QuestionPreset question =
                entityManager.getReference(QuestionPreset.class, copyQuestion);
            Game game = context.getBean(GameRepository.class).save(Game.prepare(room, question));
            entityManager.flush();
            assertThat(
                    jdbc.sql("select correct_choice_id from games where id = :id")
                        .param("id", game.getId())
                        .query(Long.class)
                        .optional())
                .isEmpty();

            List<QuestionPresetChoice> shuffled =
                List.of(
                        copySources.get(2),
                        copySources.get(0),
                        copySources.get(3),
                        copySources.get(1))
                    .stream()
                    .map(id -> entityManager.getReference(QuestionPresetChoice.class, id))
                    .toList();
            List<Choice> copies =
                shuffled.stream()
                    .map(source ->
                        Choice.copyOf(
                            game,
                            source,
                            copySources.stream().sorted().toList().indexOf(source.getId())))
                    .toList();
            copies = context.getBean(ChoiceRepository.class).saveAll(copies);
            entityManager.flush();
            game.initializeCorrectChoice(copies.get(2));
            entityManager.flush();
            entityManager.clear();

            Game reloaded =
                context.getBean(GameRepository.class).findById(game.getId()).orElseThrow();
            List<Choice> stored =
                context
                    .getBean(ChoiceRepository.class)
                    .findAllByGame_IdOrderByPositionAsc(game.getId());
            assertThat(reloaded.getQuestionTextSnapshot()).isEqualTo("copy-question");
            assertThat(reloaded.getCorrectChoiceId()).isEqualTo(copies.get(2).getId());
            assertThat(stored).extracting(Choice::getPosition).containsExactly(0, 1, 2, 3);
            assertThat(stored.getFirst().getId()).isGreaterThan(stored.get(2).getId());
            assertThat(stored)
                .extracting(Choice::getTextSnapshot)
                .containsExactly("first", "second", "third", "fourth");
            assertThat(stored.get(3).getId()).isEqualTo(reloaded.getCorrectChoiceId());
            reloaded.verifyComplete(stored);
            System.out.printf(
                "P1-03 Hibernate SQL sequence: INSERT games(correct_choice_id=NULL), INSERT choices x4, UPDATE games(correct_choice_id); game=%d choices=%s%n",
                game.getId(), stored.stream().map(Choice::getId).toList());
          });
        });
  }

  @Test
  @DisplayName("P1-03: 다른 Game의 정답 연결은 지정한 복합 FK가 거절한다")
  void rejectsCrossGameAnswerWithNamedCompositeForeignKey() throws SQLException {
    POSTGRES.inMigratedSchema(
        "P1-03-cross-game",
        (context, dataSource) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Fixture fixture = fixture(jdbc, "cross");
          long otherQuestion = insertQuestion(jdbc, "other");
          long otherGame =
              insertGame(
                  jdbc,
                  fixture.roomId(),
                  otherQuestion,
                  "other",
                  Instant.parse("2026-11-19T00:00:00Z"));
          long otherChoice =
              jdbc.sql(
                      "insert into choices (game_id, position, text_snapshot) values (:game, 0, 'other') returning id")
                  .param("game", otherGame)
                  .query(Long.class)
                  .single();
          SQLException failure =
              sqlFailure(() ->
                  jdbc.sql("update games set correct_choice_id = :choice where id = :game")
                      .param("choice", otherChoice)
                      .param("game", fixture.gameId())
                      .update());
          assertThat(failure.getSQLState()).isEqualTo("23503");
          assertThat(failure.getMessage()).contains("fk_games_correct_choice");
          System.out.printf(
              "P1-03 cross-game SQLSTATE=%s constraint=fk_games_correct_choice%n",
              failure.getSQLState());
        });
  }

  @Test
  @DisplayName("P1-03: 만료 참조도 삭제를 막고 합성 파기에서는 비대상 행을 보존한다")
  void blocksReferencedDeletesAndPreservesNonTargetRows() throws SQLException {
    POSTGRES.inMigratedSchema(
        "P1-03-delete",
        (context, dataSource) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Fixture target = fixture(jdbc, "target");
          Fixture control = fixture(jdbc, "control");
          jdbc.sql(
                  "update publication_idempotency set record_expires_at = now() - interval '1 day' where game_id = :game")
              .param("game", target.gameId())
              .update();
          jdbc.sql("update games set correct_choice_id = null where id = :game")
              .param("game", target.gameId())
              .update();
          jdbc.sql("delete from choices where game_id = :game")
              .param("game", target.gameId())
              .update();
          SQLException gameDelete =
              sqlFailure(() ->
                  jdbc.sql("delete from games where id = :id")
                      .param("id", target.gameId())
                      .update());
          SQLException roomDelete =
              sqlFailure(() ->
                  jdbc.sql("delete from rooms where id = :id")
                      .param("id", target.roomId())
                      .update());
          assertThat(gameDelete.getSQLState()).isEqualTo("23503");
          assertThat(gameDelete.getMessage()).contains("fk_publication_idempotency_game");
          assertThat(roomDelete.getSQLState()).isEqualTo("23503");
          assertThat(roomDelete.getMessage()).contains("fk_games_room");

          transaction(context)
              .executeWithoutResult(status -> {
                jdbc.sql("delete from publication_idempotency where game_id = :game")
                    .param("game", target.gameId())
                    .update();
                jdbc.sql("delete from games where id = :game")
                    .param("game", target.gameId())
                    .update();
                jdbc.sql("delete from rooms where id = :room")
                    .param("room", target.roomId())
                    .update();
              });
          assertThat(
                  jdbc.sql("select count(*) from rooms where id = :id")
                      .param("id", target.roomId())
                      .query(Long.class)
                      .single())
              .isZero();
          assertThat(
                  jdbc.sql("select count(*) from rooms where id = :id")
                      .param("id", control.roomId())
                      .query(Long.class)
                      .single())
              .isEqualTo(1);
          System.out.printf(
              "P1-03 delete SQLSTATE game=%s room=%s; target removed, control=%d preserved%n",
              gameDelete.getSQLState(), roomDelete.getSQLState(), control.roomId());
        });
  }

  @Test
  @DisplayName("P1-06: 생성 시각 동률은 Game ID로 정렬하고 중복 position을 거절한다")
  void ordersTiedGamesByIdAndRejectsDuplicateChoicePosition() throws SQLException {
    POSTGRES.inMigratedSchema(
        "P1-06-order",
        (context, dataSource) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Fixture fixture = fixture(jdbc, "order");
          Instant tie = Instant.parse("2026-11-19T00:00:00Z");
          long q2 = insertQuestion(jdbc, "two");
          long q3 = insertQuestion(jdbc, "three");
          long first = insertGame(jdbc, fixture.roomId(), q2, "two", tie);
          long second = insertGame(jdbc, fixture.roomId(), q3, "three", tie);
          long earlier =
              insertGame(
                  jdbc,
                  fixture.roomId(),
                  insertQuestion(jdbc, "earlier"),
                  "earlier",
                  tie.minusSeconds(1));
          assertThat(
                  context
                      .getBean(GameRepository.class)
                      .findAllByRoom_IdOrderByCreatedAtAscIdAsc(fixture.roomId()))
              .extracting(Game::getId)
              .containsExactly(earlier, fixture.gameId(), first, second);
          var duplicateQuestion =
              sqlFailure(() -> insertGame(jdbc, fixture.roomId(), q2, "duplicate", tie));
          assertThat(duplicateQuestion.getSQLState()).isEqualTo("23505");
          assertThat(duplicateQuestion.getMessage()).contains("uk_games_room_question");
          var nullPosition =
              sqlFailure(() ->
                  jdbc.sql(
                          "insert into choices (game_id, position, text_snapshot) values (?, null, 'invalid')")
                      .param(first)
                      .update());
          assertThat(nullPosition.getSQLState()).isEqualTo("23502");
          assertThat(nullPosition.getMessage()).contains("\"position\"");
          SQLException duplicate =
              sqlFailure(() ->
                  jdbc.sql(
                          "insert into choices (game_id, position, text_snapshot) values (:game, 0, 'duplicate')")
                      .param("game", fixture.gameId())
                      .update());
          assertThat(duplicate.getSQLState()).isEqualTo("23505");
          assertThat(duplicate.getMessage()).contains("uk_choices_game_position");
          System.out.printf(
              "P1-06 tied games=%d,%d duplicate-position SQLSTATE=%s constraint=uk_choices_game_position%n",
              first, second, duplicate.getSQLState());
        });
  }

  @Test
  @DisplayName("P1-03: 저장한 선택지가 3개면 Room·Game·Choice·선점을 롤백한다")
  void rollsBackThreeChoiceAssembly() throws SQLException {
    POSTGRES.inMigratedSchema(
        "P1-06-three",
        (context, dataSource) ->
            assertIncompleteAssemblyRollsBack(
                context.getBean(JdbcClient.class), context, 3, "three"));
  }

  @Test
  @DisplayName("P1-03: 저장한 선택지가 5개면 Room·Game·Choice·선점을 롤백한다")
  void rollsBackFiveChoiceAssembly() throws SQLException {
    POSTGRES.inMigratedSchema(
        "P1-06-five",
        (context, dataSource) ->
            assertIncompleteAssemblyRollsBack(
                context.getBean(JdbcClient.class), context, 5, "five"));
  }

  @Test
  @DisplayName("P1-03: 선택지가 4개여도 정답이 없으면 전체 롤백한다")
  void rollsBackFourChoicesWithoutAnAnswer() throws SQLException {
    POSTGRES.inMigratedSchema(
        "P1-03-null-answer",
        (context, dataSource) ->
            assertIncompleteAssemblyRollsBack(
                context.getBean(JdbcClient.class), context, 4, "null-answer"));
  }

  private static void assertIncompleteAssemblyRollsBack(
      JdbcClient jdbc, AssertableApplicationContext context, int choiceCount, String key) {
    Fixture fixture = fixture(jdbc, key);
    jdbc.sql("update rooms set status = 'CLOSED' where id = :room")
        .param("room", fixture.roomId())
        .update();
    long question = insertQuestion(jdbc, "incomplete-" + key);
    List<Long> sources =
        IntStream.range(0, choiceCount)
            .mapToObj(position ->
                jdbc.sql(
                        """
                        insert into question_preset_choices (question_preset_id, choice_text)
                        values (:question, :text) returning id
                        """)
                    .param("question", question)
                    .param("text", "choice-" + position)
                    .query(Long.class)
                    .single())
            .toList();
    long[] ids = new long[2];
    transaction(context)
        .executeWithoutResult(status -> {
          EntityManager entityManager = entityManager(context);
          Room room =
              context
                  .getBean(RoomRepository.class)
                  .save(
                      Room.prepare(
                          entityManager.getReference(User.class, fixture.userId()),
                          entityManager.getReference(
                              RewardPreset.class,
                              jdbc.sql(
                                      "select selected_reward_preset_id from rooms where id = :room")
                                  .param("room", fixture.roomId())
                                  .query(Long.class)
                                  .single())));
          Game game =
              context
                  .getBean(GameRepository.class)
                  .save(
                      Game.prepare(
                          room, entityManager.getReference(QuestionPreset.class, question)));
          List<Choice> choices =
              context
                  .getBean(ChoiceRepository.class)
                  .saveAll(
                      sources.stream()
                          .limit(4)
                          .map(source ->
                              Choice.copyOf(
                                  game,
                                  entityManager.getReference(QuestionPresetChoice.class, source),
                                  sources.indexOf(source)))
                          .toList());
          entityManager.flush();
          if (choiceCount == 5) {
            assertThat(
                    jdbc.sql(
                            "insert into choices (game_id, position, text_snapshot) values (?, 4, 'invalid extra')")
                        .param(game.getId())
                        .update())
                .isOne();
          }
          if (choiceCount != 4) {
            game.initializeCorrectChoice(choices.getFirst());
            entityManager.flush();
          }
          ids[0] = room.getId();
          ids[1] = game.getId();
          assertThat(game.getCorrectChoiceId() == null).isEqualTo(choiceCount == 4);
          var persistedChoices =
              context
                  .getBean(ChoiceRepository.class)
                  .findAllByGame_IdOrderByPositionAsc(game.getId());
          assertThat(persistedChoices).hasSize(choiceCount);
          jdbc.sql(
                  """
                  insert into publication_idempotency (user_id, request_key, normalized_input, game_id, record_expires_at)
                  values (:user, :key, '{}'::jsonb, :game, now() + interval '1 day')
                  """)
              .param("user", fixture.userId())
              .param("key", "rollback-" + key)
              .param("game", game.getId())
              .update();
          assertThatThrownBy(() -> game.verifyComplete(persistedChoices))
              .isInstanceOfSatisfying(
                  DomainException.class,
                  e -> assertThat(e.getErrorCode()).isEqualTo(GameErrorCode.INCOMPLETE_GAME));
          status.setRollbackOnly();
        });
    assertThat(
            jdbc.sql("select count(*) from rooms where id = :room")
                .param("room", ids[0])
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql("select count(*) from games where id = :game")
                .param("game", ids[1])
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql("select count(*) from choices where game_id = :game")
                .param("game", ids[1])
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql(
                    "select count(*) from publication_idempotency where user_id = :user and request_key = :key")
                .param("user", fixture.userId())
                .param("key", "rollback-" + key)
                .query(Long.class)
                .single())
        .isZero();
    System.out.printf(
        "P1-03 incomplete choices=%d persisted-null-answer=%s rollback room=%d game=%d claim=rollback-%s%n",
        choiceCount, choiceCount == 4, ids[0], ids[1], key);
  }

  private static Fixture fixture(JdbcClient jdbc, String suffix) {
    long user =
        jdbc.sql(
                """
                insert into users (uuid, display_name, status, created_at, updated_at)
                values (:uuid, :name, 'ACTIVE', now(), now()) returning id
                """)
            .param("uuid", UUID.randomUUID())
            .param("name", "host-" + suffix)
            .query(Long.class)
            .single();
    long reward =
        jdbc.sql(
                "insert into reward_presets (title, description) values (:title, 'description') returning id")
            .param("title", "reward-" + suffix)
            .query(Long.class)
            .single();
    long room =
        jdbc.sql(
                """
                insert into rooms (host_user_id, selected_reward_preset_id, status, result_state, created_at)
                values (:user, :reward, 'OPEN', 'NOT_READY', now()) returning id
                """)
            .param("user", user)
            .param("reward", reward)
            .query(Long.class)
            .single();
    long question = insertQuestion(jdbc, "question-" + suffix);
    long game =
        insertGame(
            jdbc, room, question, "snapshot-" + suffix, Instant.parse("2026-11-19T00:00:00Z"));
    List<Long> sources =
        List.of("first", "second", "third", "fourth").stream()
            .map(text ->
                jdbc.sql(
                        """
                        insert into question_preset_choices (question_preset_id, choice_text) values (:question, :text) returning id
                        """)
                    .param("question", question)
                    .param("text", text)
                    .query(Long.class)
                    .single())
            .toList();
    long choice =
        jdbc.sql(
                "insert into choices (game_id, position, text_snapshot) values (:game, 0, 'answer') returning id")
            .param("game", game)
            .query(Long.class)
            .single();
    jdbc.sql("update games set correct_choice_id = :choice where id = :game")
        .param("choice", choice)
        .param("game", game)
        .update();
    jdbc.sql(
            """
            insert into publication_idempotency (user_id, request_key, normalized_input, game_id, record_expires_at)
            values (:user, :key, '{}'::jsonb, :game, now() + interval '1 day')
            """)
        .param("user", user)
        .param("key", "key-" + suffix)
        .param("game", game)
        .update();
    return new Fixture(user, room, question, game, sources);
  }

  private static long insertQuestion(JdbcClient jdbc, String text) {
    return jdbc.sql("insert into question_presets (question_text) values (:text) returning id")
        .param("text", text)
        .query(Long.class)
        .single();
  }

  private static long insertGame(
      JdbcClient jdbc, long room, long question, String snapshot, Instant createdAt) {
    return jdbc.sql(
            """
            insert into games (room_id, question_preset_id, question_text_snapshot, created_at)
            values (:room, :question, :snapshot, :createdAt) returning id
            """)
        .param("room", room)
        .param("question", question)
        .param("snapshot", snapshot)
        .param("createdAt", createdAt.atOffset(UTC))
        .query(Long.class)
        .single();
  }

  private static TransactionTemplate transaction(AssertableApplicationContext context) {
    return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
  }

  private static EntityManager entityManager(AssertableApplicationContext context) {
    return SharedEntityManagerCreator.createSharedEntityManager(
        context.getBean(EntityManagerFactory.class));
  }

  private static SQLException sqlFailure(SqlOperation operation) {
    Throwable thrown = catchThrowable(operation);
    Throwable cause = thrown;
    while (cause != null && !(cause instanceof SQLException)) {
      cause = cause.getCause();
    }
    assertThat(cause).isInstanceOf(SQLException.class);
    return (SQLException) cause;
  }

  private static Throwable catchThrowable(SqlOperation operation) {
    try {
      operation.run();
    } catch (Throwable throwable) {
      return throwable;
    }
    throw new AssertionError("expected SQL operation to fail");
  }

  @FunctionalInterface
  private interface SqlOperation {
    void run();
  }

  private record Fixture(
      long userId, long roomId, long questionId, long gameId, List<Long> sources) {}
}

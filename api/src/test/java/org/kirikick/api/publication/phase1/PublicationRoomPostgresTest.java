package org.kirikick.api.publication.phase1;

import static java.time.ZoneOffset.UTC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.util.ReflectionTestUtils.setField;
import static org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.common.infra.DbTimeSource;
import org.kirikick.api.config.PublicationProperties;
import org.kirikick.api.game.domain.Choice;
import org.kirikick.api.game.domain.Game;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.repository.PublicationIdempotencyRepository;
import org.kirikick.api.question.domain.QuestionPreset;
import org.kirikick.api.question.domain.QuestionPresetChoice;
import org.kirikick.api.reward.domain.RewardPreset;
import org.kirikick.api.room.domain.Room;
import org.kirikick.api.room.domain.exception.RoomErrorCode;
import org.kirikick.api.room.repository.RoomRepository;
import org.kirikick.api.user.domain.User;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PublicationRoomPostgresTest {

  private static final PublicationPhase1Postgres POSTGRES = new PublicationPhase1Postgres();
  private static final Instant AS_OF = Instant.parse("2026-11-19T18:30:00.123456Z");
  private static final OffsetDateTime OLD_OPEN = AS_OF.minus(Duration.ofHours(25)).atOffset(UTC);
  private static final OffsetDateTime OLD_CLOSE = OLD_OPEN.plusHours(24);

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.close();
  }

  @Test
  @DisplayName("P1-02: Room 상태 제약·기본값과 문자열 enum 왕복을 검증한다")
  void p102RoomConstraintsAndEnumMappings() throws SQLException {
    POSTGRES.inMigratedSchema(
        "P1-02 states and times",
        (context, pool) -> {
          var jdbc = context.getBean(JdbcClient.class);
          var catalog =
              jdbc.sql(
                      """
                      select column_name, is_nullable, column_default from information_schema.columns
                      where table_schema = current_schema() and table_name = 'rooms'
                        and column_name in ('status','result_state')
                      order by column_name
                      """)
                  .query()
                  .listOfRows();
          assertThat(catalog).hasSize(2);
          assertThat(catalog.get(0))
              .containsEntry("column_name", "result_state")
              .containsEntry("is_nullable", "NO");
          assertThat(catalog.get(0).get("column_default").toString()).contains("'NOT_READY'");
          assertThat(catalog.get(1))
              .containsEntry("column_name", "status")
              .containsEntry("is_nullable", "NO")
              .containsEntry("column_default", null);
          System.out.printf("P1-02 state catalog %s%n", catalog);

          Seed seed = seed(jdbc);
          assertViolation(
              () -> insertRoom(jdbc, seed, null, "NOT_READY", null, null), "23502", "status");
          assertViolation(
              () -> insertRoom(jdbc, seed, "INVALID", "NOT_READY", null, null),
              "23514",
              "ck_rooms_status");
          assertViolation(
              () -> insertRoom(jdbc, seed, "CLOSED", null, null, null), "23502", "result_state");
          assertViolation(
              () -> insertRoom(jdbc, seed, "CLOSED", "INVALID", null, null),
              "23514",
              "ck_rooms_result_state");
          assertViolation(
              () ->
                  jdbc.sql(
                          """
                          insert into rooms (host_user_id, selected_reward_preset_id, created_at)
                          values (?, ?, now())
                          """)
                      .params(seed.user(), seed.reward())
                      .update(),
              "23502",
              "status");

          long defaulted =
              jdbc.sql(
                      """
                      insert into rooms (host_user_id, selected_reward_preset_id, status, created_at)
                      values (?, ?, 'CLOSED', now()) returning id
                      """)
                  .params(seed.user(), seed.reward())
                  .query(Long.class)
                  .single();
          assertThat(
                  jdbc.sql("select result_state from rooms where id = ?")
                      .param(defaulted)
                      .query(String.class)
                      .single())
              .isEqualTo("NOT_READY");
          assertViolation(
              () -> insertRoom(jdbc, seed, "CLOSED", "NOT_READY", OLD_OPEN, null),
              "23514",
              "ck_rooms_publication_times");
          assertViolation(
              () -> insertRoom(jdbc, seed, "CLOSED", "NOT_READY", null, OLD_CLOSE),
              "23514",
              "ck_rooms_publication_times");
          assertViolation(
              () -> insertRoom(jdbc, seed, "CLOSED", "NOT_READY", OLD_OPEN, OLD_OPEN),
              "23514",
              "ck_rooms_closes_after_open");
          assertViolation(
              () -> insertRoom(jdbc, seed, "CLOSED", "NOT_READY", OLD_CLOSE, OLD_OPEN),
              "23514",
              "ck_rooms_closes_after_open");

          var em = entityManager(context);
          var tx = transaction(context);
          long id =
              tx.execute(status -> {
                Room room = prepare(em, seed);
                room.publish(OLD_OPEN.toInstant(), OLD_CLOSE.toInstant());
                em.flush();
                em.clear();
                Room open = em.find(Room.class, room.getId());
                assertThat(open.getStatus()).isEqualTo(Room.Status.OPEN);
                assertThat(open.getResultState()).isEqualTo(Room.ResultState.NOT_READY);
                open.closeIntake();
                em.flush();
                em.clear();
                assertThat(em.find(Room.class, room.getId()).getStatus())
                    .isEqualTo(Room.Status.CLOSED);
                return room.getId();
              });
          assertThat(
                  jdbc.sql("select status || '/' || result_state from rooms where id = ?")
                      .param(id)
                      .query(String.class)
                      .single())
              .isEqualTo("CLOSED/NOT_READY");
          tx.executeWithoutResult(status -> {
            // FINAL writer is out of scope; exercise only the enum mapping here.
            setField(em.find(Room.class, id), "resultState", Room.ResultState.FINAL);
            em.flush();
            em.clear();
            assertThat(em.find(Room.class, id).getResultState()).isEqualTo(Room.ResultState.FINAL);
          });
          assertThat(
                  jdbc.sql("select result_state from rooms where id = ?")
                      .param(id)
                      .query(String.class)
                      .single())
              .isEqualTo("FINAL");
          System.out.printf(
              "P1-02 Room enum flush/clear room=%d CLOSED/FINAL; default room=%d NOT_READY%n",
              id, defaulted);
        });
  }

  @Test
  @DisplayName("P1-04: 불완전하거나 만료된 OPEN도 부분 유니크를 점유한다")
  void p104IncompleteAndExpiredOpenStillOccupyPartialUnique() throws SQLException {
    POSTGRES.inMigratedSchema(
        "P1-04 partial unique",
        (context, pool) -> {
          var jdbc = context.getBean(JdbcClient.class);
          String index =
              jdbc.sql(
                      """
                      select indexdef from pg_indexes
                      where schemaname=current_schema() and indexname='uk_rooms_open_per_host'
                      """)
                  .query(String.class)
                  .single();
          assertThat(index).contains("CREATE UNIQUE INDEX", "(host_user_id) WHERE", "'OPEN'");
          assertThat(
                  jdbc.sql(
                          """
                          select pg_get_constraintdef(oid) from pg_constraint
                          where conrelid='games'::regclass and conname='uk_games_room_question'
                          """)
                      .query(String.class)
                      .single())
              .isEqualTo("UNIQUE (room_id, question_preset_id)");
          System.out.printf("P1-04 catalog %s%n", index);
          Seed incomplete = seed(jdbc);
          long old = insertRoom(jdbc, incomplete, "OPEN", "NOT_READY", null, null);
          var row = roomRow(jdbc, old);
          assertThat(row).containsEntry("opened_at", null).containsEntry("closes_at", null);
          assertViolation(
              () -> insertRoom(jdbc, incomplete, "OPEN", "NOT_READY", OLD_OPEN, OLD_CLOSE),
              "23505",
              "uk_rooms_open_per_host");
          assertThat(roomRow(jdbc, old)).isEqualTo(row);
          Seed expired = seed(jdbc);
          long other = insertRoom(jdbc, expired, "OPEN", "NOT_READY", OLD_OPEN, OLD_CLOSE);
          assertViolation(
              () -> insertRoom(jdbc, expired, "OPEN", "NOT_READY", OLD_OPEN, OLD_CLOSE),
              "23505",
              "uk_rooms_open_per_host");
          Seed available = seed(jdbc);
          insertRoom(jdbc, available, "CLOSED", "NOT_READY", OLD_OPEN, OLD_CLOSE);
          long inserted = insertRoom(jdbc, available, "OPEN", "NOT_READY", OLD_OPEN, OLD_CLOSE);
          System.out.printf(
              "P1-04 direct OPEN INSERT incomplete=%d expired=%d control=%d no existing row changed%n",
              old, other, inserted);
        });
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("P1-02: 지연 전이 커밋과 롤백 뒤 각각 미정산 방을 재발견한다")
  void p102DelayedTransitionAndRollbackRediscoverExpiredRoom(boolean commit) throws SQLException {
    POSTGRES.inMigratedSchema(
        "P1-02 delayed transition commit=" + commit,
        (context, pool) -> {
          var jdbc = context.getBean(JdbcClient.class);
          var repository = context.getBean(RoomRepository.class);
          var em = entityManager(context);
          Seed seed = seed(jdbc);
          long old = insertRoom(jdbc, seed, "OPEN", "NOT_READY", OLD_OPEN, OLD_CLOSE);
          long game = insertGame(jdbc, old, question(jdbc));
          Instant expiry = OLD_CLOSE.plusMinutes(35).toInstant();
          assertThat(
                  jdbc.sql(
                          """
                          insert into publication_idempotency (user_id, request_key, normalized_input, game_id, record_expires_at)
                          values (?, 'old-record', '{}'::jsonb, ?, ?)
                          """)
                      .params(seed.user(), game, expiry.atOffset(UTC))
                      .update())
              .isOne();
          long earlyClosed =
              insertRoom(
                  jdbc,
                  seed(jdbc),
                  "CLOSED",
                  "NOT_READY",
                  AS_OF.atOffset(UTC),
                  AS_OF.atOffset(UTC).plusHours(24));
          insertRoom(
              jdbc,
              seed(jdbc),
              "OPEN",
              "NOT_READY",
              AS_OF.atOffset(UTC),
              AS_OF.atOffset(UTC).plusHours(24));
          insertRoom(jdbc, seed(jdbc), "CLOSED", "FINAL", OLD_OPEN, OLD_CLOSE);
          insertRoom(jdbc, seed(jdbc), "CLOSED", "NOT_READY", null, null);
          assertThat(repository.findUnsettledCandidateIds(AS_OF))
              .containsExactlyInAnyOrder(old, earlyClosed);

          long[] ids =
              transaction(context)
                  .execute(status -> {
                    Room expired = em.find(Room.class, old);
                    expired.closeIntake();
                    em.flush();
                    assertThat(roomRow(jdbc, old)).containsEntry("status", "CLOSED");
                    Room next = prepare(em, seed);
                    long nextGame = insertGame(jdbc, next.getId(), question(jdbc));
                    next.publish(AS_OF, AS_OF.plus(Duration.ofHours(24)));
                    em.flush();
                    if (!commit) status.setRollbackOnly();
                    return new long[] {next.getId(), nextGame};
                  });
          assertThat(repository.findUnsettledCandidateIds(AS_OF))
              .containsExactlyInAnyOrder(old, earlyClosed);
          assertThat(roomRow(jdbc, old))
              .containsEntry("status", commit ? "CLOSED" : "OPEN")
              .containsEntry("result_state", "NOT_READY");
          var oldState = repository.findCurrentState(old).orElseThrow();
          assertThat(oldState.getOpenedAt()).isEqualTo(OLD_OPEN.toInstant());
          assertThat(oldState.getClosesAt()).isEqualTo(OLD_CLOSE.toInstant());
          assertThat(
                  jdbc.sql(
                          """
                          select record_expires_at from publication_idempotency where user_id = ? and request_key = 'old-record'
                          """)
                      .param(seed.user())
                      .query(OffsetDateTime.class)
                      .single()
                      .toInstant())
              .isEqualTo(expiry);
          assertThat(count(jdbc, "rooms", ids[0])).isEqualTo(commit ? 1L : 0L);
          assertThat(count(jdbc, "games", ids[1])).isEqualTo(commit ? 1L : 0L);
          System.out.printf(
              "P1-02 2-A commit=%s old=%d state=%s newRoom=%d newGame=%d oldExpiry=%s%n",
              commit, old, oldState.getStatus(), ids[0], ids[1], expiry);
        });
  }

  @ParameterizedTest
  @CsvSource({"true, PT5M", "true, PT0S", "false, PT5M"})
  @DisplayName("P1-02/G2: 마지막 DB 공개 시각으로 만료를 저장하고 23시간이면 전체 롤백한다")
  void p102RoomPublishesOnlyAfterCompleteAssemblyAndExactly24Hours(boolean valid, String graceText)
      throws SQLException {
    POSTGRES.inMigratedSchema(
        "P1-02 complete publication valid=" + valid,
        (context, pool) -> {
          var jdbc = context.getBean(JdbcClient.class);
          var em = entityManager(context);
          var tx = transaction(context);
          var observer = transaction(context);
          observer.setPropagationBehavior(PROPAGATION_REQUIRES_NEW);
          var rooms = context.getBean(RoomRepository.class);
          var records = context.getBean(PublicationIdempotencyRepository.class);
          var clock = context.getBean(DbTimeSource.class);
          Duration grace = Duration.parse(graceText);
          Seed seed = seed(jdbc);
          long question = question(jdbc);
          List<Long> sources =
              jdbc.sql(
                      """
                      insert into question_preset_choices (question_preset_id, choice_text)
                      select ?, 'choice-' || i from generate_series(0, 3) i returning id
                      """)
                  .param(question)
                  .query(Long.class)
                  .list();
          String key = "p1b-publish";
          String countClaim =
              "select count(*) from publication_idempotency where user_id = ? and request_key = ?";
          var input =
              new PublicationInput(
                  PublicationInput.Kind.NK_CREATE,
                  null,
                  seed.reward(),
                  question,
                  sources,
                  sources.getFirst());
          long[] ids = new long[2];
          Throwable failure =
              catchThrowable(() ->
                  tx.executeWithoutResult(status -> {
                    var claim = records.tryClaim(seed.user(), key, input).orElseThrow();
                    Room room = prepare(em, seed);
                    ids[0] = room.getId();
                    assertThat(roomRow(jdbc, ids[0]))
                        .containsEntry("opened_at", null)
                        .containsEntry("closes_at", null);
                    Game game = Game.prepare(room, em.getReference(QuestionPreset.class, question));
                    em.persist(game);
                    ids[1] = game.getId();
                    List<Choice> choices =
                        sources.stream()
                            .map(source -> {
                              Choice choice =
                                  Choice.copyOf(
                                      game,
                                      em.getReference(QuestionPresetChoice.class, source),
                                      sources.indexOf(source));
                              em.persist(choice);
                              return choice;
                            })
                            .toList();
                    em.flush();
                    game.initializeCorrectChoice(choices.getFirst());
                    game.verifyComplete(choices);
                    em.flush();
                    int actorPid =
                        jdbc.sql("select pg_backend_pid()").query(Integer.class).single();
                    observer.executeWithoutResult(observerStatus -> {
                      int observerPid =
                          jdbc.sql("select pg_backend_pid()").query(Integer.class).single();
                      assertThat(observerPid).isNotEqualTo(actorPid);
                      assertThat(count(jdbc, "rooms", ids[0])).isZero();
                      assertThat(count(jdbc, "games", ids[1])).isZero();
                      assertThat(choiceCount(jdbc, ids[1])).isZero();
                      assertThat(
                              jdbc.sql(countClaim)
                                  .params(seed.user(), key)
                                  .query(Long.class)
                                  .single())
                          .isZero();
                      System.out.printf(
                          "P1-02 uncommitted actor=%d observer=%d room=%d game=%d invisible%n",
                          actorPid, observerPid, ids[0], ids[1]);
                    });
                    Instant dbNow = clock.readCurrentTime();
                    room.publish(dbNow, dbNow.plus(Duration.ofHours(valid ? 24 : 23)));
                    em.flush();
                    Instant storedOpenedAt =
                        rooms.findCurrentState(room.getId()).orElseThrow().getOpenedAt();
                    assertThat(storedOpenedAt).isEqualTo(dbNow);
                    records.completeClaim(
                        claim,
                        game.getId(),
                        new PublicationProperties(grace).recordExpiresAt(storedOpenedAt));
                  }));
          if (valid) {
            assertThat(failure).isNull();
            var opened = rooms.findCurrentState(ids[0]).orElseThrow();
            assertThat(Duration.between(opened.getOpenedAt(), opened.getClosesAt()))
                .isEqualTo(Duration.ofHours(24));
            assertThat(count(jdbc, "games", ids[1])).isOne();
            assertThat(choiceCount(jdbc, ids[1])).isEqualTo(4);
            Instant expiry =
                jdbc.sql(
                        """
                        select record_expires_at from publication_idempotency
                        where user_id = ? and request_key = ? and game_id = ?
                          and isfinite(record_expires_at)
                        """)
                    .params(seed.user(), key, ids[1])
                    .query(OffsetDateTime.class)
                    .single()
                    .toInstant();
            assertThat(expiry)
                .isEqualTo(
                    opened.getOpenedAt().plus(Duration.ofHours(24).plusMinutes(30)).plus(grace));
            System.out.printf(
                "G2 last-stage DB opened_at=%s closes_at=%s grace=%s stored expiry=%s room=%d game=%d%n",
                opened.getOpenedAt(), opened.getClosesAt(), grace, expiry, ids[0], ids[1]);
          } else {
            assertThat(failure)
                .isInstanceOfSatisfying(
                    DomainException.class,
                    e ->
                        assertThat(e.getErrorCode()).isEqualTo(RoomErrorCode.INVALID_CLOSING_TIME));
            assertThat(count(jdbc, "rooms", ids[0])).isZero();
            assertThat(count(jdbc, "games", ids[1])).isZero();
            assertThat(choiceCount(jdbc, ids[1])).isZero();
            assertThat(jdbc.sql(countClaim).params(seed.user(), key).query(Long.class).single())
                .isZero();
          }
          System.out.printf("P1-02 publication valid=%s room=%d game=%d%n", valid, ids[0], ids[1]);
        });
  }

  private static EntityManager entityManager(AssertableApplicationContext context) {
    return SharedEntityManagerCreator.createSharedEntityManager(
        context.getBean(EntityManagerFactory.class));
  }

  private static TransactionTemplate transaction(AssertableApplicationContext context) {
    return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
  }

  private static Room prepare(EntityManager em, Seed seed) {
    Room room =
        Room.prepare(
            em.getReference(User.class, seed.user()),
            em.getReference(RewardPreset.class, seed.reward()));
    em.persist(room);
    return room;
  }

  private static Seed seed(JdbcClient jdbc) {
    long user =
        jdbc.sql(
                """
                insert into users(uuid, display_name, status, created_at, updated_at)
                values (gen_random_uuid(), 'phase1-host', 'ACTIVE', now(), now()) returning id
                """)
            .query(Long.class)
            .single();

    long reward =
        jdbc.sql(
                "insert into reward_presets(title, description) values ('reward', 'only test') returning id")
            .query(Long.class)
            .single();
    return new Seed(user, reward);
  }

  private static long question(JdbcClient jdbc) {
    return jdbc.sql(
            "insert into question_presets(question_text) values ('publication fixture') returning id")
        .query(Long.class)
        .single();
  }

  private static long insertGame(JdbcClient jdbc, long room, long question) {
    return jdbc.sql(
            """
            insert into games(room_id, question_preset_id, question_text_snapshot, created_at)
            values (?, ?, 'phase1', now()) returning id
            """)
        .params(room, question)
        .query(Long.class)
        .single();
  }

  private static long insertRoom(
      JdbcClient jdbc,
      Seed seed,
      String status,
      String resultState,
      OffsetDateTime opened,
      OffsetDateTime closed) {
    return jdbc.sql(
            """
            insert into rooms(host_user_id, selected_reward_preset_id, status, result_state, opened_at, closes_at, created_at)
            values (?, ?, ?, ?, ?, ?, now()) returning id
            """)
        .params(seed.user(), seed.reward(), status, resultState, opened, closed)
        .query(Long.class)
        .single();
  }

  private static Map<String, Object> roomRow(JdbcClient jdbc, long id) {
    return jdbc.sql("select * from rooms where id = ?").param(id).query().singleRow();
  }

  private static long count(JdbcClient jdbc, String table, long id) {
    return jdbc.sql("select count(*) from " + table + " where id = ?")
        .param(id)
        .query(Long.class)
        .single();
  }

  private static long choiceCount(JdbcClient jdbc, long game) {
    return jdbc.sql("select count(*) from choices where game_id = ?")
        .param(game)
        .query(Long.class)
        .single();
  }

  private static void assertViolation(Runnable operation, String state, String marker) {
    Throwable cause = catchThrowable(operation::run);
    while (cause != null && !(cause instanceof SQLException)) cause = cause.getCause();
    assertThat(cause).isInstanceOf(SQLException.class);
    SQLException sql = (SQLException) cause;
    assertThat(sql.getSQLState()).isEqualTo(state);
    assertThat(sql.getMessage()).contains("\"" + marker + "\"");
    System.out.printf("P1-02/P1-04 SQLSTATE=%s constraint/column=%s%n", state, marker);
  }

  private record Seed(long user, long reward) {}
}

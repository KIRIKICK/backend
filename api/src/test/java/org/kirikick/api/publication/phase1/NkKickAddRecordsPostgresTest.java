package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.common.infra.DbTimeSource;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.domain.PublicationKind;
import org.kirikick.api.publication.domain.PublicationRequestKey;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.kirikick.api.publication.repository.PublicationIdempotencyRepository;
import org.kirikick.api.publication.repository.model.ExistingReservation;
import org.kirikick.api.publication.repository.model.PublicationClaim;
import org.kirikick.api.publication.repository.model.PublicationReplay;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class NkKickAddRecordsPostgresTest {
  private static final PublicationPhase1Postgres POSTGRES = new PublicationPhase1Postgres();
  private static final Instant EXPIRY = Instant.parse("2030-01-01T00:00:00Z");

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.close();
  }

  @Test
  @DisplayName("R-01/R-02: raw 후보는 인가 전 JSON·만료를 해석하지 않고 이후 복원 불가로 격리한다")
  void rawConflictReservationDefersMalformedJsonAndInfiniteExpiryUntilResolution()
      throws SQLException {
    inSchema((tx, db) -> {
      Fixture fixture = fixture(db);
      db.sql(
              """
              insert into publication_idempotency(user_id, request_key, normalized_input, game_id,
                                                  record_expires_at)
              values (?, 'corrupt', '{}'::jsonb, ?, 'infinity')
              """)
          .params(fixture.user(), fixture.game())
          .update();
      String before = rawRow(db, fixture.user(), "corrupt");
      PublicationIdempotencyRepository records = recordsAt(db);

      assertThatThrownBy(() ->
              tx.execute(status -> {
                var reservation =
                    records.reserveNkKickAdd(
                        fixture.user(), new PublicationRequestKey("corrupt"), fixture.input());
                assertThat(reservation).isInstanceOf(ExistingReservation.class);
                return records.resolveNkKickAdd(
                    (ExistingReservation) reservation, fixture.input(), EXPIRY);
              }))
          .isInstanceOfSatisfying(
              DomainException.class,
              error ->
                  assertThat(error.getErrorCode())
                      .isEqualTo(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD));
      assertThat(rawRow(db, fixture.user(), "corrupt")).isEqualTo(before);
    });
  }

  @Test
  @DisplayName("R-01/R-02: malformed·incomplete·nonfinite raw 행은 인가 뒤에도 보존한다")
  void malformedIncompleteAndNonfiniteRawRecordsAreQuarantinedWithoutReplacement()
      throws SQLException {
    inSchema((tx, db) -> {
      Fixture fixture = fixture(db);
      String validInput = inputJson(fixture.input());
      List<BrokenRecord> records =
          List.of(
              new BrokenRecord("bad-json-shape", "{}", null, null),
              new BrokenRecord("bad-enum", "{\"kind\":\"OTHER\"}", null, null),
              new BrokenRecord("numeric-enum", "{\"kind\":1}", null, null),
              new BrokenRecord("missing-required", "{\"kind\":\"NK_KICK_ADD\"}", null, null),
              new BrokenRecord("incomplete", validInput, null, null));
      PublicationIdempotencyRepository repository = recordsAt(db);
      for (BrokenRecord record : records) {
        db.sql(
                """
                insert into publication_idempotency(user_id, request_key, normalized_input, game_id,
                                                    record_expires_at)
                values (?, ?, cast(? as jsonb), ?, cast(? as timestamptz))
                """)
            .params(
                fixture.user(),
                record.requestKey(),
                record.normalizedInput(),
                record.gameId(),
                record.expiry())
            .update();
        String before = rawRow(db, fixture.user(), record.requestKey());

        assertThatThrownBy(() ->
                tx.execute(status -> {
                  var raw =
                      repository.reserveNkKickAdd(
                          fixture.user(),
                          new PublicationRequestKey(record.requestKey()),
                          fixture.input());
                  assertThat(raw).isInstanceOf(ExistingReservation.class);
                  return repository.resolveNkKickAdd(
                      (ExistingReservation) raw, fixture.input(), EXPIRY.plusNanos(1_000));
                }))
            .isInstanceOfSatisfying(
                DomainException.class,
                error ->
                    assertThat(error.getErrorCode())
                        .isEqualTo(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD));
        assertThat(rawRow(db, fixture.user(), record.requestKey())).isEqualTo(before);
      }
      for (String infinity : List.of("infinity", "-infinity")) {
        Fixture infinityFixture = fixture(db);
        String key = "nonfinite-" + infinity;
        db.sql(
                """
                insert into publication_idempotency(user_id, request_key, normalized_input, game_id,
                                                    record_expires_at)
                values (?, ?, cast(? as jsonb), ?, cast(? as timestamptz))
                """)
            .params(
                infinityFixture.user(),
                key,
                inputJson(infinityFixture.input()),
                infinityFixture.game(),
                infinity)
            .update();
        String before = rawRow(db, infinityFixture.user(), key);
        assertThatThrownBy(() ->
                tx.execute(status -> {
                  var raw =
                      repository.reserveNkKickAdd(
                          infinityFixture.user(),
                          new PublicationRequestKey(key),
                          infinityFixture.input());
                  assertThat(raw).isInstanceOf(ExistingReservation.class);
                  return repository.resolveNkKickAdd(
                      (ExistingReservation) raw, infinityFixture.input(), EXPIRY.plusNanos(1_000));
                }))
            .isInstanceOfSatisfying(
                DomainException.class,
                error ->
                    assertThat(error.getErrorCode())
                        .isEqualTo(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD));
        assertThat(rawRow(db, infinityFixture.user(), key)).isEqualTo(before);
      }
    });
  }

  @Test
  @DisplayName("R-02/R-04: 추가 출제는 보기 순서와 무관하게 복구하고 명령 충돌을 거절한다")
  void replaysCanonicalInputAndRejectsAnUnexpiredDifferentCommand() throws SQLException {
    inSchema((tx, db) -> {
      Fixture fixture = fixture(db);
      PublicationIdempotencyRepository records = recordsAt(db);
      tx.executeWithoutResult(status -> {
        PublicationClaim claim =
            (PublicationClaim)
                records.reserveNkKickAdd(
                    fixture.user(), new PublicationRequestKey("replay"), fixture.input());
        records.completeClaim(claim, fixture.game(), EXPIRY);
      });
      PublicationInput reordered =
          new PublicationInput(
              PublicationKind.NK_KICK_ADD,
              fixture.room(),
              null,
              fixture.input().questionPresetId(),
              fixture.input().choiceIds().reversed(),
              fixture.input().correctChoiceId());
      PublicationReplay replay =
          tx.execute(status -> {
            ExistingReservation existing =
                (ExistingReservation)
                    records.reserveNkKickAdd(
                        fixture.user(), new PublicationRequestKey("replay"), reordered);
            return (PublicationReplay)
                records.resolveNkKickAdd(existing, reordered, EXPIRY.minusNanos(1_000));
          });
      assertThat(replay.gameId()).isEqualTo(fixture.game());
      assertThat(replay.roomId()).isEqualTo(fixture.room());

      Fixture other = fixture(db);
      PublicationInput create =
          new PublicationInput(
              PublicationKind.NK_CREATE,
              null,
              other.reward(),
              other.input().questionPresetId(),
              other.input().choiceIds(),
              other.input().correctChoiceId());
      tx.executeWithoutResult(status -> {
        PublicationClaim claim =
            records
                .tryClaim(other.user(), new PublicationRequestKey("command"), create)
                .orElseThrow();
        records.completeClaim(claim, other.game(), EXPIRY);
      });
      assertThatThrownBy(() ->
              tx.execute(status -> {
                ExistingReservation existing =
                    (ExistingReservation)
                        records.reserveNkKickAdd(
                            other.user(), new PublicationRequestKey("command"), other.input());
                return records.resolveNkKickAdd(existing, other.input(), EXPIRY.minusNanos(1_000));
              }))
          .isInstanceOfSatisfying(
              DomainException.class,
              error ->
                  assertThat(error.getErrorCode())
                      .isEqualTo(PublicationErrorCode.IDEMPOTENCY_KEY_CONFLICT));
    });
  }

  @Test
  @DisplayName("R-03/R-04: 저장 만료 정각에서만 세대를 교체하고 이전 claim을 거절한다")
  void replacesAtTheStoredExpiryBoundaryAndRejectsThePreviousClaim() throws SQLException {
    inSchema((tx, db) -> {
      Fixture fixture = fixture(db);
      PublicationIdempotencyRepository records = recordsAt(db);
      PublicationClaim old =
          tx.execute(status -> {
            PublicationClaim claim =
                (PublicationClaim)
                    records.reserveNkKickAdd(
                        fixture.user(), new PublicationRequestKey("expiry"), fixture.input());
            records.completeClaim(claim, fixture.game(), EXPIRY);
            return claim;
          });
      Object before =
          tx.execute(status -> {
            ExistingReservation existing =
                (ExistingReservation)
                    records.reserveNkKickAdd(
                        fixture.user(), new PublicationRequestKey("expiry"), fixture.input());
            return records.resolveNkKickAdd(existing, fixture.input(), EXPIRY.minusNanos(1_000));
          });
      assertThat(before).isInstanceOf(PublicationReplay.class);
      tx.executeWithoutResult(status -> {
        ExistingReservation existing =
            (ExistingReservation)
                records.reserveNkKickAdd(
                    fixture.user(), new PublicationRequestKey("expiry"), fixture.input());
        PublicationClaim replacement =
            (PublicationClaim) records.resolveNkKickAdd(existing, fixture.input(), EXPIRY);
        assertThatThrownBy(() -> records.completeClaim(old, fixture.game(), EXPIRY))
            .isInstanceOfSatisfying(
                DomainException.class,
                error ->
                    assertThat(error.getErrorCode())
                        .isEqualTo(PublicationErrorCode.PUBLICATION_CLAIM_COMPLETION_FAILED));
        records.completeClaim(replacement, fixture.game(), EXPIRY.plusSeconds(1));
      });

      Fixture after = fixture(db);
      tx.executeWithoutResult(status -> {
        PublicationClaim claim =
            (PublicationClaim)
                records.reserveNkKickAdd(
                    after.user(), new PublicationRequestKey("after"), after.input());
        records.completeClaim(claim, after.game(), EXPIRY);
      });
      tx.executeWithoutResult(status -> {
        ExistingReservation existing =
            (ExistingReservation)
                records.reserveNkKickAdd(
                    after.user(), new PublicationRequestKey("after"), after.input());
        PublicationClaim replacement =
            (PublicationClaim)
                records.resolveNkKickAdd(existing, after.input(), EXPIRY.plusNanos(1_000));
        records.completeClaim(replacement, after.game(), EXPIRY.plusSeconds(1));
      });
    });
  }

  @Test
  @DisplayName("R-03: 만료 세대 교체 뒤 실패 롤백은 이전 claim·expiry·game을 복원한다")
  void replacementRollbackRestoresThePreviousGeneration() throws SQLException {
    inSchema((tx, db) -> {
      Fixture fixture = fixture(db);
      PublicationIdempotencyRepository records = recordsAt(db);
      tx.executeWithoutResult(status -> {
        PublicationClaim claim =
            (PublicationClaim)
                records.reserveNkKickAdd(
                    fixture.user(), new PublicationRequestKey("rollback"), fixture.input());
        records.completeClaim(claim, fixture.game(), EXPIRY);
      });
      String before = rawRow(db, fixture.user(), "rollback");
      assertThatThrownBy(() ->
              tx.executeWithoutResult(status -> {
                ExistingReservation existing =
                    (ExistingReservation)
                        records.reserveNkKickAdd(
                            fixture.user(), new PublicationRequestKey("rollback"), fixture.input());
                assertThat(records.resolveNkKickAdd(existing, fixture.input(), EXPIRY))
                    .isInstanceOf(PublicationClaim.class);
                throw new DeliberateRollback();
              }))
          .isInstanceOf(DeliberateRollback.class);
      assertThat(rawRow(db, fixture.user(), "rollback")).isEqualTo(before);
    });
  }

  @Test
  @DisplayName("R-02/R-04: 저장된 owner·room·question 불일치 완료 기록은 재사용하지 않는다")
  void storedOwnerRoomAndQuestionMismatchesAreUnreadableAndPreserved() throws SQLException {
    inSchema((tx, db) -> {
      Fixture ownerInput = fixture(db);
      Fixture ownerGame = fixture(db);
      Fixture roomGame = fixture(db);
      Fixture roomInput = fixture(db);
      Fixture questionGame = fixture(db);
      Fixture questionInput = fixture(db);
      List<BrokenRecord> records =
          List.of(
              new BrokenRecord(
                  "wrong-owner",
                  inputJson(ownerInput.input()),
                  ownerGame.game(),
                  EXPIRY.toString()),
              new BrokenRecord(
                  "wrong-room",
                  inputJson(
                      withTarget(
                          roomInput.input(),
                          roomInput.room(),
                          roomGame.input().questionPresetId())),
                  roomGame.game(),
                  EXPIRY.toString()),
              new BrokenRecord(
                  "wrong-question",
                  inputJson(
                      withTarget(
                          questionGame.input(),
                          questionGame.room(),
                          questionInput.input().questionPresetId())),
                  questionGame.game(),
                  EXPIRY.toString()));
      List<Fixture> callers = List.of(ownerInput, roomGame, questionGame);
      PublicationIdempotencyRepository repository = recordsAt(db);
      for (int index = 0; index < records.size(); index++) {
        BrokenRecord record = records.get(index);
        Fixture caller = callers.get(index);
        db.sql(
                """
                insert into publication_idempotency(user_id, request_key, normalized_input, game_id,
                                                    record_expires_at)
                values (?, ?, cast(? as jsonb), ?, cast(? as timestamptz))
                """)
            .params(
                caller.user(),
                record.requestKey(),
                record.normalizedInput(),
                record.gameId(),
                record.expiry())
            .update();
        String before = rawRow(db, caller.user(), record.requestKey());
        assertThatThrownBy(() ->
                tx.execute(status -> {
                  var raw =
                      repository.reserveNkKickAdd(
                          caller.user(),
                          new PublicationRequestKey(record.requestKey()),
                          caller.input());
                  return repository.resolveNkKickAdd(
                      (ExistingReservation) raw, caller.input(), EXPIRY.minusNanos(1_000));
                }))
            .isInstanceOfSatisfying(
                DomainException.class,
                error ->
                    assertThat(error.getErrorCode())
                        .isEqualTo(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD));
        assertThat(rawRow(db, caller.user(), record.requestKey())).isEqualTo(before);
      }
    });
  }

  private static PublicationInput withTarget(
      PublicationInput input, long roomId, long questionPresetId) {
    return new PublicationInput(
        PublicationKind.NK_KICK_ADD,
        roomId,
        null,
        questionPresetId,
        input.choiceIds(),
        input.correctChoiceId());
  }

  @Test
  @DisplayName("R-02: stored NK_KICK_ADD와 incoming NK_CREATE는 양방향 충돌로 분류한다")
  void storedKickAddAndIncomingCreateConflictBeforeExpiry() throws SQLException {
    inSchema((tx, db) -> {
      Fixture fixture = fixture(db);
      PublicationIdempotencyRepository repository = recordsAt(db, EXPIRY.minusNanos(1_000));
      tx.executeWithoutResult(status -> {
        PublicationClaim claim =
            (PublicationClaim)
                repository.reserveNkKickAdd(
                    fixture.user(), new PublicationRequestKey("reverse-command"), fixture.input());
        repository.completeClaim(claim, fixture.game(), EXPIRY);
      });
      PublicationInput create =
          new PublicationInput(
              PublicationKind.NK_CREATE,
              null,
              fixture.reward(),
              fixture.input().questionPresetId(),
              fixture.input().choiceIds(),
              fixture.input().correctChoiceId());
      assertThatThrownBy(() ->
              tx.execute(status ->
                  repository.reserveNkCreate(
                      fixture.user(), new PublicationRequestKey("reverse-command"), create)))
          .isInstanceOfSatisfying(
              DomainException.class,
              error ->
                  assertThat(error.getErrorCode())
                      .isEqualTo(PublicationErrorCode.IDEMPOTENCY_KEY_CONFLICT));
    });
  }

  @Test
  @DisplayName("R-02/R-04: 3·5개 보기 또는 없는 정답의 완료 기록은 복원하지 않는다")
  void damagedStoredGameChoicesAndCorrectChoiceAreUnreadableAndPreserved() throws SQLException {
    inSchema((tx, db) -> {
      PublicationIdempotencyRepository repository = recordsAt(db);
      for (StoredGameDamage damage : StoredGameDamage.values()) {
        Fixture fixture = fixture(db);
        String key = "damage-" + damage;
        tx.executeWithoutResult(status -> {
          PublicationClaim claim =
              (PublicationClaim)
                  repository.reserveNkKickAdd(
                      fixture.user(), new PublicationRequestKey(key), fixture.input());
          repository.completeClaim(claim, fixture.game(), EXPIRY);
        });
        damage.apply(db, fixture.game());
        String before = rawRow(db, fixture.user(), key);
        assertThatThrownBy(() ->
                tx.execute(status -> {
                  ExistingReservation existing =
                      (ExistingReservation)
                          repository.reserveNkKickAdd(
                              fixture.user(), new PublicationRequestKey(key), fixture.input());
                  return repository.resolveNkKickAdd(
                      existing, fixture.input(), EXPIRY.minusNanos(1_000));
                }))
            .isInstanceOfSatisfying(
                DomainException.class,
                error ->
                    assertThat(error.getErrorCode())
                        .isEqualTo(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD));
        assertThat(rawRow(db, fixture.user(), key)).isEqualTo(before);
      }
    });
  }

  @Test
  @DisplayName("R-04: 다른 Game 정답은 DB 제약이 거절하고 기존 기록을 보존한다")
  void databaseInvariantRejectsForeignCorrectChoice() throws SQLException {
    inSchema((tx, db) -> {
      Fixture fixture = fixture(db);
      PublicationIdempotencyRepository repository = recordsAt(db);
      tx.executeWithoutResult(status -> {
        PublicationClaim claim =
            (PublicationClaim)
                repository.reserveNkKickAdd(
                    fixture.user(),
                    new PublicationRequestKey("database-invariant"),
                    fixture.input());
        repository.completeClaim(claim, fixture.game(), EXPIRY);
      });
      String before = rawRow(db, fixture.user(), "database-invariant");
      int choiceCount =
          db.sql("select count(*) from choices where game_id = ?")
              .param(fixture.game())
              .query(Integer.class)
              .single();
      Fixture other = fixture(db);
      long otherChoice =
          db.sql("select id from choices where game_id = ? order by id limit 1")
              .param(other.game())
              .query(Long.class)
              .single();
      assertThatThrownBy(() ->
              db.sql("update games set correct_choice_id = ? where id = ?")
                  .params(otherChoice, fixture.game())
                  .update())
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(rawRow(db, fixture.user(), "database-invariant")).isEqualTo(before);
      assertThat(
              db.sql("select count(*) from choices where game_id = ?")
                  .param(fixture.game())
                  .query(Integer.class)
                  .single())
          .isEqualTo(choiceCount);
    });
  }

  private static String inputJson(PublicationInput input) {
    return """
    {"kind":"%s","roomId":%d,"rewardPresetId":null,"questionPresetId":%d,
    "choiceIds":%s,"correctChoiceId":%d}
    """
        .formatted(
            input.kind(),
            input.roomId(),
            input.questionPresetId(),
            input.choiceIds(),
            input.correctChoiceId())
        .replaceAll("\s+", "");
  }

  private static String rawRow(JdbcClient db, long userId, String key) {
    return db.sql(
            """
            select claim_id::text || '|' || normalized_input::text || '|'
                   || coalesce(game_id::text, 'null') || '|'
                   || coalesce(record_expires_at::text, 'null')
              from publication_idempotency where user_id = ? and request_key = ?
            """)
        .params(userId, key)
        .query(String.class)
        .single();
  }

  private static PublicationIdempotencyRepository recordsAt(JdbcClient db) {
    return recordsAt(db, EXPIRY);
  }

  private static PublicationIdempotencyRepository recordsAt(JdbcClient db, Instant cutoff) {
    return new PublicationIdempotencyRepository(
        db,
        new DbTimeSource(db) {
          @Override
          public Instant readCurrentTime() {
            return cutoff;
          }
        });
  }

  private static Fixture fixture(JdbcClient db) {
    long user =
        id(
            db,
            """
            insert into users(uuid, display_name, status, created_at, updated_at)
            values (?, 'synthetic', 'ACTIVE', now(), now()) returning id
            """,
            UUID.randomUUID());
    long reward =
        id(
            db,
            "insert into reward_presets(title, description) values ('reward', 'fixture') returning id");
    long question =
        id(db, "insert into question_presets(question_text) values ('fixture') returning id");
    List<Long> choices = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      choices.add(
          id(
              db,
              "insert into question_preset_choices(question_preset_id, choice_text) values (?, ?) returning id",
              question,
              "choice-" + i));
    }
    long room =
        id(
            db,
            "insert into rooms(host_user_id, selected_reward_preset_id, status, created_at, opened_at, closes_at) values (?, ?, 'CLOSED', now(), now(), now() + interval '24 hours') returning id",
            user,
            reward);
    long game =
        id(
            db,
            "insert into games(room_id, question_preset_id, question_text_snapshot, created_at) values (?, ?, 'fixture', now()) returning id",
            room,
            question);
    long correct = 0;
    for (int i = 0; i < 4; i++) {
      long choice =
          id(
              db,
              "insert into choices(game_id, position, text_snapshot) values (?, ?, ?) returning id",
              game,
              i,
              "choice-" + i);
      if (i == 0) correct = choice;
    }
    db.sql("update games set correct_choice_id = ? where id = ?").params(correct, game).update();
    return new Fixture(
        user,
        room,
        game,
        reward,
        new PublicationInput(
            PublicationKind.NK_KICK_ADD, room, null, question, choices, choices.getFirst()));
  }

  private static long id(JdbcClient db, String sql, Object... args) {
    return db.sql(sql).params(args).query(Long.class).single();
  }

  private static void inSchema(BiConsumer<TransactionTemplate, JdbcClient> test)
      throws SQLException {
    POSTGRES.inMigratedSchema(
        "NK76-record", (context, pool) -> test.accept(tx(context), JdbcClient.create(pool)));
  }

  private static TransactionTemplate tx(ApplicationContext context) {
    return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
  }

  private record Fixture(long user, long room, long game, long reward, PublicationInput input) {}

  private record BrokenRecord(
      String requestKey, String normalizedInput, Long gameId, String expiry) {}

  private enum StoredGameDamage {
    THREE_CHOICES {
      @Override
      void apply(JdbcClient db, long gameId) {
        db.sql(
                "delete from choices where id = (select id from choices where game_id = ? and id <> (select correct_choice_id from games where id = ?) order by id limit 1)")
            .params(gameId, gameId)
            .update();
      }
    },
    FIVE_CHOICES {
      @Override
      void apply(JdbcClient db, long gameId) {
        db.sql("insert into choices(game_id, position, text_snapshot) values (?, 4, 'fifth')")
            .param(gameId)
            .update();
      }
    },
    MISSING_CORRECT {
      @Override
      void apply(JdbcClient db, long gameId) {
        db.sql("update games set correct_choice_id = null where id = ?").param(gameId).update();
      }
    };

    abstract void apply(JdbcClient db, long gameId);
  }

  private static class DeliberateRollback extends RuntimeException {}
}

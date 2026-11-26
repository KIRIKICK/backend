package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.common.infra.DbTimeSource;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.domain.PublicationKind;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.kirikick.api.publication.repository.PublicationIdempotencyRepository;
import org.kirikick.api.publication.repository.model.PublicationClaim;
import org.kirikick.api.publication.repository.model.PublicationReplay;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class NkCreateRecordsPostgresTest {

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
  @DisplayName("R-04/R-05: 같은 의미는 복구하고 다른 의미는 기록을 바꾸지 않는다")
  void onlyReplaysTheSameMeaningWithoutChangingTheStoredGeneration() throws SQLException {
    inSchema((tx, db) -> {
      var f = fixture(db);
      var records = recordsAt(db, -1);
      tx.executeWithoutResult(status -> {
        var claim = (PublicationClaim) records.reserveNkCreate(f.user(), "replay", f.input());
        records.completeClaim(claim, f.game(), EXPIRY);
      });
      var before = row(db, f, "replay");
      var reordered =
          new PublicationInput(
              PublicationKind.NK_CREATE,
              null,
              f.input().rewardPresetId(),
              f.input().questionPresetId(),
              f.input().choiceIds().reversed(),
              f.input().correctChoiceId());
      var replay =
          (PublicationReplay)
              tx.execute(status -> records.reserveNkCreate(f.user(), "replay", reordered));
      assertThat(replay.gameId()).isEqualTo(f.game());
      assertThat(replay.roomId()).isEqualTo(f.room());
      assertThat(replay.closesAt()).isEqualTo(replay.openedAt().plusSeconds(86400));
      var old = f.input();
      var changedChoice = new ArrayList<>(old.choiceIds());
      changedChoice.set(3, old.choiceIds().getLast() + 10);
      for (var changed :
          List.of(
              new PublicationInput(
                  PublicationKind.NK_CREATE,
                  null,
                  old.rewardPresetId() + 10,
                  old.questionPresetId(),
                  old.choiceIds(),
                  old.correctChoiceId()),
              new PublicationInput(
                  PublicationKind.NK_CREATE,
                  null,
                  old.rewardPresetId(),
                  old.questionPresetId() + 10,
                  old.choiceIds(),
                  old.correctChoiceId()),
              new PublicationInput(
                  PublicationKind.NK_CREATE,
                  null,
                  old.rewardPresetId(),
                  old.questionPresetId(),
                  changedChoice,
                  old.correctChoiceId()),
              new PublicationInput(
                  PublicationKind.NK_CREATE,
                  null,
                  old.rewardPresetId(),
                  old.questionPresetId(),
                  old.choiceIds(),
                  old.choiceIds().get(1)))) {
        assertThatThrownBy(
                () -> tx.execute(status -> records.reserveNkCreate(f.user(), "replay", changed)))
            .isInstanceOfSatisfying(
                DomainException.class,
                err ->
                    assertThat(err.getErrorCode())
                        .isEqualTo(PublicationErrorCode.IDEMPOTENCY_KEY_CONFLICT));
      }
      assertThat(row(db, f, "replay")).isEqualTo(before);
    });
  }

  @Test
  @DisplayName("R-06/R-08/R-09: 저장 만료 경계와 세대 교체 실패는 이전 세대를 보존한다")
  void replacesOnlyAtOrAfterThePgMicrosecondBoundaryAndRollsBackTheWholeGeneration()
      throws SQLException {
    inSchema((tx, db) -> {
      var f = fixture(db);
      var old = recordsAt(db, -1);
      var oldClaim =
          tx.execute(status -> {
            var claim = (PublicationClaim) old.reserveNkCreate(f.user(), "expiry", f.input());
            old.completeClaim(claim, f.game(), EXPIRY);
            return claim;
          });
      var before = row(db, f, "expiry");
      var beforeExpiry = tx.execute(status -> old.reserveNkCreate(f.user(), "expiry", f.input()));
      assertThat(beforeExpiry).isInstanceOf(PublicationReplay.class);
      var exact = recordsAt(db, 0);
      assertThatThrownBy(() ->
              tx.executeWithoutResult(status -> {
                var fresh = exact.reserveNkCreate(f.user(), "expiry", f.input());
                assertThat(fresh).isInstanceOf(PublicationClaim.class);
                assertThat(row(db, f, "expiry"))
                    .containsEntry("game_id", null)
                    .containsEntry("record_expires_at", null)
                    .doesNotContainEntry("claim_id", before.get("claim_id"));
                throw new DeliberateRollback();
              }))
          .isInstanceOf(DeliberateRollback.class);
      assertThat(row(db, f, "expiry")).isEqualTo(before);
      var after = recordsAt(db, 1);
      assertThatThrownBy(() ->
              tx.executeWithoutResult(status -> {
                assertThat(after.reserveNkCreate(f.user(), "expiry", f.input()))
                    .isInstanceOf(PublicationClaim.class);
                after.completeClaim(oldClaim, f.game(), EXPIRY.plusSeconds(1));
              }))
          .isInstanceOf(DomainException.class);
      assertThat(row(db, f, "expiry")).isEqualTo(before);
      assertThat(id(db, "select count(*) from games where id = ?", f.game())).isOne();
      var winner =
          tx.execute(
              status -> (PublicationClaim) exact.reserveNkCreate(f.user(), "expiry", f.input()));
      assertThatThrownBy(() ->
              tx.executeWithoutResult(
                  status -> exact.completeClaim(winner, f.game(), EXPIRY.plusSeconds(1))))
          .isInstanceOf(DomainException.class);
      assertThat(row(db, f, "expiry")).containsEntry("game_id", null);
    });
  }

  @Test
  @DisplayName("R-06/R-07: 만료된 정상 기록은 다른 유효 입력으로도 새 세대를 만든다")
  void expiredCompletedRecordCanBeReplacedWithDifferentValidMeaning() throws SQLException {
    inSchema((tx, db) -> {
      var f = fixture(db);
      var alternative = alternativeInput(db);
      var oldRecords = recordsAt(db, -1);
      tx.executeWithoutResult(status -> {
        var old = oldRecords.tryClaim(f.user(), "different-expiry", f.input()).orElseThrow();
        oldRecords.completeClaim(old, f.game(), EXPIRY);
      });
      var before = row(db, f, "different-expiry");
      var alternativeGame = game(db, f.user(), alternative).game();
      var current = recordsAt(db, 0);

      tx.executeWithoutResult(status -> {
        var replacement =
            (PublicationClaim) current.reserveNkCreate(f.user(), "different-expiry", alternative);
        current.completeClaim(replacement, alternativeGame, EXPIRY.plusSeconds(60));
      });

      var replaced = row(db, f, "different-expiry");
      assertThat(replaced)
          .containsEntry("game_id", alternativeGame)
          .doesNotContainEntry("claim_id", before.get("claim_id"))
          .doesNotContainEntry("input", before.get("input"));
      var replacementRecord =
          tx.execute(status -> current.findForUpdate(f.user(), "different-expiry").orElseThrow());
      assertThat(replacementRecord.normalizedInput()).isEqualTo(alternative);
      assertThat(replacementRecord.gameId()).isEqualTo(alternativeGame);
      assertThat(replacementRecord.claimId()).isNotEqualTo(before.get("claim_id"));
      var replay =
          (PublicationReplay)
              tx.execute(
                  status -> current.reserveNkCreate(f.user(), "different-expiry", alternative));
      assertThat(replay.gameId()).isEqualTo(alternativeGame);
      assertThatThrownBy(() ->
              tx.execute(
                  status -> current.reserveNkCreate(f.user(), "different-expiry", f.input())))
          .isInstanceOfSatisfying(
              DomainException.class,
              error ->
                  assertThat(error.getErrorCode())
                      .isEqualTo(PublicationErrorCode.IDEMPOTENCY_KEY_CONFLICT));
      assertThat(id(db, "select count(*) from games where id = ?", f.game())).isOne();
    });
  }

  @Test
  @DisplayName("R-11: 만료 세대 경합은 한 세대만 공개하고 다른 요청은 복구한다")
  void expiredKeyRaceCommitsOneNewGenerationAndLosingTransactionReplaysIt() throws SQLException {
    inSchema((tx, db) -> {
      var f = fixture(db);
      long newGame = game(db, f.user(), f.input()).game();
      var records = recordsAt(db, 0);
      var waiter = new CompletableFuture<Long>();
      tx.executeWithoutResult(status -> {
        var old = records.tryClaim(f.user(), "expired-race", f.input()).orElseThrow();
        records.completeClaim(old, f.game(), EXPIRY);
      });
      var before = row(db, f, "expired-race");
      try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
        var result =
            tx.execute(status -> {
              long holder = id(db, "select pg_backend_pid()");
              var fresh =
                  (PublicationClaim) records.reserveNkCreate(f.user(), "expired-race", f.input());
              var waiting =
                  worker.submit(() ->
                      tx.execute(other -> {
                        waiter.complete(id(db, "select pg_backend_pid()"));
                        return records.reserveNkCreate(f.user(), "expired-race", f.input());
                      }));
              awaitLock(db, get(waiter), holder);
              records.completeClaim(fresh, newGame, EXPIRY.plusSeconds(60));
              return waiting;
            });
        assertThat(get(result))
            .isInstanceOfSatisfying(
                PublicationReplay.class, replay -> assertThat(replay.gameId()).isEqualTo(newGame));
      }
      assertThat(row(db, f, "expired-race"))
          .containsEntry("game_id", newGame)
          .doesNotContainEntry("claim_id", before.get("claim_id"));
      assertThat(id(db, "select count(*) from games where id = ?", f.game())).isOne();
    });
  }

  @Test
  @DisplayName("R-09: 같은 트랜잭션의 이전 claim은 새 세대를 완료하지 못한다")
  void claimIdPreventsCompletionByAnOldHandleEvenInTheSameTransaction() throws SQLException {
    inSchema((tx, db) -> {
      var f = fixture(db);
      var records = recordsAt(db, 0);
      var later = EXPIRY.plusSeconds(60);

      tx.executeWithoutResult(status -> {
        var old = records.tryClaim(f.user(), "same-xid", f.input()).orElseThrow();
        records.completeClaim(old, f.game(), EXPIRY);
        var fresh = (PublicationClaim) records.reserveNkCreate(f.user(), "same-xid", f.input());

        assertThat(fresh).isNotSameAs(old);
        assertThatThrownBy(() -> records.completeClaim(old, f.game(), later))
            .isInstanceOfSatisfying(
                DomainException.class,
                error ->
                    assertThat(error.getErrorCode())
                        .isEqualTo(PublicationErrorCode.PUBLICATION_CLAIM_COMPLETION_FAILED));
        status.setRollbackOnly();
      });

      assertThat(id(db, "select count(*) from publication_idempotency where user_id = ?", f.user()))
          .isZero();
      assertThat(id(db, "select count(*) from games where id = ?", f.game())).isOne();
    });
  }

  @Test
  @DisplayName("완료 claim은 저장된 입력과 다른 질문·보상 또는 없는 게임에 연결하지 않는다")
  void completionRejectsAGameThatCannotBeVerifiedAgainstTheStoredInput() throws SQLException {
    inSchema((tx, db) -> {
      var f = fixture(db);
      var alternative = alternativeInput(db);
      var differentQuestion =
          game(
                  db,
                  f.user(),
                  new PublicationInput(
                      PublicationKind.NK_CREATE,
                      null,
                      f.input().rewardPresetId(),
                      alternative.questionPresetId(),
                      alternative.choiceIds(),
                      alternative.correctChoiceId()))
              .game();
      var differentReward =
          game(
                  db,
                  f.user(),
                  new PublicationInput(
                      PublicationKind.NK_CREATE,
                      null,
                      alternative.rewardPresetId(),
                      f.input().questionPresetId(),
                      f.input().choiceIds(),
                      f.input().correctChoiceId()))
              .game();
      var records = recordsAt(db, -1);

      assertCompletionIsUnreadable(tx, db, records, f, "mismatched-question", differentQuestion);
      assertCompletionIsUnreadable(tx, db, records, f, "mismatched-reward", differentReward);
      assertCompletionIsUnreadable(tx, db, records, f, "missing-game", Long.MAX_VALUE);
    });
  }

  private static void assertCompletionIsUnreadable(
      TransactionTemplate tx,
      JdbcClient db,
      PublicationIdempotencyRepository records,
      Fixture fixture,
      String requestKey,
      long gameId) {
    assertThatThrownBy(() ->
            tx.executeWithoutResult(status -> {
              var claim =
                  records.tryClaim(fixture.user(), requestKey, fixture.input()).orElseThrow();
              records.completeClaim(claim, gameId, EXPIRY);
            }))
        .isInstanceOfSatisfying(
            DomainException.class,
            error ->
                assertThat(error.getErrorCode())
                    .isEqualTo(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD));
    assertThat(
            id(
                db,
                "select count(*) from publication_idempotency where user_id = ? and request_key = ?",
                fixture.user(),
                requestKey))
        .isZero();
  }

  @Test
  @DisplayName("R-09: 다른 트랜잭션의 같은 claim은 완료하지 못하고 기록은 남는다")
  void claimCannotBeCompletedFromAnotherTransaction() throws SQLException {
    inSchema((tx, db) -> {
      var f = fixture(db);
      var records = recordsAt(db, -1);
      var claim =
          tx.execute(status -> records.tryClaim(f.user(), "another-xid", f.input()).orElseThrow());
      var before = row(db, f, "another-xid");

      assertThatThrownBy(() ->
              tx.executeWithoutResult(status -> records.completeClaim(claim, f.game(), EXPIRY)))
          .isInstanceOf(DomainException.class);

      assertThat(row(db, f, "another-xid")).isEqualTo(before);
    });
  }

  @Test
  @DisplayName("R-10: 만료 전후의 잘못된 완료 JSON은 재선점하거나 교체하지 않는다")
  void malformedCompletedRowsArePreservedBeforeAndAfterLogicalExpiry() throws SQLException {
    inSchema((tx, db) -> {
      for (var micros : List.of(-1, 0, 1)) {
        var f = fixture(db);
        String key = "malformed-complete-" + micros;
        db.sql(
                """
                insert into publication_idempotency(
                  user_id, request_key, normalized_input, game_id, record_expires_at
                )
                values (?, ?, '{}'::jsonb, ?, ?)
                """)
            .params(f.user(), key, f.game(), EXPIRY.atOffset(ZoneOffset.UTC))
            .update();
        var before = row(db, f, key);

        assertUnreadable(tx, recordsAt(db, micros), f, key);

        assertThat(row(db, f, key)).isEqualTo(before);
        assertThat(id(db, "select count(*) from games where id = ?", f.game())).isOne();
      }
    });
  }

  @Test
  @DisplayName("R-10: 복원할 수 없는 완료 결과는 미만료와 만료 후에도 교체하지 않는다")
  void corruptCompletedResultsArePreservedBeforeAndAfterLogicalExpiry() throws SQLException {
    inSchema((tx, db) -> {
      for (var damage : ResultDamage.values()) {
        for (var micros : List.of(-1, 0, 1)) {
          var f = fixture(db);
          String key = "corrupt-" + damage + "-" + micros;
          var records = recordsAt(db, -1);
          tx.executeWithoutResult(status -> {
            var claim = records.tryClaim(f.user(), key, f.input()).orElseThrow();
            records.completeClaim(claim, f.game(), EXPIRY);
          });
          var before = row(db, f, key);
          damage.apply(db, f);

          assertUnreadable(tx, recordsAt(db, micros), f, key);

          assertThat(row(db, f, key)).isEqualTo(before);
          assertThat(id(db, "select count(*) from games where id = ?", f.game())).isOne();
        }
      }
    });
  }

  @Test
  @DisplayName("R-10: 불완전하거나 복원할 수 없는 기록은 변경하지 않는다")
  void incompleteMalformedAndUnrestorableRowsStayUntouched() throws SQLException {
    inSchema((tx, db) -> {
      var f = fixture(db);
      var records = recordsAt(db, 1);
      tx.executeWithoutResult(
          status -> records.tryClaim(f.user(), "incomplete", f.input()).orElseThrow());
      db.sql(
              "insert into publication_idempotency(user_id, request_key, normalized_input) values (?, 'malformed', '{}')")
          .param(f.user())
          .update();
      tx.executeWithoutResult(status -> {
        var claim = records.tryClaim(f.user(), "unrestorable", f.input()).orElseThrow();
        records.completeClaim(claim, f.game(), EXPIRY);
      });
      for (var key : List.of("incomplete", "malformed")) {
        var before = row(db, f, key);
        assertUnreadable(tx, records, f, key);
        assertThat(row(db, f, key)).isEqualTo(before);
      }
      var before = row(db, f, "unrestorable");
      long otherHost = fixture(db).user();
      db.sql("update rooms set host_user_id = ? where id = ?").params(otherHost, f.room()).update();
      assertUnreadable(tx, records, f, "unrestorable");
      db.sql("update rooms set host_user_id = ? where id = ?").params(f.user(), f.room()).update();
      db.sql("delete from choices where game_id = ? and position = 3").param(f.game()).update();
      assertUnreadable(tx, records, f, "unrestorable");
      db.sql("insert into choices(game_id, position, text_snapshot) values (?, 3, 'restored')")
          .param(f.game())
          .update();
      long correct = id(db, "select correct_choice_id from games where id = ?", f.game());
      db.sql("update games set correct_choice_id = null where id = ?").param(f.game()).update();
      assertUnreadable(tx, recordsAt(db, -1), f, "unrestorable");
      assertUnreadable(tx, records, f, "unrestorable");
      db.sql("update games set correct_choice_id = ? where id = ?")
          .params(correct, f.game())
          .update();
      db.sql("update rooms set opened_at = null, closes_at = null where id = ?")
          .param(f.room())
          .update();
      assertUnreadable(tx, records, f, "unrestorable");
      assertThat(row(db, f, "unrestorable")).isEqualTo(before);
    });
  }

  private static void assertUnreadable(
      TransactionTemplate tx, PublicationIdempotencyRepository records, Fixture f, String key) {
    assertThatThrownBy(
            () -> tx.execute(status -> records.reserveNkCreate(f.user(), key, f.input())))
        .isInstanceOfSatisfying(
            DomainException.class,
            err ->
                assertThat(err.getErrorCode())
                    .isEqualTo(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD));
  }

  @Test
  @DisplayName("R-02/R-03: 경쟁 INSERT는 실제 잠금 뒤 commit 또는 rollback 결과를 본다")
  void insertLoserWaitsForCommitOrRollbackAndViewsTheRealOutcome() throws SQLException {
    inSchema((tx, db) -> {
      var f = fixture(db);
      var records = recordsAt(db, -1);
      for (var commit : List.of(true, false)) {
        String key = commit ? "winner-commit" : "winner-rollback";
        var waiter = new CompletableFuture<Long>();
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
          Future<Object> result =
              tx.execute(status -> {
                long holder = id(db, "select pg_backend_pid()");
                var original = records.tryClaim(f.user(), key, f.input()).orElseThrow();
                var waiting =
                    worker.submit(() ->
                        tx.execute(other -> {
                          assertThat(
                                  id(
                                      db,
                                      "select count(*) from publication_idempotency where user_id = ?",
                                      f.user()))
                              .isZero();
                          waiter.complete(id(db, "select pg_backend_pid()"));
                          var reservation = records.reserveNkCreate(f.user(), key, f.input());
                          if (reservation instanceof PublicationClaim claim) {
                            records.completeClaim(claim, f.game(), EXPIRY);
                          }
                          return (Object) reservation;
                        }));
                awaitLock(db, get(waiter), holder);
                if (commit) {
                  records.completeClaim(original, f.game(), EXPIRY);
                } else {
                  status.setRollbackOnly();
                }
                return waiting;
              });
          assertThat(get(result))
              .isInstanceOf(commit ? PublicationReplay.class : PublicationClaim.class);
          assertThat(row(db, f, key)).containsEntry("game_id", f.game());
          db.sql("delete from publication_idempotency where user_id = ? and request_key = ?")
              .params(f.user(), key)
              .update();
        }
      }
    });
  }

  private static void awaitLock(JdbcClient db, long waiter, long holder) {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      if (db.sql("select cast(? as integer) = any(pg_blocking_pids(cast(? as integer)))")
          .params(holder, waiter)
          .query(Boolean.class)
          .single()) {
        System.out.printf("observed PG lock: holder=%d waiter=%d%n", holder, waiter);
        return;
      }
    }
    throw new AssertionError("Waiter did not wait on holder's real PG lock");
  }

  private static <T> T get(Future<T> future) {
    try {
      return future.get(10, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    } catch (ExecutionException | TimeoutException exception) {
      throw new AssertionError(exception);
    }
  }

  private static PublicationIdempotencyRepository recordsAt(JdbcClient db, int micros) {
    return new PublicationIdempotencyRepository(
        db,
        new DbTimeSource(db) {
          @Override
          public Instant readCurrentTime() {
            return db.sql("select cast(? as timestamptz) + ? * interval '1 microsecond'")
                .params(EXPIRY.atOffset(ZoneOffset.UTC), micros)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
          }
        });
  }

  private static Map<String, Object> row(JdbcClient db, Fixture f, String key) {
    return db.sql(
            """
            select claim_id, game_id, record_expires_at, normalized_input::text as input
            from publication_idempotency where user_id = ? and request_key = ?
            """)
        .params(f.user(), key)
        .query()
        .singleRow();
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
    var choices = new ArrayList<Long>();
    for (int i = 0; i < 4; i++) {
      choices.add(
          id(
              db,
              """
              insert into question_preset_choices(question_preset_id, choice_text) values (?, ?) returning id
              """,
              question,
              "choice-" + i));
    }
    return game(
        db,
        user,
        new PublicationInput(
            PublicationKind.NK_CREATE, null, reward, question, choices, choices.getFirst()));
  }

  private static PublicationInput alternativeInput(JdbcClient db) {
    long reward =
        id(
            db,
            "insert into reward_presets(title, description) values ('alternative', 'fixture') returning id");
    long question =
        id(db, "insert into question_presets(question_text) values ('alternative') returning id");
    var choices = new ArrayList<Long>();
    for (int i = 0; i < 4; i++) {
      choices.add(
          id(
              db,
              """
              insert into question_preset_choices(question_preset_id, choice_text)
              values (?, ?) returning id
              """,
              question,
              "alternative-" + i));
    }
    return new PublicationInput(
        PublicationKind.NK_CREATE, null, reward, question, choices, choices.getLast());
  }

  private static Fixture game(JdbcClient db, long user, PublicationInput input) {
    long room =
        id(
            db,
            """
            insert into rooms(host_user_id, selected_reward_preset_id, status, created_at, opened_at, closes_at)
            values (?, ?, 'CLOSED', now(), now(), now() + interval '24 hours') returning id
            """,
            user,
            input.rewardPresetId());
    long game =
        id(
            db,
            """
            insert into games(room_id, question_preset_id, question_text_snapshot, created_at)
            values (?, ?, 'fixture', now()) returning id
            """,
            room,
            input.questionPresetId());
    long correct = 0;
    for (int i = 0; i < 4; i++) {
      long choice =
          id(
              db,
              "insert into choices(game_id, position, text_snapshot) values (?, ?, ?) returning id",
              game,
              i,
              "choice-" + i);
      if (i == 0) {
        correct = choice;
      }
    }
    db.sql("update games set correct_choice_id = ? where id = ?").params(correct, game).update();
    return new Fixture(user, room, game, input);
  }

  private static long id(JdbcClient db, String sql, Object... args) {
    return db.sql(sql).params(args).query(Long.class).single();
  }

  private static void inSchema(BiConsumer<TransactionTemplate, JdbcClient> test)
      throws SQLException {
    POSTGRES.inMigratedSchema(
        "NK75-record", (context, pool) -> test.accept(tx(context), JdbcClient.create(pool)));
  }

  private static TransactionTemplate tx(ApplicationContext context) {
    return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
  }

  private record Fixture(long user, long room, long game, PublicationInput input) {}

  private enum ResultDamage {
    HOST {
      @Override
      void apply(JdbcClient db, Fixture f) {
        db.sql("update rooms set host_user_id = ? where id = ?")
            .params(fixture(db).user(), f.room())
            .update();
      }
    },
    TIMES {
      @Override
      void apply(JdbcClient db, Fixture f) {
        db.sql("update rooms set opened_at = null, closes_at = null where id = ?")
            .param(f.room())
            .update();
      }
    },
    CORRECT_CHOICE {
      @Override
      void apply(JdbcClient db, Fixture f) {
        db.sql("update games set correct_choice_id = null where id = ?").param(f.game()).update();
      }
    },
    CHOICES {
      @Override
      void apply(JdbcClient db, Fixture f) {
        db.sql("delete from choices where game_id = ? and position = 3").param(f.game()).update();
      }
    },
    QUESTION {
      @Override
      void apply(JdbcClient db, Fixture f) {
        db.sql("update games set question_preset_id = ? where id = ?")
            .params(alternativeInput(db).questionPresetId(), f.game())
            .update();
      }
    },
    REWARD {
      @Override
      void apply(JdbcClient db, Fixture f) {
        db.sql("update rooms set selected_reward_preset_id = ? where id = ?")
            .params(alternativeInput(db).rewardPresetId(), f.room())
            .update();
      }
    };

    abstract void apply(JdbcClient db, Fixture f);
  }

  private static class DeliberateRollback extends RuntimeException {}
}

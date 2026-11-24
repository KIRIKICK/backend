package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.common.infra.DbTimeSource;
import org.kirikick.api.config.PublicationProperties;
import org.kirikick.api.game.repository.ChoiceRepository;
import org.kirikick.api.game.repository.GameRepository;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.kirikick.api.publication.infra.PublicationCommitObserver;
import org.kirikick.api.publication.repository.PublicationIdempotencyRepository;
import org.kirikick.api.publication.service.OpenRoomService;
import org.kirikick.api.question.repository.QuestionPresetChoiceRepository;
import org.kirikick.api.question.repository.QuestionPresetRepository;
import org.kirikick.api.reward.repository.RewardPresetRepository;
import org.kirikick.api.room.repository.RoomRepository;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;

class OpenRoomServicePostgresTest {
  private static final PublicationPhase1Postgres POSTGRES = new PublicationPhase1Postgres();

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.close();
  }

  @Test
  @DisplayName("S-01: 첫 출제는 방·킥·보기와 완료 기록을 한 번만 저장하고 같은 키는 복구한다")
  void createsAndReplaysExactlyOnce() throws SQLException {
    POSTGRES.inMigratedSchema(
        "nkcreate-service-create-replay",
        (context, pool) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Fixture fixture = fixture(jdbc);
          OpenRoomService service = service(context);
          PublicationInput input =
              new PublicationInput(
                  PublicationInput.Kind.NK_CREATE,
                  null,
                  fixture.rewardId(),
                  fixture.questionId(),
                  fixture.choiceIds().reversed(),
                  fixture.choiceIds().get(1));

          OpenRoomService.Result created =
              service.publish(fixture.userUuid(), "nkcreate-service-key", input);
          OpenRoomService.Result replayed =
              service.publish(fixture.userUuid(), "nkcreate-service-key", input);

          assertThat(created.created()).isTrue();
          assertThat(replayed.created()).isFalse();
          assertThat(replayed)
              .isEqualTo(
                  new OpenRoomService.Result(
                      false,
                      created.roomId(),
                      created.gameId(),
                      created.openedAt(),
                      created.closesAt()));
          assertThat(Duration.between(created.openedAt(), created.closesAt()))
              .isEqualTo(Duration.ofHours(24));
          assertThat(count(jdbc, "rooms")).isEqualTo(1);
          assertThat(count(jdbc, "games")).isEqualTo(1);
          assertThat(count(jdbc, "choices")).isEqualTo(4);
          assertThat(count(jdbc, "publication_idempotency")).isEqualTo(1);
          assertThat(
                  jdbc.sql("select game_id from publication_idempotency where request_key = ?")
                      .param("nkcreate-service-key")
                      .query(Long.class)
                      .single())
              .isEqualTo(created.gameId());
        });
  }

  @ParameterizedTest(name = "기존 OPEN 방={0}")
  @ValueSource(booleans = {false, true})
  @DisplayName("User 잠금 대기 중 정지가 커밋되면 최신 상태로 출제를 거절한다")
  void rejectsAccountSuspendedDuringUserLockWait(boolean existingRoom) throws SQLException {
    POSTGRES.inMigratedSchema(
        "nkcreate-service-suspended-after-wait",
        (context, pool) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Fixture fixture = fixture(jdbc);
          Long oldRoom =
              existingRoom
                  ? insertRoom(
                      jdbc, fixture, "OPEN", "NOT_READY", "now()", "now() + interval '24 hours'")
                  : null;
          OpenRoomService service = service(context);
          try (Connection holder = pool.getConnection();
              ExecutorService workers = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            int holderPid = backendPid(holder);
            try (var statement =
                holder.prepareStatement("select id from users where uuid = ? for no key update")) {
              statement.setObject(1, fixture.userUuid());
              try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
              }
            }
            Future<Object> pending =
                workers.submit(() -> invoke(service, fixture, "suspended-during-wait"));
            WaitEvidence wait = awaitUserWaiters(jdbc, holderPid, pending).getFirst();
            try (var statement =
                holder.prepareStatement("update users set status = 'SUSPENDED' where uuid = ?")) {
              statement.setObject(1, fixture.userUuid());
              assertThat(statement.executeUpdate()).isOne();
            }
            holder.commit();
            assertThat(pending.get(10, TimeUnit.SECONDS))
                .isInstanceOfSatisfying(
                    DomainException.class,
                    failure ->
                        assertThat(failure.getErrorCode())
                            .isEqualTo(AuthErrorCode.ACCOUNT_INACTIVE));
            assertThat(wait.blockerPid()).isEqualTo(holderPid);
            assertThat(count(jdbc, "rooms")).isEqualTo(existingRoom ? 1 : 0);
            if (existingRoom) {
              assertThat(
                      jdbc.sql("select status from rooms where id = ?")
                          .param(oldRoom)
                          .query(String.class)
                          .single())
                  .isEqualTo("OPEN");
            }
            assertThat(count(jdbc, "games")).isZero();
            assertThat(count(jdbc, "choices")).isZero();
            assertThat(count(jdbc, "publication_idempotency")).isZero();
          } catch (Exception exception) {
            throw new AssertionError(exception);
          }
        });
  }

  @Test
  @DisplayName("S-02: 실제 접수 중인 기존 방은 새 출제를 막고 선점도 롤백한다")
  void rejectsActualOpenRoom() throws SQLException {
    POSTGRES.inMigratedSchema(
        "nkcreate-service-open-room",
        (context, pool) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Fixture fixture = fixture(jdbc);
          insertRoom(jdbc, fixture, "OPEN", "NOT_READY", "now()", "now() + interval '24 hours'");
          PublicationInput input = input(fixture);

          assertThatThrownBy(
                  () -> service(context).publish(fixture.userUuid(), "open-room-key", input))
              .isInstanceOf(DomainException.class)
              .extracting(error -> ((DomainException) error).getErrorCode())
              .isEqualTo(PublicationErrorCode.OPEN_ROOM_EXISTS);
          assertThat(count(jdbc, "publication_idempotency")).isZero();
          assertThat(count(jdbc, "rooms")).isOne();
        });
  }

  @Test
  @DisplayName("S-03: 만료된 OPEN 방은 CLOSED로 전이하고 시각·결과를 보존한 뒤 새 방을 공개한다")
  void closesExpiredOpenRoomBeforeNewPublication() throws SQLException {
    POSTGRES.inMigratedSchema(
        "nkcreate-service-expired-room",
        (context, pool) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Fixture fixture = fixture(jdbc);
          long oldRoom =
              insertRoom(
                  jdbc,
                  fixture,
                  "OPEN",
                  "NOT_READY",
                  "now() - interval '25 hours'",
                  "now() - interval '1 hour'");
          var before =
              jdbc.sql("select opened_at, closes_at, result_state from rooms where id = ?")
                  .param(oldRoom)
                  .query()
                  .singleRow();

          OpenRoomService.Result created =
              service(context).publish(fixture.userUuid(), "expired-room-key", input(fixture));

          var after =
              jdbc.sql("select status, opened_at, closes_at, result_state from rooms where id = ?")
                  .param(oldRoom)
                  .query()
                  .singleRow();
          assertThat(after.get("status")).isEqualTo("CLOSED");
          assertThat(after.get("opened_at")).isEqualTo(before.get("opened_at"));
          assertThat(after.get("closes_at")).isEqualTo(before.get("closes_at"));
          assertThat(after.get("result_state")).isEqualTo(before.get("result_state"));
          assertThat(created.roomId()).isNotEqualTo(oldRoom);
          assertThat(count(jdbc, "rooms")).isEqualTo(2);
        });
  }

  @Test
  @DisplayName("S-04: 완료 UPDATE 0건이면 새 행·선점·기존 만료 방 CLOSED 전이가 함께 롤백된다")
  void rollsBackExpiredRoomTransitionWhenClaimCompletionDoesNotUpdate() throws SQLException {
    POSTGRES.inMigratedSchema(
        "nkcreate-service-completion-rollback",
        (context, pool) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Fixture fixture = fixture(jdbc);
          long oldRoom =
              insertRoom(
                  jdbc,
                  fixture,
                  "OPEN",
                  "NOT_READY",
                  "now() - interval '25 hours'",
                  "now() - interval '1 hour'");
          jdbc.sql(
                  """
                  create function suppress_publication_completion() returns trigger language plpgsql as $$
                  begin
                    if new.game_id is not null then return null; end if;
                    return new;
                  end $$;
                  create trigger suppress_publication_completion before update on publication_idempotency
                  for each row execute function suppress_publication_completion()
                  """)
              .update();

          assertThatThrownBy(() ->
                  service(context)
                      .publish(fixture.userUuid(), "completion-zero-key", input(fixture)))
              .isInstanceOf(DomainException.class)
              .extracting(error -> ((DomainException) error).getErrorCode())
              .isEqualTo(PublicationErrorCode.PUBLICATION_CLAIM_COMPLETION_FAILED);
          assertThat(
                  jdbc.sql("select status from rooms where id = ?")
                      .param(oldRoom)
                      .query(String.class)
                      .single())
              .isEqualTo("OPEN");
          assertThat(count(jdbc, "rooms")).isOne();
          assertThat(count(jdbc, "games")).isZero();
          assertThat(count(jdbc, "choices")).isZero();
          assertThat(count(jdbc, "publication_idempotency")).isZero();
        });
  }

  @Test
  @DisplayName("S-05: 보기 조립 DB 실패면 기존 만료 방 전이와 선점도 남기지 않는다")
  void rollsBackExpiredRoomTransitionWhenChoiceAssemblyFails() throws SQLException {
    POSTGRES.inMigratedSchema(
        "nkcreate-service-choice-rollback",
        (context, pool) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Fixture fixture = fixture(jdbc);
          long oldRoom =
              insertRoom(
                  jdbc,
                  fixture,
                  "OPEN",
                  "NOT_READY",
                  "now() - interval '25 hours'",
                  "now() - interval '1 hour'");
          jdbc.sql(
                  """
                  create function reject_publication_choice() returns trigger language plpgsql as $$
                  begin raise exception 'choice assembly failure'; end $$;
                  create trigger reject_publication_choice before insert on choices
                  for each row execute function reject_publication_choice()
                  """)
              .update();

          assertThatThrownBy(() ->
                  service(context)
                      .publish(fixture.userUuid(), "choice-failure-key", input(fixture)))
              .isInstanceOf(RuntimeException.class)
              .hasStackTraceContaining("choice assembly failure");
          assertThat(
                  jdbc.sql("select status from rooms where id = ?")
                      .param(oldRoom)
                      .query(String.class)
                      .single())
              .isEqualTo("OPEN");
          assertThat(count(jdbc, "rooms")).isOne();
          assertThat(count(jdbc, "games")).isZero();
          assertThat(count(jdbc, "choices")).isZero();
          assertThat(count(jdbc, "publication_idempotency")).isZero();
        });
  }

  @Test
  @DisplayName("S-06: OPEN 방의 공개 시각 쌍이 불완전하면 입력 오류가 아닌 무결성 실패로 롤백한다")
  void rejectsIncompleteOpenRoomTimePairAsIntegrityFailure() throws SQLException {
    POSTGRES.inMigratedSchema(
        "nkcreate-service-incomplete-room",
        (context, pool) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Fixture fixture = fixture(jdbc);
          insertRoom(jdbc, fixture, "OPEN", "NOT_READY", "null", "null");

          assertThatThrownBy(() ->
                  service(context)
                      .publish(fixture.userUuid(), "incomplete-room-key", input(fixture)))
              .isInstanceOf(DomainException.class)
              .extracting(error -> ((DomainException) error).getErrorCode())
              .isEqualTo(PublicationErrorCode.PUBLICATION_CLAIM_STATE_INVALID);
          assertThat(count(jdbc, "publication_idempotency")).isZero();
          assertThat(count(jdbc, "games")).isZero();
        });
  }

  @Test
  @DisplayName("S-07: 만료 세대 교체 뒤 신규 공개가 막히면 이전 claim·입력·게임·만료가 복원된다")
  void restoresExpiredGenerationWhenNewPublicationRollsBack() throws SQLException {
    POSTGRES.inMigratedSchema(
        "nkcreate-service-expired-generation-rollback",
        (context, pool) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Fixture fixture = fixture(jdbc);
          OpenRoomService service = service(context);
          OpenRoomService.Result original =
              service.publish(fixture.userUuid(), "replacement-key", input(fixture));
          jdbc.sql(
                  "update publication_idempotency set record_expires_at = now() - interval '1 microsecond' where request_key = ?")
              .param("replacement-key")
              .update();
          var before =
              jdbc.sql(
                      "select claim_id, normalized_input::text, game_id, record_expires_at from publication_idempotency where request_key = ?")
                  .param("replacement-key")
                  .query()
                  .singleRow();
          long otherReward =
              jdbc.sql(
                      "insert into reward_presets(title, description) values ('other', 'other') returning id")
                  .query(Long.class)
                  .single();
          PublicationInput changed =
              new PublicationInput(
                  PublicationInput.Kind.NK_CREATE,
                  null,
                  otherReward,
                  fixture.questionId(),
                  fixture.choiceIds(),
                  fixture.choiceIds().get(1));

          assertThatThrownBy(() -> service.publish(fixture.userUuid(), "replacement-key", changed))
              .isInstanceOf(DomainException.class)
              .extracting(error -> ((DomainException) error).getErrorCode())
              .isEqualTo(PublicationErrorCode.OPEN_ROOM_EXISTS);
          var after =
              jdbc.sql(
                      "select claim_id, normalized_input::text, game_id, record_expires_at from publication_idempotency where request_key = ?")
                  .param("replacement-key")
                  .query()
                  .singleRow();
          assertThat(after).isEqualTo(before);
          assertThat(count(jdbc, "games")).isOne();
          assertThat(jdbc.sql("select id from games").query(Long.class).single())
              .isEqualTo(original.gameId());
        });
  }

  @Test
  @DisplayName("S-08: 없는 보기 원본은 404용 PRESET_NOT_FOUND이고 다른 질문 보기는 입력 오류다")
  void distinguishesMissingAndForeignChoiceSources() throws SQLException {
    POSTGRES.inMigratedSchema(
        "nkcreate-service-choice-source-errors",
        (context, pool) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Fixture fixture = fixture(jdbc);
          List<Long> missing =
              List.of(
                  fixture.choiceIds().get(0),
                  fixture.choiceIds().get(1),
                  fixture.choiceIds().get(2),
                  999999L);
          PublicationInput missingInput =
              new PublicationInput(
                  PublicationInput.Kind.NK_CREATE,
                  null,
                  fixture.rewardId(),
                  fixture.questionId(),
                  missing,
                  fixture.choiceIds().get(1));
          assertThatThrownBy(() ->
                  service(context).publish(fixture.userUuid(), "missing-choice-key", missingInput))
              .isInstanceOf(DomainException.class)
              .extracting(error -> ((DomainException) error).getErrorCode())
              .isEqualTo(PublicationErrorCode.PRESET_NOT_FOUND);

          long otherQuestion =
              jdbc.sql("insert into question_presets(question_text) values ('other') returning id")
                  .query(Long.class)
                  .single();
          long foreign =
              jdbc.sql(
                      "insert into question_preset_choices(question_preset_id, choice_text) values (?, 'foreign') returning id")
                  .param(otherQuestion)
                  .query(Long.class)
                  .single();
          List<Long> foreignIds =
              List.of(
                  fixture.choiceIds().get(0),
                  fixture.choiceIds().get(1),
                  fixture.choiceIds().get(2),
                  foreign);
          PublicationInput foreignInput =
              new PublicationInput(
                  PublicationInput.Kind.NK_CREATE,
                  null,
                  fixture.rewardId(),
                  fixture.questionId(),
                  foreignIds,
                  fixture.choiceIds().get(1));
          assertThatThrownBy(() ->
                  service(context).publish(fixture.userUuid(), "foreign-choice-key", foreignInput))
              .isInstanceOf(DomainException.class)
              .extracting(error -> ((DomainException) error).getErrorCode())
              .isEqualTo(PublicationErrorCode.INVALID_PUBLICATION_INPUT);
          assertThat(count(jdbc, "publication_idempotency")).isZero();
        });
  }

  @Test
  @DisplayName("S-09: 실제 Room 잠금 대기 후 제어 시각을 마감 정각으로 전진하면 기존 방을 닫고 공개한다")
  void closesAfterRealRoomLockWaitCrossesDeadline() throws Exception {
    POSTGRES.inMigratedSchema(
        "nkcreate-service-room-lock-deadline",
        (context, pool) -> {
          try {
            JdbcClient jdbc = context.getBean(JdbcClient.class);
            Fixture fixture = fixture(jdbc);
            Instant closesAt = Instant.parse("2040-01-02T00:00:00Z");
            AtomicReference<Instant> now = new AtomicReference<>(closesAt.minusNanos(1_000));
            DbTimeSource clock =
                new DbTimeSource(jdbc) {
                  @Override
                  public Instant readCurrentTime() {
                    return now.get();
                  }
                };
            long oldRoom = insertRoom(jdbc, fixture, "OPEN", "NOT_READY", "null", "null");
            jdbc.sql("update rooms set opened_at = ?, closes_at = ? where id = ?")
                .params(
                    closesAt.minus(Duration.ofHours(24)).atOffset(ZoneOffset.UTC),
                    closesAt.atOffset(ZoneOffset.UTC),
                    oldRoom)
                .update();
            OpenRoomService service = service(context, clock);
            try (Connection holder = pool.getConnection();
                ExecutorService workers = Executors.newSingleThreadExecutor()) {
              holder.setAutoCommit(false);
              int holderPid = backendPid(holder);
              try (var statement =
                  holder.prepareStatement("select id from rooms where id = ? for update")) {
                statement.setLong(1, oldRoom);
                try (ResultSet ignored = statement.executeQuery()) {
                  assertThat(ignored.next()).isTrue();
                }
              }
              Future<OpenRoomService.Result> pending =
                  workers.submit(() ->
                      service.publish(
                          fixture.userUuid(), "room-lock-deadline-key", input(fixture)));
              WaitEvidence wait = awaitRoomWait(jdbc, holderPid, pending);
              now.set(closesAt);
              holder.commit();
              OpenRoomService.Result created = pending.get(10, TimeUnit.SECONDS);

              assertThat(wait.blockerPid()).isEqualTo(holderPid);
              assertThat(wait.waitEventType()).isEqualTo("Lock");
              assertThat(
                      jdbc.sql("select status from rooms where id = ?")
                          .param(oldRoom)
                          .query(String.class)
                          .single())
                  .isEqualTo("CLOSED");
              assertThat(created.roomId()).isNotEqualTo(oldRoom);
              assertThat(count(jdbc, "rooms")).isEqualTo(2);
              System.out.printf(
                  "S-09 holderPid=%d waiterPid=%d blocker=%d wait=%s controlledNow=%s closesAt=%s oldRoom=%d newRoom=%d%n",
                  holderPid,
                  wait.waiterPid(),
                  wait.blockerPid(),
                  wait.waitEventType(),
                  now.get(),
                  closesAt,
                  oldRoom,
                  created.roomId());
            }
          } catch (Exception exception) {
            throw new RuntimeException(exception);
          }
        });
  }

  @Test
  @DisplayName("S-10: 서로 다른 키의 같은 방장 출제는 실제 User lock 뒤 하나만 공개되고 패자는 선점을 롤백한다")
  void differentKeysForSameHostRaceAtUserLock() throws Exception {
    POSTGRES.inMigratedSchema(
        "nkcreate-service-user-lock-race",
        (context, pool) -> {
          try {
            JdbcClient jdbc = context.getBean(JdbcClient.class);
            Fixture fixture = fixture(jdbc);
            OpenRoomService service = service(context);
            long userId =
                jdbc.sql("select id from users where uuid = ?")
                    .param(fixture.userUuid())
                    .query(Long.class)
                    .single();
            try (Connection holder = pool.getConnection();
                ExecutorService workers = Executors.newFixedThreadPool(2)) {
              holder.setAutoCommit(false);
              int holderPid = backendPid(holder);
              try (var statement =
                  holder.prepareStatement("select id from users where id = ? for no key update")) {
                statement.setLong(1, userId);
                try (ResultSet ignored = statement.executeQuery()) {
                  assertThat(ignored.next()).isTrue();
                }
              }
              Future<Object> first = workers.submit(() -> invoke(service, fixture, "user-race-a"));
              Future<Object> second = workers.submit(() -> invoke(service, fixture, "user-race-b"));
              List<WaitEvidence> waits = awaitUserWaiters(jdbc, holderPid, first, second);
              holder.commit();
              Object firstResult = first.get(10, TimeUnit.SECONDS);
              Object secondResult = second.get(10, TimeUnit.SECONDS);

              assertThat(waits).hasSize(2);
              assertThat(waits)
                  .allSatisfy(wait -> {
                    assertThat(wait.blockerPid()).isEqualTo(holderPid);
                    assertThat(wait.waitEventType()).isEqualTo("Lock");
                  });
              List<Object> results = List.of(firstResult, secondResult);
              assertThat(results.stream().filter(OpenRoomService.Result.class::isInstance).count())
                  .isOne();
              assertThat(
                      results.stream()
                          .filter(DomainException.class::isInstance)
                          .map(DomainException.class::cast)
                          .map(DomainException::getErrorCode))
                  .containsExactly(PublicationErrorCode.OPEN_ROOM_EXISTS);
              assertThat(count(jdbc, "rooms")).isOne();
              assertThat(count(jdbc, "games")).isOne();
              assertThat(count(jdbc, "choices")).isEqualTo(4);
              assertThat(count(jdbc, "publication_idempotency")).isOne();
              System.out.printf(
                  "S-10 holderPid=%d waiterPids=%s%n",
                  holderPid, waits.stream().map(WaitEvidence::waiterPid).toList());
            }
          } catch (Exception exception) {
            throw new RuntimeException(exception);
          }
        });
  }

  @Test
  @DisplayName("S-11: 같은 키·같은 입력은 실제 미완료 선점 대기 뒤 하나만 공개하고 같은 결과를 복구한다")
  void sameKeyRaceRecoversSinglePublication() throws Exception {
    POSTGRES.inMigratedSchema(
        "nkcreate-service-same-key-race",
        (context, pool) -> {
          try {
            JdbcClient jdbc = context.getBean(JdbcClient.class);
            Fixture fixture = fixture(jdbc);
            OpenRoomService service = service(context);
            try (Connection holder = pool.getConnection();
                ExecutorService workers = Executors.newFixedThreadPool(2)) {
              holder.setAutoCommit(false);
              int holderPid = backendPid(holder);
              try (var statement =
                  holder.prepareStatement(
                      "select id from users where uuid = ? for no key update")) {
                statement.setObject(1, fixture.userUuid());
                try (ResultSet result = statement.executeQuery()) {
                  assertThat(result.next()).isTrue();
                }
              }
              Future<OpenRoomService.Result> first =
                  workers.submit(
                      () -> service.publish(fixture.userUuid(), "same-key-race", input(fixture)));
              WaitEvidence firstWait = awaitUserWaiters(jdbc, holderPid, first).getFirst();
              Future<OpenRoomService.Result> second =
                  workers.submit(
                      () -> service.publish(fixture.userUuid(), "same-key-race", input(fixture)));
              WaitEvidence secondWait =
                  awaitAnyPublicationWait(jdbc, firstWait.waiterPid(), second);
              holder.commit();
              OpenRoomService.Result firstResult = first.get(10, TimeUnit.SECONDS);
              OpenRoomService.Result secondResult = second.get(10, TimeUnit.SECONDS);

              assertThat(firstWait.waiterPid()).isNotEqualTo(secondWait.waiterPid());
              assertThat(firstWait.blockerPid()).isEqualTo(holderPid);
              assertThat(secondWait.blockerPid()).isEqualTo(firstWait.waiterPid());
              assertThat(firstResult.created()).isTrue();
              assertThat(secondResult.created()).isFalse();
              assertThat(secondResult.roomId()).isEqualTo(firstResult.roomId());
              assertThat(secondResult.gameId()).isEqualTo(firstResult.gameId());
              assertThat(secondResult.openedAt()).isEqualTo(firstResult.openedAt());
              assertThat(secondResult.closesAt()).isEqualTo(firstResult.closesAt());
              assertThat(count(jdbc, "rooms")).isOne();
              assertThat(count(jdbc, "games")).isOne();
              assertThat(count(jdbc, "choices")).isEqualTo(4);
              assertThat(count(jdbc, "publication_idempotency")).isOne();
              System.out.printf(
                  "S-11 holderPid=%d firstPid=%d secondPid=%d firstBlocker=%d secondBlocker=%d%n",
                  holderPid,
                  firstWait.waiterPid(),
                  secondWait.waiterPid(),
                  firstWait.blockerPid(),
                  secondWait.blockerPid());
            }
          } catch (Exception exception) {
            throw new RuntimeException(exception);
          }
        });
  }

  private static OpenRoomService service(ApplicationContext context) {
    return service(context, context.getBean(DbTimeSource.class));
  }

  private static OpenRoomService service(ApplicationContext context, DbTimeSource clock) {
    EntityManagerFactory entityManagerFactory = context.getBean(EntityManagerFactory.class);
    QuestionPresetChoiceRepository choices =
        new JpaRepositoryFactory(
                SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory))
            .getRepository(QuestionPresetChoiceRepository.class);
    return new OpenRoomService(
        context.getBean(PlatformTransactionManager.class),
        context.getBean(PublicationIdempotencyRepository.class),
        context.getBean(UserRepository.class),
        context.getBean(RoomRepository.class),
        context.getBean(GameRepository.class),
        context.getBean(ChoiceRepository.class),
        context.getBean(QuestionPresetRepository.class),
        choices,
        context.getBean(RewardPresetRepository.class),
        SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory),
        clock,
        new PublicationProperties(Duration.ofMinutes(5)),
        new PublicationCommitObserver());
  }

  private static Fixture fixture(JdbcClient jdbc) {
    UserRow user =
        jdbc.sql(
                """
                insert into users(uuid, display_name, status, created_at, updated_at)
                values (?, 'service-host', 'ACTIVE', now(), now())
                returning id, uuid
                """)
            .param(UUID.randomUUID())
            .query(
                (row, rowNum) -> new UserRow(row.getLong("id"), row.getObject("uuid", UUID.class)))
            .single();
    long rewardId =
        jdbc.sql(
                "insert into reward_presets(title, description) values ('reward', 'reward') returning id")
            .query(Long.class)
            .single();
    long questionId =
        jdbc.sql("insert into question_presets(question_text) values ('question') returning id")
            .query(Long.class)
            .single();
    List<Long> choiceIds =
        jdbc.sql(
                """
                insert into question_preset_choices(question_preset_id, choice_text)
                select ?, 'choice-' || generate_series(1, 4)
                returning id
                """)
            .param(questionId)
            .query(Long.class)
            .list();
    return new Fixture(user.uuid(), rewardId, questionId, choiceIds);
  }

  private static PublicationInput input(Fixture fixture) {
    return new PublicationInput(
        PublicationInput.Kind.NK_CREATE,
        null,
        fixture.rewardId(),
        fixture.questionId(),
        fixture.choiceIds().reversed(),
        fixture.choiceIds().get(1));
  }

  private static long insertRoom(
      JdbcClient jdbc,
      Fixture fixture,
      String status,
      String resultState,
      String openedAt,
      String closesAt) {
    return jdbc.sql(
            """
            insert into rooms(host_user_id, selected_reward_preset_id, status, result_state, opened_at, closes_at, created_at)
            select id, ?, ?, ?, %s, %s, now() from users where uuid = ?
            returning id
            """
                .formatted(openedAt, closesAt))
        .params(fixture.rewardId(), status, resultState, fixture.userUuid())
        .query(Long.class)
        .single();
  }

  private static int backendPid(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        ResultSet result = statement.executeQuery("select pg_backend_pid()")) {
      assertThat(result.next()).isTrue();
      return result.getInt(1);
    }
  }

  private static WaitEvidence awaitRoomWait(JdbcClient jdbc, int holderPid, Future<?> pending) {
    long deadline = System.nanoTime() + Duration.ofSeconds(7).toNanos();
    while (System.nanoTime() < deadline) {
      WaitEvidence evidence =
          jdbc.sql(
                  """
                  select pid, coalesce(array_to_string(pg_blocking_pids(pid), ','), ''), coalesce(wait_event_type, ''), query
                  from pg_stat_activity
                  where pid <> pg_backend_pid()
                    and query ilike '%from rooms%for update%'
                    and :holderPid = any(pg_blocking_pids(pid))
                  order by query_start desc limit 1
                  """)
              .param("holderPid", holderPid)
              .query((row, rowNum) ->
                  new WaitEvidence(
                      row.getInt(1),
                      firstBlockingPid(row.getString(2)),
                      row.getString(3),
                      row.getString(4)))
              .optional()
              .orElse(null);
      if (evidence != null) {
        return evidence;
      }
      if (pending.isDone()) {
        throw new AssertionError("service finished before actual Room lock wait");
      }
      LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
    }
    throw new AssertionError("did not observe pg_blocking_pids Room wait");
  }

  private static List<WaitEvidence> awaitUserWaiters(
      JdbcClient jdbc, int holderPid, Future<?>... pending) {
    long deadline = System.nanoTime() + Duration.ofSeconds(7).toNanos();
    while (System.nanoTime() < deadline) {
      List<WaitEvidence> waits =
          jdbc.sql(
                  """
                  select pid, coalesce(array_to_string(pg_blocking_pids(pid), ','), ''), coalesce(wait_event_type, ''), query
                  from pg_stat_activity
                  where pid <> pg_backend_pid()
                    and query ilike '%from users%for no key update%'
                    and :holderPid = any(pg_blocking_pids(pid))
                  order by pid
                  """)
              .param("holderPid", holderPid)
              .query((row, rowNum) ->
                  new WaitEvidence(
                      row.getInt(1),
                      firstBlockingPid(row.getString(2)),
                      row.getString(3),
                      row.getString(4)))
              .list();
      if (waits.size() == pending.length) {
        return waits;
      }
      if (Arrays.stream(pending).anyMatch(Future::isDone)) {
        throw new AssertionError("service completed before both User lock waits were observed");
      }
      LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
    }
    throw new AssertionError("did not observe both User lock waits");
  }

  private static WaitEvidence awaitAnyPublicationWait(
      JdbcClient jdbc, int blockerPid, Future<?> pending) {
    long deadline = System.nanoTime() + Duration.ofSeconds(7).toNanos();
    while (System.nanoTime() < deadline) {
      WaitEvidence evidence =
          jdbc.sql(
                  """
                  select pid, coalesce(array_to_string(pg_blocking_pids(pid), ','), ''), coalesce(wait_event_type, ''), query
                  from pg_stat_activity
                  where pid <> pg_backend_pid()
                    and query ilike '%publication_idempotency%'
                    and :blockerPid = any(pg_blocking_pids(pid))
                  order by query_start desc limit 1
                  """)
              .param("blockerPid", blockerPid)
              .query((row, rowNum) ->
                  new WaitEvidence(
                      row.getInt(1),
                      firstBlockingPid(row.getString(2)),
                      row.getString(3),
                      row.getString(4)))
              .optional()
              .orElse(null);
      if (evidence != null) {
        return evidence;
      }
      if (pending.isDone()) {
        throw new AssertionError("service completed before publication claim wait was observed");
      }
      LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
    }
    throw new AssertionError("did not observe publication claim wait");
  }

  private static Object invoke(OpenRoomService service, Fixture fixture, String key) {
    try {
      return service.publish(fixture.userUuid(), key, input(fixture));
    } catch (DomainException exception) {
      return exception;
    }
  }

  private static int firstBlockingPid(String blockerPids) {
    String[] pids = blockerPids.split(",");
    return Integer.parseInt(pids[pids.length - 1]);
  }

  private static long count(JdbcClient jdbc, String table) {
    return jdbc.sql("select count(*) from " + table).query(Long.class).single();
  }

  private record Fixture(UUID userUuid, long rewardId, long questionId, List<Long> choiceIds) {}

  private record UserRow(long id, UUID uuid) {}

  private record WaitEvidence(int waiterPid, int blockerPid, String waitEventType, String query) {}
}

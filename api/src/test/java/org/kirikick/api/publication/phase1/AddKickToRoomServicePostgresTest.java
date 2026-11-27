package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.common.infra.DbTimeSource;
import org.kirikick.api.config.PublicationProperties;
import org.kirikick.api.game.repository.ChoiceRepository;
import org.kirikick.api.game.repository.GameRepository;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.domain.PublicationKind;
import org.kirikick.api.publication.domain.PublicationRequestKey;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.kirikick.api.publication.infra.PublicationCommitObserver;
import org.kirikick.api.publication.repository.PublicationIdempotencyRepository;
import org.kirikick.api.publication.service.AddKickToRoomService;
import org.kirikick.api.publication.service.PublicationResult;
import org.kirikick.api.question.repository.QuestionPresetChoiceRepository;
import org.kirikick.api.question.repository.QuestionPresetRepository;
import org.kirikick.api.room.repository.RoomRepository;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AddKickToRoomServicePostgresTest {
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
  @DisplayName("S-76-01: 공개된 방에 첫 요청은 생성하고 같은 의미 입력은 원래 결과로 복구한다")
  void createsAndReplaysKickWithoutChangingRoom() throws Exception {
    POSTGRES.inMigratedSchema(
        "nkkickadd-service-create-replay",
        (context, pool) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Fixture fixture = fixture(jdbc, "OPEN", "NOT_READY");
          PublicationInput input = input(fixture);

          PublicationResult created =
              service(context)
                  .add(fixture.userUuid(), new PublicationRequestKey("kick-create"), input);
          PublicationResult replayed =
              service(context)
                  .add(
                      fixture.userUuid(),
                      new PublicationRequestKey("kick-create"),
                      new PublicationInput(
                          PublicationKind.NK_KICK_ADD,
                          fixture.roomId(),
                          null,
                          fixture.questionId(),
                          input.choiceIds().reversed(),
                          input.correctChoiceId()));

          assertThat(created.created()).isTrue();
          assertThat(replayed.created()).isFalse();
          assertThat(replayed)
              .isEqualTo(
                  new PublicationResult(
                      false,
                      created.roomId(),
                      created.gameId(),
                      created.openedAt(),
                      created.closesAt()));
          assertThat(count(jdbc, "games")).isOne();
          assertThat(count(jdbc, "choices")).isEqualTo(4);
          assertThat(count(jdbc, "publication_idempotency")).isOne();
          assertThat(
                  jdbc.sql("select status || ':' || result_state from rooms where id = :roomId")
                      .param("roomId", fixture.roomId())
                      .query(String.class)
                      .single())
              .isEqualTo("OPEN:NOT_READY");
        });
  }

  @ParameterizedTest(name = "상태={0}")
  @ValueSource(strings = {"CLOSED", "FINAL"})
  @DisplayName("S-76-02: 접수가 닫혔거나 결과가 확정된 방의 신규 요청은 선점 없이 거절한다")
  void rejectsNewKickWhenIntakeIsClosed(String state) throws Exception {
    String status = state.equals("CLOSED") ? "CLOSED" : "OPEN";
    String result = state.equals("FINAL") ? "FINAL" : "NOT_READY";
    POSTGRES.inMigratedSchema(
        "nkkickadd-service-closed-" + state.toLowerCase(),
        (context, pool) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Fixture fixture = fixture(jdbc, status, result);

          assertThatThrownBy(() ->
                  service(context)
                      .add(
                          fixture.userUuid(),
                          new PublicationRequestKey("closed-" + state),
                          input(fixture)))
              .isInstanceOfSatisfying(
                  DomainException.class,
                  error ->
                      assertThat(error.getErrorCode())
                          .isEqualTo(PublicationErrorCode.ROOM_INTAKE_CLOSED));
          assertThat(count(jdbc, "games")).isZero();
          assertThat(count(jdbc, "choices")).isZero();
          assertThat(count(jdbc, "publication_idempotency")).isZero();
        });
  }

  @Test
  @DisplayName("S-76-03: 바깥 트랜잭션이 롤백돼도 REQUIRES_NEW 추가 출제는 공개 결과를 보존한다")
  void preservesAddedKickWhenOuterTransactionRollsBack() throws Exception {
    POSTGRES.inMigratedSchema(
        "nkkickadd-service-requires-new",
        (context, pool) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Fixture fixture = fixture(jdbc, "OPEN", "NOT_READY");
          TransactionTemplate outer =
              new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

          outer.executeWithoutResult(status -> {
            PublicationResult result =
                service(context)
                    .add(
                        fixture.userUuid(),
                        new PublicationRequestKey("requires-new"),
                        input(fixture));
            assertThat(result.created()).isTrue();
            status.setRollbackOnly();
          });

          assertThat(count(jdbc, "games")).isOne();
          assertThat(count(jdbc, "choices")).isEqualTo(4);
          assertThat(count(jdbc, "publication_idempotency")).isOne();
        });
  }

  @ParameterizedTest(name = "publication={0}")
  @ValueSource(strings = {"opened_at = '-infinity'", "closes_at = 'infinity'"})
  @DisplayName("S-76-04: 비유한 공개 시각은 Instant 변환 전에 대상 없음으로 거절한다")
  void rejectsNonFinitePublishedRoomBeforeInstantConversion(String update) throws Exception {
    POSTGRES.inMigratedSchema(
        "nkkickadd-service-nonfinite-" + Integer.toUnsignedString(update.hashCode()),
        (context, pool) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Fixture fixture = fixture(jdbc, "OPEN", "NOT_READY");
          jdbc.sql("update rooms set " + update + " where id = :roomId")
              .param("roomId", fixture.roomId())
              .update();

          assertThatThrownBy(() ->
                  service(context)
                      .add(
                          fixture.userUuid(),
                          new PublicationRequestKey("nonfinite"),
                          input(fixture)))
              .isInstanceOfSatisfying(
                  DomainException.class,
                  error ->
                      assertThat(error.getErrorCode())
                          .isEqualTo(PublicationErrorCode.ROOM_NOT_FOUND));
          assertThat(count(jdbc, "games")).isZero();
          assertThat(count(jdbc, "publication_idempotency")).isZero();
        });
  }

  @Test
  @DisplayName("R-04: Choice 조립 실패는 기존 방과 새 멱등 세대를 함께 롤백한다")
  void rollsBackClaimAndAssemblyWhenChoiceInsertFails() throws Exception {
    POSTGRES.inMigratedSchema(
        "nkkickadd-service-choice-rollback",
        (context, pool) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Fixture fixture = fixture(jdbc, "OPEN", "NOT_READY");
          String roomBefore = roomSnapshot(jdbc, fixture.roomId());
          jdbc.sql(
                  """
                  create function reject_kick_choice() returns trigger language plpgsql as $$
                  begin
                    raise exception 'choice assembly failure';
                  end;
                  $$
                  """)
              .update();
          jdbc.sql(
                  """
                  create trigger reject_kick_choice
                  before insert on choices
                  for each row execute function reject_kick_choice()
                  """)
              .update();

          assertThatThrownBy(() ->
                  service(context)
                      .add(
                          fixture.userUuid(),
                          new PublicationRequestKey("choice-rollback"),
                          input(fixture)))
              .isInstanceOf(RuntimeException.class)
              .hasStackTraceContaining("choice assembly failure");
          assertThat(roomSnapshot(jdbc, fixture.roomId())).isEqualTo(roomBefore);
          assertThat(count(jdbc, "games")).isZero();
          assertThat(count(jdbc, "choices")).isZero();
          assertThat(count(jdbc, "publication_idempotency")).isZero();
        });
  }

  @Test
  @DisplayName("R-04: 완료 UPDATE 0건은 새 Game과 멱등 선점을 함께 롤백한다")
  void rollsBackClaimAndAssemblyWhenCompletionUpdatesNothing() throws Exception {
    POSTGRES.inMigratedSchema(
        "nkkickadd-service-completion-rollback",
        (context, pool) -> {
          JdbcClient jdbc = context.getBean(JdbcClient.class);
          Fixture fixture = fixture(jdbc, "OPEN", "NOT_READY");
          String roomBefore = roomSnapshot(jdbc, fixture.roomId());
          jdbc.sql(
                  """
                  create function suppress_kick_completion() returns trigger language plpgsql as $$
                  begin
                    return null;
                  end;
                  $$
                  """)
              .update();
          jdbc.sql(
                  """
                  create trigger suppress_kick_completion
                  before update of game_id on publication_idempotency
                  for each row execute function suppress_kick_completion()
                  """)
              .update();

          assertThatThrownBy(() ->
                  service(context)
                      .add(
                          fixture.userUuid(),
                          new PublicationRequestKey("completion-rollback"),
                          input(fixture)))
              .isInstanceOfSatisfying(
                  DomainException.class,
                  error ->
                      assertThat(error.getErrorCode())
                          .isEqualTo(PublicationErrorCode.PUBLICATION_CLAIM_COMPLETION_FAILED));
          assertThat(roomSnapshot(jdbc, fixture.roomId())).isEqualTo(roomBefore);
          assertThat(count(jdbc, "games")).isZero();
          assertThat(count(jdbc, "choices")).isZero();
          assertThat(count(jdbc, "publication_idempotency")).isZero();
        });
  }

  private static AddKickToRoomService service(ApplicationContext context) {
    EntityManagerFactory entityManagerFactory = context.getBean(EntityManagerFactory.class);
    QuestionPresetChoiceRepository questionChoices =
        new JpaRepositoryFactory(
                SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory))
            .getRepository(QuestionPresetChoiceRepository.class);
    return new AddKickToRoomService(
        context.getBean(PlatformTransactionManager.class),
        context.getBean(PublicationIdempotencyRepository.class),
        context.getBean(UserRepository.class),
        context.getBean(RoomRepository.class),
        context.getBean(GameRepository.class),
        context.getBean(ChoiceRepository.class),
        context.getBean(QuestionPresetRepository.class),
        questionChoices,
        SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory),
        context.getBean(DbTimeSource.class),
        new PublicationProperties(Duration.ofMinutes(5)),
        new PublicationCommitObserver());
  }

  private static Fixture fixture(JdbcClient jdbc, String status, String resultState) {
    UUID userUuid = UUID.randomUUID();
    long userId =
        jdbc.sql(
                """
                insert into users(uuid, display_name, status, created_at, updated_at)
                values (:uuid, 'kick-host', 'ACTIVE', now(), now())
                returning id
                """)
            .param("uuid", userUuid)
            .query(Long.class)
            .single();
    long rewardId =
        jdbc.sql(
                "insert into reward_presets(title, description) values ('reward', 'reward') returning id")
            .query(Long.class)
            .single();
    long roomId =
        jdbc.sql(
                """
                insert into rooms(host_user_id, selected_reward_preset_id, status, result_state,
                                  opened_at, closes_at, created_at)
                values (:userId, :rewardId, :status, :resultState,
                        now() - interval '1 hour', now() + interval '23 hours', now())
                returning id
                """)
            .param("userId", userId)
            .param("rewardId", rewardId)
            .param("status", status)
            .param("resultState", resultState)
            .query(Long.class)
            .single();
    long questionId =
        jdbc.sql("insert into question_presets(question_text) values ('new question') returning id")
            .query(Long.class)
            .single();
    List<Long> choiceIds =
        jdbc.sql(
                """
                insert into question_preset_choices(question_preset_id, choice_text)
                select :questionId, 'choice-' || generate_series(1, 4)
                returning id
                """)
            .param("questionId", questionId)
            .query(Long.class)
            .list();
    return new Fixture(userUuid, roomId, questionId, choiceIds);
  }

  private static PublicationInput input(Fixture fixture) {
    return new PublicationInput(
        PublicationKind.NK_KICK_ADD,
        fixture.roomId(),
        null,
        fixture.questionId(),
        fixture.choiceIds(),
        fixture.choiceIds().get(1));
  }

  private static long count(JdbcClient jdbc, String table) {
    return jdbc.sql("select count(*) from " + table).query(Long.class).single();
  }

  private static String roomSnapshot(JdbcClient jdbc, long roomId) {
    return jdbc.sql(
            """
            select status || ':' || result_state || ':' || opened_at::text || ':' || closes_at::text
            from rooms where id = :roomId
            """)
        .param("roomId", roomId)
        .query(String.class)
        .single();
  }

  private record Fixture(UUID userUuid, long roomId, long questionId, List<Long> choiceIds) {}
}

package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.FlushEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.common.infra.DbTimeSource;
import org.kirikick.api.game.domain.Choice;
import org.kirikick.api.game.domain.Game;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.kirikick.api.publication.repository.PublicationIdempotencyRepository;
import org.kirikick.api.question.repository.QuestionPresetChoiceRepository;
import org.kirikick.api.question.repository.QuestionPresetRepository;
import org.kirikick.api.reward.repository.RewardPresetRepository;
import org.kirikick.api.room.domain.Room;
import org.kirikick.api.room.repository.RoomRepository;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

/** Locks the publication CORS exception to the POST /games boundary. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({
  OpenRoomHttpPostgresTest.DomainEntities.class,
  OpenRoomHttpAcceptancePostgresTest.FixedClockConfig.class
})
class OpenRoomHttpAcceptancePostgresTest {

  @LocalServerPort private int port;
  @Autowired private ObjectMapper json;
  @Autowired private JdbcClient jdbc;
  @MockitoSpyBean private UserRepository users;

  @MockitoSpyBean private RoomRepository rooms;

  @MockitoSpyBean private QuestionPresetRepository questions;

  @MockitoSpyBean private QuestionPresetChoiceRepository choices;

  @MockitoSpyBean private RewardPresetRepository rewards;
  @Autowired private LoginSessionService logins;
  @Autowired private FixedDbTimeSource clock;
  @Autowired private AssemblyFaultProbe assemblyFaults;
  @MockitoSpyBean private PublicationIdempotencyRepository records;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    registry.add("spring.datasource.url", OpenRoomHttpAcceptancePostgresTest::nk75JdbcUrl);
    registry.add("spring.datasource.username", PostgresTestDatabase.container()::getUsername);
    registry.add("spring.datasource.password", PostgresTestDatabase.container()::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.flyway.default-schema", () -> "nk75_http_acceptance");
    registry.add("publication.grace", () -> "PT5M");
  }

  @AfterEach
  void resetTestDoubles() {
    clock.clear();
    reset(records);
  }

  @Test
  @DisplayName("H-07: 없는 보기 원본은 404이고 다른 질문 소속의 실제 보기는 400이며 모두 공개를 남기지 않는다")
  void distinguishesMissingChoiceSourcesFromExistingForeignSources() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    NkCreateHttpFixture.Fixture other = fixture().fixture();
    for (boolean missing : List.of(true, false)) {
      List<Long> choices = new ArrayList<>(fixture.choiceIds());
      choices.set(3, missing ? Long.MAX_VALUE : other.choiceIds().getFirst());
      HttpResponse<String> response =
          fixture()
              .post(
                  fixture,
                  UUID.randomUUID().toString(),
                  choices,
                  choices.getFirst(),
                  fixture.rewardId());
      assertThat(response.statusCode())
          .withFailMessage(response.body())
          .isEqualTo(missing ? 404 : 400);
      assertThat(json.readTree(response.body()).required("code").textValue())
          .isEqualTo(missing ? "PRESET_NOT_FOUND" : "INVALID_PUBLICATION_INPUT");
      assertThat(
              jdbc.sql("select count(*) from rooms where host_user_id=:id")
                  .param("id", fixture.user().getId())
                  .query(Long.class)
                  .single())
          .isZero();
      assertThat(
              jdbc.sql("select count(*) from publication_idempotency where user_id=:id")
                  .param("id", fixture.user().getId())
                  .query(Long.class)
                  .single())
          .isZero();
    }
  }

  @ParameterizedTest(name = "failure={0}")
  @ValueSource(
      strings = {
        "ROOM_BEFORE",
        "GAME_AFTER",
        "CHOICE_1",
        "CHOICE_2",
        "CHOICE_3",
        "CHOICE_4",
        "ANSWER_BEFORE_PUBLISH",
        "PUBLISH_BEFORE_FLUSH",
        "PUBLISH_AFTER_FLUSH"
      })
  @DisplayName("F-01: 각 조립 단계의 실패는 실제 HTTP에서 신규 행과 기존 방 전이를 함께 롤백한다")
  void rollsBackEveryAssemblyFailureAndCompanionRoomTransition(String point) throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    String oldKey = UUID.randomUUID().toString();
    HttpResponse<String> first = fixture().postRaw(fixture, oldKey, true);
    assertThat(first.statusCode()).withFailMessage(first.body()).isEqualTo(201);
    long roomId = Long.parseLong(json.readTree(first.body()).required("roomId").textValue());
    String oldRoom = roomSnapshot(roomId);
    String oldRecord = recordSnapshot(fixture, oldKey);
    clock.set(
        Instant.parse(json.readTree(first.body()).required("closesAt").textValue())
            .plusNanos(1_000));
    AtomicBoolean injected = new AtomicBoolean();
    Runnable fail =
        () -> {
          assertThat(
                  jdbc.sql("select status from rooms where id=:id")
                      .param("id", roomId)
                      .query(String.class)
                      .single())
              .isEqualTo("CLOSED");
          injected.set(true);
          throw new IllegalStateException("synthetic assembly failure: " + point);
        };
    AssemblyFault fault = new AssemblyFault(point, fail);
    clock.beforeRead(fault::beforeClockRead);
    assemblyFaults.active.set(fault);
    String failingKey = UUID.randomUUID().toString();
    try {
      HttpResponse<String> response = fixture().postRaw(fixture, failingKey, true);
      assertInternalError(response);
      assertThat(injected).isTrue();
      assertThat(roomSnapshot(roomId)).isEqualTo(oldRoom);
      assertThat(recordSnapshot(fixture, oldKey)).isEqualTo(oldRecord);
      assertOriginalPublicationOnly(fixture);
    } finally {
      assemblyFaults.active.set(null);
      clock.clear();
    }
  }

  @Test
  @DisplayName("H-11/F-02: 만료 세대 교체 뒤 실제 완료 UPDATE 0은 이전 세대와 OPEN 방을 완전히 복원한다")
  void restoresTheExpiredGenerationAndOldRoomAfterActualCompletionUpdateZero() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    String key = UUID.randomUUID().toString();
    HttpResponse<String> first = fixture().postRaw(fixture, key, true);
    assertThat(first.statusCode()).withFailMessage(first.body()).isEqualTo(201);
    long roomId = Long.parseLong(json.readTree(first.body()).required("roomId").textValue());
    String oldRoom = roomSnapshot(roomId);
    String oldRecord = recordSnapshot(fixture, key);
    clock.set(recordExpiry(fixture, key).plusNanos(1_000));
    AtomicBoolean realGuardFailed = new AtomicBoolean();
    willAnswer(
            invocation -> {
              assertThat(
                      jdbc.sql(
                              "update publication_idempotency set claim_id=:claim where user_id=:user and request_key=:key")
                          .param("claim", UUID.randomUUID())
                          .param("user", fixture.user().getId())
                          .param("key", key)
                          .update())
                  .isEqualTo(1);
              try {
                return invocation.callRealMethod();
              } catch (DomainException failure) {
                assertThat(failure.getErrorCode())
                    .isEqualTo(PublicationErrorCode.PUBLICATION_CLAIM_COMPLETION_FAILED);
                realGuardFailed.set(true);
                throw failure;
              }
            })
        .given(AopTestUtils.<PublicationIdempotencyRepository>getTargetObject(records))
        .completeClaim(any(), anyLong(), any());
    HttpResponse<String> response =
        fixture()
            .post(
                fixture,
                key,
                fixture.choiceIds(),
                fixture.choiceIds().getFirst(),
                fixture.alternateRewardId());
    assertInternalError(response);
    assertThat(realGuardFailed).isTrue();
    assertThat(recordSnapshot(fixture, key)).isEqualTo(oldRecord);
    assertThat(roomSnapshot(roomId)).isEqualTo(oldRoom);
    assertOriginalPublicationOnly(fixture);
  }

  private String roomSnapshot(long id) {
    return jdbc.sql("select row_to_json(r)::text from rooms r where id=:id")
        .param("id", id)
        .query(String.class)
        .single();
  }

  private String recordSnapshot(NkCreateHttpFixture.Fixture fixture, String key) {
    return jdbc.sql(
            "select row_to_json(p)::text from publication_idempotency p where user_id=:id and request_key=:key")
        .param("id", fixture.user().getId())
        .param("key", key)
        .query(String.class)
        .single();
  }

  private void assertOriginalPublicationOnly(NkCreateHttpFixture.Fixture fixture) {
    long userId = fixture.user().getId();
    assertThat(
            jdbc.sql("select count(*) from rooms where host_user_id=:id")
                .param("id", userId)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
    assertThat(
            jdbc.sql(
                    "select count(*) from games g join rooms r on r.id=g.room_id where r.host_user_id=:id")
                .param("id", userId)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
    assertThat(
            jdbc.sql(
                    "select count(*) from choices c join games g on g.id=c.game_id join rooms r on r.id=g.room_id where r.host_user_id=:id")
                .param("id", userId)
                .query(Long.class)
                .single())
        .isEqualTo(4L);
    assertThat(
            jdbc.sql("select count(*) from publication_idempotency where user_id=:id")
                .param("id", userId)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  private void assertInternalError(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(500);
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo("INTERNAL_ERROR");
    assertThat(json.readTree(response.body()).required("message").textValue())
        .isEqualTo("요청을 처리하는 중 오류가 발생했습니다.");
    assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(response.headers().firstValue("Location")).isEmpty();
    assertThat(response.headers().firstValue("Retry-After")).isEmpty();
  }

  @Test
  @DisplayName("출제 요청 키는 games 이외 CORS preflight에서 허용되지 않는다")
  void doesNotBroadenIdempotencyKeyCorsPermissionToExistingEndpoints() throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/users/me/profile"))
                    .header("Origin", "http://localhost:5173")
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "Authorization, Idempotency-Key")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(response.headers().allValues("Access-Control-Allow-Headers"))
        .allSatisfy(headers -> assertThat(headers).doesNotContainIgnoringCase("Idempotency-Key"));
  }

  @Test
  @DisplayName("논리 만료 정각에는 실제 POST가 새 세대로 교체하고 기존 게임을 보존한다")
  void replacesExpiredGenerationAtTheExactStoredExpiryOverHttp() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    String key = "8aa20f38-0a6f-4b9d-97e5-45b5250c3e1a";
    HttpResponse<String> first =
        fixture()
            .post(
                fixture,
                key,
                fixture.choiceIds(),
                fixture.choiceIds().getFirst(),
                fixture.rewardId());
    assertThat(first.statusCode()).isEqualTo(201);
    JsonNode firstJson = json.readTree(first.body());
    long oldRoomId = Long.parseLong(firstJson.required("roomId").textValue());
    long oldGameId = Long.parseLong(firstJson.required("gameId").textValue());
    Instant expiry = recordExpiry(fixture, key);
    String originalRoom = roomSnapshot(oldRoomId);
    String originalRecord = recordSnapshot(fixture, key);

    clearInvocations(users, rooms, questions, choices, rewards);
    clock.set(expiry.minusNanos(1_000));
    HttpResponse<String> replay =
        fixture()
            .post(
                fixture,
                key,
                fixture.choiceIds().reversed(),
                fixture.choiceIds().getFirst(),
                fixture.rewardId());
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(json.readTree(replay.body())).isEqualTo(firstJson);
    assertThat(roomSnapshot(oldRoomId)).isEqualTo(originalRoom);
    assertThat(recordSnapshot(fixture, key)).isEqualTo(originalRecord);
    assertThat(recordExpiry(fixture, key)).isEqualTo(expiry);
    assertOriginalPublicationOnly(fixture);
    then(users).should(never()).lockAndFindStatusById(anyLong());
    then(rooms).shouldHaveNoInteractions();
    then(questions).shouldHaveNoInteractions();
    then(choices).shouldHaveNoInteractions();
    then(rewards).shouldHaveNoInteractions();

    clock.set(expiry);
    HttpResponse<String> replacement =
        fixture()
            .post(
                fixture,
                key,
                fixture.choiceIds(),
                fixture.choiceIds().getFirst(),
                fixture.alternateRewardId());
    assertThat(replacement.statusCode()).withFailMessage(replacement.body()).isEqualTo(201);
    assertThat(Long.parseLong(json.readTree(replacement.body()).required("gameId").textValue()))
        .isNotEqualTo(oldGameId);
    assertThat(
            jdbc.sql("select count(*) from games where id = :id")
                .param("id", oldGameId)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("논리 만료 직후에도 실제 POST가 새 세대를 만들고 기존 성공 게임을 보존한다")
  void replacesExpiredGenerationAfterTheStoredExpiryOverHttp() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    String key = "8aa20f38-0a6f-4b9d-97e5-45b5250c3e1b";
    HttpResponse<String> first =
        fixture()
            .post(
                fixture,
                key,
                fixture.choiceIds(),
                fixture.choiceIds().getFirst(),
                fixture.rewardId());
    assertThat(first.statusCode()).isEqualTo(201);
    long oldGameId = Long.parseLong(json.readTree(first.body()).required("gameId").textValue());
    clock.set(recordExpiry(fixture, key).plusNanos(1_000));

    HttpResponse<String> replacement =
        fixture()
            .post(
                fixture,
                key,
                fixture.choiceIds(),
                fixture.choiceIds().getFirst(),
                fixture.alternateRewardId());
    assertThat(replacement.statusCode()).withFailMessage(replacement.body()).isEqualTo(201);
    assertThat(Long.parseLong(json.readTree(replacement.body()).required("gameId").textValue()))
        .isNotEqualTo(oldGameId);
    assertThat(
            jdbc.sql("select count(*) from games where id = :id")
                .param("id", oldGameId)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("없는 보상 프리셋은 실제 POST에서 404이고 선점 기록을 남기지 않는다")
  void rejectsMissingRewardPresetWithoutClaim() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    NkCreateHttpFixture.Fixture missingReward =
        new NkCreateHttpFixture.Fixture(
            fixture.user(),
            fixture.grant(),
            Long.MAX_VALUE,
            fixture.alternateRewardId(),
            fixture.questionId(),
            fixture.choiceIds());
    String key = "8aa20f38-0a6f-4b9d-97e5-45b5250c3e1e";

    HttpResponse<String> response =
        fixture()
            .post(
                missingReward,
                key,
                missingReward.choiceIds(),
                missingReward.choiceIds().getFirst(),
                missingReward.rewardId());
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo("PRESET_NOT_FOUND");
    assertThat(
            jdbc.sql(
                    "select count(*) from publication_idempotency where user_id = :userId and request_key = :key")
                .param("userId", fixture.user().getId())
                .param("key", key)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  @DisplayName("멱등 완료 갱신이 0건이면 실제 POST의 Room과 게임 조립 전체가 롤백된다")
  void rollsBackTheWholePublicationWhenClaimCompletionFails() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    String key = "8aa20f38-0a6f-4b9d-97e5-45b5250c3e1d";
    PublicationIdempotencyRepository target = AopTestUtils.getTargetObject(records);
    AtomicBoolean completionGuardFailed = new AtomicBoolean();
    willAnswer(
            invocation -> {
              int changed =
                  jdbc.sql(
                          "update publication_idempotency set claim_id = :replacement "
                              + "where user_id = :userId and request_key = :key and game_id is null")
                      .param("replacement", UUID.randomUUID())
                      .param("userId", fixture.user().getId())
                      .param("key", key)
                      .update();
              assertThat(changed).isEqualTo(1);
              try {
                return invocation.callRealMethod();
              } catch (DomainException failure) {
                assertThat(failure.getErrorCode())
                    .isEqualTo(PublicationErrorCode.PUBLICATION_CLAIM_COMPLETION_FAILED);
                completionGuardFailed.set(true);
                throw failure;
              }
            })
        .given(target)
        .completeClaim(any(), anyLong(), any());

    HttpResponse<String> response =
        fixture()
            .post(
                fixture,
                key,
                fixture.choiceIds(),
                fixture.choiceIds().getFirst(),
                fixture.rewardId());
    assertThat(completionGuardFailed).isTrue();
    assertThat(response.statusCode()).isEqualTo(500);
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo("INTERNAL_ERROR");
    assertThat(
            jdbc.sql("select count(*) from rooms where host_user_id = :userId")
                .param("userId", fixture.user().getId())
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql(
                    "select count(*) from publication_idempotency where user_id = :userId and request_key = :key")
                .param("userId", fixture.user().getId())
                .param("key", key)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  @DisplayName("복원할 수 없는 커밋 멱등 기록은 실제 POST에서 별도 500으로 보존된다")
  void keepsMalformedCommittedRecordInsteadOfReclaimingIt() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    String key = "8aa20f38-0a6f-4b9d-97e5-45b5250c3e1c";
    jdbc.sql(
            "insert into publication_idempotency(user_id, request_key, normalized_input) values (:userId, :key, cast('{}' as jsonb))")
        .param("userId", fixture.user().getId())
        .param("key", key)
        .update();

    HttpResponse<String> response =
        fixture()
            .post(
                fixture,
                key,
                fixture.choiceIds(),
                fixture.choiceIds().getFirst(),
                fixture.rewardId());
    assertThat(response.statusCode()).isEqualTo(500);
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo("PUBLICATION_RECORD_UNAVAILABLE");
    assertThat(
            jdbc.sql(
                    "select count(*) from publication_idempotency where user_id = :userId and request_key = :key")
                .param("userId", fixture.user().getId())
                .param("key", key)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("접수 중인 저장 OPEN 방은 실제 POST의 새 공개를 막고 선점도 롤백한다")
  void rejectsWhileStoredOpenRoomHasNotReachedItsCloseTime() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    Instant closesAt = Instant.parse("2040-01-01T00:00:00Z");
    insertStoredRoom(fixture, closesAt);
    clock.set(closesAt.minusNanos(1_000));
    String key = "8aa20f38-0a6f-4b9d-97e5-45b5250c3e1f";

    HttpResponse<String> response =
        fixture()
            .post(
                fixture,
                key,
                fixture.choiceIds(),
                fixture.choiceIds().getFirst(),
                fixture.rewardId());
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo("OPEN_ROOM_EXISTS");
    assertThat(
            jdbc.sql(
                    "select count(*) from publication_idempotency where user_id = :userId and request_key = :key")
                .param("userId", fixture.user().getId())
                .param("key", key)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  @DisplayName("저장 OPEN 방의 마감 정각에는 실제 POST가 그 방만 CLOSED로 전이하고 새 방을 만든다")
  void closesStoredOpenRoomAtTheExactCloseTimeBeforePublishing() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    Instant closesAt = Instant.parse("2040-01-01T00:00:00Z");
    long oldRoomId = insertStoredRoom(fixture, closesAt);
    clock.set(closesAt);

    HttpResponse<String> response =
        fixture()
            .post(
                fixture,
                "8aa20f38-0a6f-4b9d-97e5-45b5250c3e20",
                fixture.choiceIds(),
                fixture.choiceIds().getFirst(),
                fixture.rewardId());
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
    assertThat(
            jdbc.sql("select status from rooms where id = :roomId")
                .param("roomId", oldRoomId)
                .query(String.class)
                .single())
        .isEqualTo("CLOSED");
  }

  @Test
  @DisplayName("이미 CLOSED인 이전 방은 실제 POST의 새 공개를 막거나 변경하지 않는다")
  void publishesWithAStoredClosedRoomWithoutChangingItsResultState() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    long oldRoomId = insertStoredRoom(fixture, Instant.parse("2040-01-01T00:00:00Z"));
    jdbc.sql("update rooms set status = 'CLOSED' where id = :roomId")
        .param("roomId", oldRoomId)
        .update();

    HttpResponse<String> response =
        fixture()
            .post(
                fixture,
                "8aa20f38-0a6f-4b9d-97e5-45b5250c3e23",
                fixture.choiceIds(),
                fixture.choiceIds().getFirst(),
                fixture.rewardId());
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
    assertThat(
            jdbc.sql("select result_state from rooms where id = :roomId")
                .param("roomId", oldRoomId)
                .query(String.class)
                .single())
        .isEqualTo("NOT_READY");
  }

  @Test
  @DisplayName("시각이 없는 저장 OPEN 방은 실제 POST에서 무결성 500으로 보존된다")
  void rejectsIncompleteStoredOpenRoomWithoutRepairingIt() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    long oldRoomId = insertStoredRoom(fixture, Instant.parse("2040-01-01T00:00:00Z"));
    jdbc.sql("update rooms set opened_at = null, closes_at = null where id = :roomId")
        .param("roomId", oldRoomId)
        .update();

    HttpResponse<String> response =
        fixture()
            .post(
                fixture,
                "8aa20f38-0a6f-4b9d-97e5-45b5250c3e22",
                fixture.choiceIds(),
                fixture.choiceIds().getFirst(),
                fixture.rewardId());
    assertThat(response.statusCode()).isEqualTo(500);
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo("INTERNAL_ERROR");
    assertThat(
            jdbc.sql("select status from rooms where id = :roomId")
                .param("roomId", oldRoomId)
                .query(String.class)
                .single())
        .isEqualTo("OPEN");
  }

  @Test
  @DisplayName("저장 OPEN 방의 마감 직후에도 실제 POST가 방을 CLOSED로 전이하고 새 방을 만든다")
  void closesStoredOpenRoomAfterTheCloseTimeBeforePublishing() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture().fixture();
    Instant closesAt = Instant.parse("2040-01-01T00:00:00Z");
    long oldRoomId = insertStoredRoom(fixture, closesAt);
    clock.set(closesAt.plusNanos(1_000));

    HttpResponse<String> response =
        fixture()
            .post(
                fixture,
                "8aa20f38-0a6f-4b9d-97e5-45b5250c3e21",
                fixture.choiceIds(),
                fixture.choiceIds().getFirst(),
                fixture.rewardId());
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
    assertThat(
            jdbc.sql("select status from rooms where id = :roomId")
                .param("roomId", oldRoomId)
                .query(String.class)
                .single())
        .isEqualTo("CLOSED");
  }

  private long insertStoredRoom(NkCreateHttpFixture.Fixture fixture, Instant closesAt) {
    return jdbc.sql(
            """
            insert into rooms(host_user_id, selected_reward_preset_id, status, result_state, opened_at, closes_at, created_at)
            values (:userId, :rewardId, 'OPEN', 'NOT_READY', :openedAt, :closesAt, :openedAt)
            returning id
            """)
        .param("userId", fixture.user().getId())
        .param("rewardId", fixture.rewardId())
        .param("openedAt", closesAt.minusSeconds(3_600).atOffset(ZoneOffset.UTC))
        .param("closesAt", closesAt.atOffset(ZoneOffset.UTC))
        .query(Long.class)
        .single();
  }

  private Instant recordExpiry(NkCreateHttpFixture.Fixture fixture, String key) {
    return jdbc.sql(
            "select record_expires_at from publication_idempotency where user_id = :userId and request_key = :key")
        .param("userId", fixture.user().getId())
        .param("key", key)
        .query(OffsetDateTime.class)
        .single()
        .toInstant();
  }

  private NkCreateHttpFixture fixture() {
    return new NkCreateHttpFixture(port, json, jdbc, users, logins);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfig {
    @Bean
    @Primary
    FixedDbTimeSource fixedDbTimeSource(JdbcClient jdbc) {
      return new FixedDbTimeSource(jdbc);
    }

    @Bean
    AssemblyFaultProbe assemblyFaultProbe(EntityManagerFactory factory) {
      return new AssemblyFaultProbe(factory);
    }
  }

  private static final class AssemblyFaultProbe {
    private final AtomicReference<AssemblyFault> active = new AtomicReference<>();

    private AssemblyFaultProbe(EntityManagerFactory factory) {
      EventListenerRegistry listeners =
          factory
              .unwrap(SessionFactoryImplementor.class)
              .getServiceRegistry()
              .getService(EventListenerRegistry.class);
      listeners.prependListeners(
          EventType.PRE_INSERT,
          (PreInsertEventListener)
              event -> {
                if (event.getEntity() instanceof Room) {
                  observe(fault -> fault.reach("ROOM_BEFORE"));
                }
                return false;
              });
      listeners.appendListeners(
          EventType.POST_INSERT,
          new PostInsertEventListener() {
            @Override
            public void onPostInsert(PostInsertEvent event) {
              observe(fault -> fault.afterInsert(event.getEntity()));
            }

            @Override
            public boolean requiresPostCommitHandling(EntityPersister persister) {
              return false;
            }
          });
      listeners.prependListeners(
          EventType.FLUSH, (FlushEventListener) event -> observe(fault -> fault.onFlush(false)));
      listeners.appendListeners(
          EventType.FLUSH, (FlushEventListener) event -> observe(fault -> fault.onFlush(true)));
    }

    private void observe(Consumer<AssemblyFault> measurement) {
      AssemblyFault fault = active.get();
      if (fault != null) {
        measurement.accept(fault);
      }
    }
  }

  private static final class AssemblyFault {
    private final String point;
    private final Runnable fail;
    private Game game;
    private int insertedChoices;
    private boolean choicesFlushed;
    private boolean publicationFlushed;

    private AssemblyFault(String point, Runnable fail) {
      this.point = point;
      this.fail = fail;
    }

    private void afterInsert(Object entity) {
      if (entity instanceof Game inserted) {
        game = inserted;
        reach("GAME_AFTER");
      } else if (entity instanceof Choice) {
        reach("CHOICE_" + ++insertedChoices);
      }
    }

    private void onFlush(boolean completed) {
      if (completed && insertedChoices == 4) {
        choicesFlushed = true;
      }
      Room room = game == null ? null : game.getRoom();
      if (!publicationFlushed
          && room != null
          && room.getOpenedAt() != null
          && room.getClosesAt() != null) {
        publicationFlushed = completed;
        reach(completed ? "PUBLISH_AFTER_FLUSH" : "PUBLISH_BEFORE_FLUSH");
      }
    }

    private void beforeClockRead() {
      if (point.equals("ANSWER_BEFORE_PUBLISH") && choicesFlushed) {
        assertThat(game.getCorrectChoiceId()).isNotNull();
        reach("ANSWER_BEFORE_PUBLISH");
      }
    }

    private void reach(String reached) {
      if (point.equals(reached)) {
        fail.run();
      }
    }
  }

  static final class FixedDbTimeSource extends DbTimeSource {
    private final AtomicReference<Instant> current = new AtomicReference<>();
    private final AtomicReference<Runnable> beforeRead = new AtomicReference<>();

    FixedDbTimeSource(JdbcClient jdbc) {
      super(jdbc);
    }

    @Override
    public Instant readCurrentTime() {
      Runnable hook = beforeRead.get();
      if (hook != null) hook.run();
      Instant fixed = current.get();
      return fixed == null ? super.readCurrentTime() : fixed;
    }

    void beforeRead(Runnable hook) {
      beforeRead.set(hook);
    }

    void set(Instant instant) {
      current.set(instant);
    }

    void clear() {
      current.set(null);
      beforeRead.set(null);
    }
  }

  private static String nk75JdbcUrl() {
    return NkCreateHttpFixture.withCurrentSchema(
        PostgresTestDatabase.container().getJdbcUrl(), "nk75_http_acceptance");
  }
}

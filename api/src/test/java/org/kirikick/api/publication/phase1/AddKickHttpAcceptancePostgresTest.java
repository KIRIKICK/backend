package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.reset;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.FlushEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
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
import org.kirikick.api.game.domain.exception.GameErrorCode;
import org.kirikick.api.game.repository.ChoiceRepository;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.domain.PublicationKind;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.kirikick.api.publication.repository.PublicationIdempotencyRepository;
import org.kirikick.api.publication.service.AddKickToRoomService;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

/** Actual HTTP acceptance coverage for adding one game to an already-published room. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({
  OpenRoomHttpPostgresTest.DomainEntities.class,
  AddKickHttpAcceptancePostgresTest.AssemblyFaultConfig.class
})
class AddKickHttpAcceptancePostgresTest {

  @LocalServerPort private int port;
  @Autowired private ObjectMapper json;
  @Autowired private JdbcClient jdbc;
  @Autowired private UserRepository users;
  @Autowired private LoginSessionService logins;
  @Autowired private EntityManager entityManager;
  @MockitoSpyBean private DbTimeSource clock;
  @MockitoSpyBean private PublicationIdempotencyRepository records;
  @MockitoSpyBean private AddKickToRoomService addKickService;
  @MockitoSpyBean private ChoiceRepository choiceRepository;
  @Autowired private AssemblyFaultProbe assemblyFaults;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    registry.add("spring.datasource.url", AddKickHttpAcceptancePostgresTest::jdbcUrl);
    registry.add("spring.datasource.username", PostgresTestDatabase.container()::getUsername);
    registry.add("spring.datasource.password", PostgresTestDatabase.container()::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.default-schema", () -> "nk76_http_acceptance");
    registry.add("publication.grace", () -> "PT5M");
  }

  @AfterEach
  void clearClock() {
    reset(clock);
  }

  @Test
  @DisplayName("H-01/H-02: 공개된 방에 첫 추가 킥은 201이고 보기 순서만 바꾼 같은 키는 200으로 복구한다")
  void createsKickAndReplaysSameMeaningInput() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();

    HttpResponse<String> created = post(fixture, key, fixture.choiceIds());

    assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
    JsonNode first = json.readTree(created.body());
    assertThat(first.required("roomId").textValue()).isEqualTo(String.valueOf(fixture.roomId()));
    assertThat(first.required("gameId").textValue()).matches("[1-9][0-9]*");
    assertThat(first.required("openedAt").textValue())
        .isEqualTo(roomInstant(fixture.roomId(), "opened_at"));
    assertThat(first.required("closesAt").textValue())
        .isEqualTo(roomInstant(fixture.roomId(), "closes_at"));

    HttpResponse<String> replay = post(fixture, key, fixture.choiceIds().reversed());

    assertThat(replay.statusCode()).withFailMessage(replay.body()).isEqualTo(200);
    assertThat(json.readTree(replay.body())).isEqualTo(first);
    assertThat(
            jdbc.sql("select count(*) from games where room_id = :roomId")
                .param("roomId", fixture.roomId())
                .query(Long.class)
                .single())
        .isEqualTo(2L);
  }

  @Test
  @DisplayName("H-02/H-05F: FINAL 방도 기존 미만료 성공은 복구하지만 새 키 추가는 거절한다")
  void replaysExistingSuccessButRejectsNewKickWhenResultIsFinal() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    String replayKey = UUID.randomUUID().toString();
    HttpResponse<String> created = post(fixture, replayKey, fixture.choiceIds());
    assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
    jdbc.sql("update rooms set result_state = 'FINAL' where id = :roomId")
        .param("roomId", fixture.roomId())
        .update();
    Instant closesAt =
        jdbc.sql("select closes_at from rooms where id = :roomId")
            .param("roomId", fixture.roomId())
            .query(OffsetDateTime.class)
            .single()
            .toInstant();
    String before = fullSnapshot(fixture, replayKey);
    willReturn(closesAt.minusNanos(1_000)).given(clock).readCurrentTime();
    HttpResponse<String> replayBeforeClose =
        post(fixture, replayKey, fixture.choiceIds().reversed());
    reset(clock);
    willReturn(closesAt.plusNanos(1_000)).given(clock).readCurrentTime();
    HttpResponse<String> replayAfterClose =
        post(fixture, replayKey, fixture.choiceIds().reversed());
    HttpResponse<String> changedInput =
        new AddKickHttpFixture(port, json, jdbc, users, logins)
            .postAt(
                fixture,
                fixture.roomId(),
                replayKey,
                fixture.choiceIds(),
                fixture.choiceIds().getLast());
    HttpResponse<String> fresh = post(fixture, UUID.randomUUID().toString(), fixture.choiceIds());

    assertThat(replayBeforeClose.statusCode())
        .withFailMessage(replayBeforeClose.body())
        .isEqualTo(200);
    assertThat(json.readTree(replayBeforeClose.body())).isEqualTo(json.readTree(created.body()));
    assertThat(replayAfterClose.statusCode())
        .withFailMessage(replayAfterClose.body())
        .isEqualTo(200);
    assertThat(json.readTree(replayAfterClose.body())).isEqualTo(json.readTree(created.body()));
    assertThat(changedInput.statusCode()).withFailMessage(changedInput.body()).isEqualTo(409);
    assertThat(json.readTree(changedInput.body()).required("code").textValue())
        .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
    assertThat(fresh.statusCode()).withFailMessage(fresh.body()).isEqualTo(409);
    assertThat(json.readTree(fresh.body()).required("code").textValue())
        .isEqualTo("ROOM_INTAKE_CLOSED");
    assertThat(
            jdbc.sql("select count(*) from games where room_id = :roomId")
                .param("roomId", fixture.roomId())
                .query(Long.class)
                .single())
        .isEqualTo(2L);
    assertThat(fullSnapshot(fixture, replayKey)).isEqualTo(before);
  }

  @Test
  @DisplayName("H-05F: 마감 전 OPEN FINAL Room의 미사용 질문 신규 키는 FINAL 때문에 409이다")
  void rejectsFreshOtherwiseNewQuestionWhenResultIsFinalBeforeDeadline() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    jdbc.sql("update rooms set result_state = 'FINAL' where id = :roomId")
        .param("roomId", fixture.roomId())
        .update();
    String before = fullSnapshot(fixture, key);

    HttpResponse<String> response = post(fixture, key, fixture.choiceIds());

    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(409);
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo("ROOM_INTAKE_CLOSED");
    assertThat(fullSnapshot(fixture, key)).isEqualTo(before);
  }

  @Test
  @DisplayName("H-03: 인증되지 않은 호출자는 기존 성공 키의 결과를 복구하거나 노출하지 않는다")
  void hidesExistingPublicationFromUnauthenticatedCaller() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    HttpResponse<String> created = post(fixture, key, fixture.choiceIds());
    assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
    long beforeGames =
        jdbc.sql("select count(*) from games where room_id = :roomId")
            .param("roomId", fixture.roomId())
            .query(Long.class)
            .single();
    String recordBefore = recordSnapshot(fixture, key);

    HttpResponse<String> denied =
        new AddKickHttpFixture(port, json, jdbc, users, logins).postRaw(fixture, key, false, "{}");

    assertThat(denied.statusCode()).isEqualTo(401);
    assertThat(denied.body()).doesNotContain("gameId", "openedAt", "closesAt");
    assertThat(
            jdbc.sql("select count(*) from games where room_id = :roomId")
                .param("roomId", fixture.roomId())
                .query(Long.class)
                .single())
        .isEqualTo(beforeGames);
    assertThat(recordSnapshot(fixture, key)).isEqualTo(recordBefore);
  }

  @Test
  @DisplayName("H-03: 현재 방장의 malformed·incomplete·무한 만료 기록은 교체하지 않고 500으로 격리한다")
  void preservesUnreadableRecordsForCurrentOwner() throws Exception {
    for (String state : List.of("malformed", "incomplete", "infinity", "minus-infinity")) {
      AddKickHttpFixture.Fixture fixture = fixture();
      String key = UUID.randomUUID().toString();
      HttpResponse<String> created = post(fixture, key, fixture.choiceIds());
      assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
      jdbc.sql("update rooms set result_state = 'FINAL' where id = :roomId")
          .param("roomId", fixture.roomId())
          .update();
      switch (state) {
        case "malformed" ->
            jdbc.sql(
                    "update publication_idempotency set normalized_input = cast(:input as jsonb) where user_id = :userId and request_key = :key")
                .param("input", "{\"kind\":\"BROKEN\"}")
                .param("userId", fixture.user().getId())
                .param("key", key)
                .update();
        case "incomplete" ->
            jdbc.sql(
                    "update publication_idempotency set game_id = null, record_expires_at = null where user_id = :userId and request_key = :key")
                .param("userId", fixture.user().getId())
                .param("key", key)
                .update();
        case "infinity", "minus-infinity" ->
            jdbc.sql(
                    "update publication_idempotency set record_expires_at = cast(:expiry as timestamptz) where user_id = :userId and request_key = :key")
                .param("expiry", state.equals("infinity") ? "infinity" : "-infinity")
                .param("userId", fixture.user().getId())
                .param("key", key)
                .update();
        default -> throw new AssertionError(state);
      }
      String before = fullSnapshot(fixture, key);

      HttpResponse<String> response = post(fixture, key, fixture.choiceIds());

      assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(500);
      assertThat(json.readTree(response.body()).required("code").textValue())
          .isEqualTo("PUBLICATION_RECORD_UNAVAILABLE");
      assertThat(response.body()).doesNotContain("gameId", "openedAt", "closesAt");
      assertThat(fullSnapshot(fixture, key)).isEqualTo(before);

      long replacementHost =
          users
              .saveAndFlush(User.create("un" + UUID.randomUUID().toString().substring(0, 6), null))
              .getId();
      jdbc.sql("update rooms set host_user_id = :hostId where id = :roomId")
          .param("hostId", replacementHost)
          .param("roomId", fixture.roomId())
          .update();
      String afterOwnershipMove = fullSnapshot(fixture, key);

      HttpResponse<String> forbidden = post(fixture, key, fixture.choiceIds());

      assertThat(forbidden.statusCode()).withFailMessage(forbidden.body()).isEqualTo(403);
      assertThat(json.readTree(forbidden.body()).required("code").textValue())
          .isEqualTo("PUBLICATION_FORBIDDEN");
      assertThat(forbidden.body()).doesNotContain("gameId", "openedAt", "closesAt");
      assertThat(fullSnapshot(fixture, key)).isEqualTo(afterOwnershipMove);
    }
  }

  @Test
  @DisplayName("H-08: 자연 마감된 같은 방의 만료 경계는 직전만 복구하고 정각과 직후는 이전 기록을 보존한다")
  void preservesExpiredGenerationAtNaturalRoomExpiryBoundary() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    HttpResponse<String> created = post(fixture, key, fixture.choiceIds());
    assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
    jdbc.sql("update rooms set status = 'CLOSED' where id = :roomId")
        .param("roomId", fixture.roomId())
        .update();
    String before = fullSnapshot(fixture, key);
    Instant expiry =
        jdbc.sql(
                "select record_expires_at from publication_idempotency where user_id = :userId and request_key = :key")
            .param("userId", fixture.user().getId())
            .param("key", key)
            .query(Instant.class)
            .single();

    willReturn(expiry.minusNanos(1_000)).given(clock).readCurrentTime();
    HttpResponse<String> beforeExpiry = post(fixture, key, fixture.choiceIds().reversed());
    reset(clock);
    willReturn(expiry).given(clock).readCurrentTime();
    HttpResponse<String> atExpiry = post(fixture, key, fixture.choiceIds());
    assertThat(beforeExpiry.statusCode()).withFailMessage(beforeExpiry.body()).isEqualTo(200);
    assertThat(json.readTree(beforeExpiry.body())).isEqualTo(json.readTree(created.body()));
    assertThat(atExpiry.statusCode()).withFailMessage(atExpiry.body()).isEqualTo(409);
    assertThat(json.readTree(atExpiry.body()).required("code").textValue())
        .isEqualTo("ROOM_INTAKE_CLOSED");
    assertThat(fullSnapshot(fixture, key)).isEqualTo(before);

    reset(clock);
    willReturn(expiry.plusNanos(1_000)).given(clock).readCurrentTime();
    HttpResponse<String> afterExpiry = post(fixture, key, fixture.choiceIds());

    assertThat(afterExpiry.statusCode()).withFailMessage(afterExpiry.body()).isEqualTo(409);
    assertThat(fullSnapshot(fixture, key)).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(strings = {"infinity", "-infinity"})
  @DisplayName("H-03: 비유한 만료 기록은 비활성 사용자와 없는·비공개 대상 방보다 먼저 해석하지 않는다")
  void doesNotExposeNonFiniteReceiptBeforeAuthorizationAndTargetChecks(String expiry)
      throws Exception {
    AddKickHttpFixture.Fixture inactiveFixture = fixture();
    String inactiveKey = UUID.randomUUID().toString();
    assertThat(post(inactiveFixture, inactiveKey, inactiveFixture.choiceIds()).statusCode())
        .isEqualTo(201);
    setRecordExpiry(inactiveFixture, inactiveKey, expiry);
    String inactiveBefore = fullSnapshot(inactiveFixture, inactiveKey);
    jdbc.sql("update users set status = 'SUSPENDED' where id = :userId")
        .param("userId", inactiveFixture.user().getId())
        .update();

    HttpResponse<String> inactive = post(inactiveFixture, inactiveKey, inactiveFixture.choiceIds());

    assertError(inactive, 403, "ACCOUNT_INACTIVE");
    assertThat(fullSnapshot(inactiveFixture, inactiveKey)).isEqualTo(inactiveBefore);

    AddKickHttpFixture.Fixture missingFixture = fixture();
    String missingKey = UUID.randomUUID().toString();
    assertThat(post(missingFixture, missingKey, missingFixture.choiceIds()).statusCode())
        .isEqualTo(201);
    setRecordExpiry(missingFixture, missingKey, expiry);
    String missingBefore = fullSnapshot(missingFixture, missingKey);
    long missingRoomId =
        jdbc.sql("select coalesce(max(id), 0) + 1 from rooms").query(Long.class).single();

    HttpResponse<String> missing =
        new AddKickHttpFixture(port, json, jdbc, users, logins)
            .postAt(
                missingFixture,
                missingRoomId,
                missingKey,
                missingFixture.choiceIds(),
                missingFixture.choiceIds().getFirst());

    assertError(missing, 404, "ROOM_NOT_FOUND");
    assertThat(fullSnapshot(missingFixture, missingKey)).isEqualTo(missingBefore);

    AddKickHttpFixture.Fixture unpublishedFixture = fixture();
    String unpublishedKey = UUID.randomUUID().toString();
    assertThat(
            post(unpublishedFixture, unpublishedKey, unpublishedFixture.choiceIds()).statusCode())
        .isEqualTo(201);
    setRecordExpiry(unpublishedFixture, unpublishedKey, expiry);
    long unpublishedRoomId =
        jdbc.sql(
                """
                insert into rooms (host_user_id, selected_reward_preset_id, status, result_state, created_at)
                values (:userId, :rewardId, 'CLOSED', 'NOT_READY', clock_timestamp())
                returning id
                """)
            .param("userId", unpublishedFixture.user().getId())
            .param("rewardId", unpublishedFixture.rewardId())
            .query(Long.class)
            .single();
    String unpublishedBefore = fullSnapshot(unpublishedFixture, unpublishedKey);

    HttpResponse<String> unpublished =
        new AddKickHttpFixture(port, json, jdbc, users, logins)
            .postAt(
                unpublishedFixture,
                unpublishedRoomId,
                unpublishedKey,
                unpublishedFixture.choiceIds(),
                unpublishedFixture.choiceIds().getFirst());

    assertError(unpublished, 404, "ROOM_NOT_FOUND");
    assertThat(fullSnapshot(unpublishedFixture, unpublishedKey)).isEqualTo(unpublishedBefore);
  }

  @Test
  @DisplayName("H-04: 미만료 같은 키의 다른 유효 질문은 FINAL intake 전에 409 충돌이다")
  void rejectsSameKeyForDifferentQuestionBeforeIntake() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    assertThat(post(fixture, key, fixture.choiceIds()).statusCode()).isEqualTo(201);
    long differentQuestionId =
        jdbc.sql("select question_preset_id from games where id = :gameId")
            .param("gameId", fixture.existingGameId())
            .query(Long.class)
            .single();
    List<Long> differentChoiceIds =
        jdbc.sql(
                "select id from question_preset_choices where question_preset_id = :questionId order by id")
            .param("questionId", differentQuestionId)
            .query(Long.class)
            .list();
    jdbc.sql("update rooms set result_state = 'FINAL' where id = :roomId")
        .param("roomId", fixture.roomId())
        .update();
    String before = fullSnapshot(fixture, key);
    String body =
        json.writeValueAsString(
            Map.of(
                "questionPresetId", String.valueOf(differentQuestionId),
                "choiceIds", differentChoiceIds.stream().map(String::valueOf).toList(),
                "correctChoiceId", String.valueOf(differentChoiceIds.getFirst())));

    HttpResponse<String> response =
        new AddKickHttpFixture(port, json, jdbc, users, logins)
            .postRawAs(fixture, key, fixture.grant().accessToken(), body);

    assertError(response, 409, "IDEMPOTENCY_KEY_CONFLICT");
    assertThat(fullSnapshot(fixture, key)).isEqualTo(before);
  }

  @Test
  @DisplayName("H-03: 공개 시각 계약이 깨진 성공 기록은 같은 인가 대상에서 500으로 격리한다")
  void quarantinesStoredSuccessWithInvalidPublicationWindowAfterAuthorization() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    assertThat(post(fixture, key, fixture.choiceIds()).statusCode()).isEqualTo(201);
    jdbc.sql("update rooms set closes_at = opened_at + interval '23 hours' where id = :roomId")
        .param("roomId", fixture.roomId())
        .update();
    String before = fullSnapshot(fixture, key);

    HttpResponse<String> response = post(fixture, key, fixture.choiceIds());

    assertError(response, 500, "PUBLICATION_RECORD_UNAVAILABLE");
    assertThat(fullSnapshot(fixture, key)).isEqualTo(before);
  }

  @Test
  @DisplayName("H-05F: 자연 만료된 Room A 기록으로 FINAL Room B에 추가하면 이전 generation을 보존하고 409이다")
  void preservesNaturallyExpiredReceiptWhenCurrentRoomIsFinal() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    assertThat(post(fixture, key, fixture.choiceIds()).statusCode()).isEqualTo(201);
    jdbc.sql("update rooms set status = 'CLOSED' where id = :roomId")
        .param("roomId", fixture.roomId())
        .update();
    Instant oldExpiry =
        jdbc.sql(
                "select record_expires_at from publication_idempotency where user_id = :userId and request_key = :key")
            .param("userId", fixture.user().getId())
            .param("key", key)
            .query(Instant.class)
            .single();
    long finalRoomId =
        jdbc.sql(
                """
                insert into rooms (host_user_id, selected_reward_preset_id, status, result_state, opened_at, closes_at, created_at)
                values (:userId, :rewardId, 'OPEN', 'FINAL', :openedAt, :closesAt, clock_timestamp())
                returning id
                """)
            .param("userId", fixture.user().getId())
            .param("rewardId", fixture.rewardId())
            .param("openedAt", oldExpiry.minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
            .param("closesAt", oldExpiry.plus(Duration.ofHours(23)).atOffset(ZoneOffset.UTC))
            .query(Long.class)
            .single();
    copyOriginalFirstGame(fixture, finalRoomId);
    String before = recordSnapshot(fixture, key);
    willReturn(oldExpiry).given(clock).readCurrentTime();

    HttpResponse<String> response =
        new AddKickHttpFixture(port, json, jdbc, users, logins)
            .postAt(fixture, finalRoomId, key, fixture.choiceIds(), fixture.choiceIds().getFirst());

    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(409);
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo("ROOM_INTAKE_CLOSED");
    assertThat(recordSnapshot(fixture, key)).isEqualTo(before);
  }

  @Test
  @DisplayName("H-03/H-04: 방 소유자가 바뀐 뒤 원 방장은 같은 키의 복구와 충돌을 볼 수 없다")
  void hidesReceiptAfterRoomOwnershipMoves() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    assertThat(post(fixture, key, fixture.choiceIds()).statusCode()).isEqualTo(201);
    long replacementHost =
        users
            .saveAndFlush(User.create("mv" + UUID.randomUUID().toString().substring(0, 6), null))
            .getId();
    jdbc.sql("update rooms set host_user_id = :hostId where id = :roomId")
        .param("hostId", replacementHost)
        .param("roomId", fixture.roomId())
        .update();
    String before = fullSnapshot(fixture, key);

    HttpResponse<String> response = post(fixture, key, fixture.choiceIds());

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body()).doesNotContain("gameId", "openedAt", "closesAt");
    assertThat(fullSnapshot(fixture, key)).isEqualTo(before);
  }

  @Test
  @DisplayName("H-03: 비활성 원 방장은 같은 성공 키의 복구·충돌·기록을 보지 못한다")
  void hidesExistingReceiptFromInactiveOriginalHost() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    assertThat(post(fixture, key, fixture.choiceIds()).statusCode()).isEqualTo(201);
    String before = fullSnapshot(fixture, key);
    jdbc.sql("update users set status = 'SUSPENDED' where id = :userId")
        .param("userId", fixture.user().getId())
        .update();

    HttpResponse<String> response = post(fixture, key, fixture.choiceIds());

    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(403);
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo("ACCOUNT_INACTIVE");
    assertThat(response.body()).doesNotContain("gameId", "openedAt", "closesAt");
    assertThat(fullSnapshot(fixture, key)).isEqualTo(before);
  }

  @Test
  @DisplayName("H-04: 같은 유효 키를 다른 Room에 사용하면 intake 전에 409 충돌이다")
  void rejectsSameKeyForDifferentRoomBeforeIntake() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    assertThat(post(fixture, key, fixture.choiceIds()).statusCode()).isEqualTo(201);
    jdbc.sql("update rooms set status = 'CLOSED' where id = :roomId")
        .param("roomId", fixture.roomId())
        .update();
    long otherRoom =
        jdbc.sql(
                """
                insert into rooms (host_user_id, selected_reward_preset_id, status, result_state, opened_at, closes_at, created_at)
                values (:userId, :rewardId, 'OPEN', 'FINAL', now(), now() + interval '24 hours', clock_timestamp())
                returning id
                """)
            .param("userId", fixture.user().getId())
            .param("rewardId", fixture.rewardId())
            .query(Long.class)
            .single();
    copyOriginalFirstGame(fixture, otherRoom);
    String before = recordSnapshot(fixture, key);

    HttpResponse<String> response =
        new AddKickHttpFixture(port, json, jdbc, users, logins)
            .postAt(fixture, otherRoom, key, fixture.choiceIds(), fixture.choiceIds().getFirst());

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
    assertThat(recordSnapshot(fixture, key)).isEqualTo(before);
  }

  @Test
  @DisplayName("H-05F: 기존 성공 키라도 미공개 대상 Room은 충돌이나 복구 전에 404로 비노출한다")
  void hidesExistingReceiptWhenIncomingTargetRoomIsUnpublished() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    assertThat(post(fixture, key, fixture.choiceIds()).statusCode()).isEqualTo(201);
    long unpublishedRoomId =
        jdbc.sql(
                """
                insert into rooms (host_user_id, selected_reward_preset_id, status, result_state, created_at)
                values (:userId, :rewardId, 'CLOSED', 'NOT_READY', clock_timestamp())
                returning id
                """)
            .param("userId", fixture.user().getId())
            .param("rewardId", fixture.rewardId())
            .query(Long.class)
            .single();
    String before = fullSnapshot(fixture, key) + "|" + roomSnapshot(unpublishedRoomId);

    HttpResponse<String> response =
        new AddKickHttpFixture(port, json, jdbc, users, logins)
            .postAt(
                fixture,
                unpublishedRoomId,
                key,
                fixture.choiceIds(),
                fixture.choiceIds().getFirst());

    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(404);
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo("ROOM_NOT_FOUND");
    assertThat(response.body()).doesNotContain("gameId", "openedAt", "closesAt");
    assertThat(fullSnapshot(fixture, key) + "|" + roomSnapshot(unpublishedRoomId))
        .isEqualTo(before);
    long missingRoomId =
        jdbc.sql("select coalesce(max(id), 0) + 1 from rooms").query(Long.class).single();
    HttpResponse<String> missing =
        new AddKickHttpFixture(port, json, jdbc, users, logins)
            .postAt(
                fixture, missingRoomId, key, fixture.choiceIds(), fixture.choiceIds().getFirst());
    assertThat(missing.statusCode()).withFailMessage(missing.body()).isEqualTo(404);
    assertThat(json.readTree(missing.body()).required("code").textValue())
        .isEqualTo("ROOM_NOT_FOUND");
    assertThat(fullSnapshot(fixture, key) + "|" + roomSnapshot(unpublishedRoomId))
        .isEqualTo(before);
  }

  @Test
  @DisplayName("H-04: 완료된 NK_CREATE 기록과 같은 키는 추가 출제의 intake 전에 409 충돌이다")
  void rejectsCrossCommandReceiptBeforeIntake() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    long firstGameId =
        jdbc.sql("select id from games where room_id = :roomId order by id limit 1")
            .param("roomId", fixture.roomId())
            .query(Long.class)
            .single();
    long firstQuestionId =
        jdbc.sql("select question_preset_id from games where id = :gameId")
            .param("gameId", firstGameId)
            .query(Long.class)
            .single();
    List<Long> firstChoiceIds =
        jdbc.sql(
                "select id from question_preset_choices where question_preset_id = :questionId order by id")
            .param("questionId", firstQuestionId)
            .query(Long.class)
            .list();
    long firstCorrectChoiceId = firstChoiceIds.getFirst();
    String key = UUID.randomUUID().toString();
    String normalizedCreate =
        json.writeValueAsString(
            new PublicationInput(
                PublicationKind.NK_CREATE,
                null,
                fixture.rewardId(),
                firstQuestionId,
                firstChoiceIds,
                firstCorrectChoiceId));
    jdbc.sql(
            """
            insert into publication_idempotency
                (user_id, request_key, normalized_input, game_id, record_expires_at, claim_id)
            select :userId, :key, cast(:input as jsonb), :gameId,
                   opened_at + interval '24 hours 35 minutes', :claimId
            from rooms where id = :roomId
            """)
        .param("userId", fixture.user().getId())
        .param("key", key)
        .param("input", normalizedCreate)
        .param("gameId", firstGameId)
        .param("claimId", UUID.randomUUID())
        .param("roomId", fixture.roomId())
        .update();
    jdbc.sql("update rooms set result_state = 'FINAL' where id = :roomId")
        .param("roomId", fixture.roomId())
        .update();
    String before = fullSnapshot(fixture, key);

    HttpResponse<String> response = post(fixture, key, fixture.choiceIds());

    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(409);
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
    assertThat(fullSnapshot(fixture, key)).isEqualTo(before);
  }

  @Test
  @DisplayName("H-09: 자연 만료 직전의 교차 Room 요청은 충돌이며 기존 세대를 보존한다")
  void rejectsCrossRoomReplacementImmediatelyBeforeNaturalExpiry() throws Exception {
    ExpiredReplacementFixture setup = expiredReplacementFixture();
    String before = fullSnapshot(setup.fixture(), setup.key());
    willReturn(setup.expiry().minusNanos(1_000)).given(clock).readCurrentTime();

    HttpResponse<String> response =
        new AddKickHttpFixture(port, json, jdbc, users, logins)
            .postAt(
                setup.fixture(),
                setup.currentRoomId(),
                setup.key(),
                setup.fixture().choiceIds(),
                setup.fixture().choiceIds().getFirst());

    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(409);
    assertThat(json.readTree(response.body()).required("code").textValue())
        .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
    assertThat(fullSnapshot(setup.fixture(), setup.key())).isEqualTo(before);
  }

  @Test
  @DisplayName("H-09: 자연 만료 정각에는 다른 공개 Room으로 새 세대를 완성한다")
  void replacesExpiredGenerationAtNaturalExpiry() throws Exception {
    ExpiredReplacementFixture setup = expiredReplacementFixture();
    ReplacementBefore before = replacementBefore(setup);
    willReturn(setup.expiry()).given(clock).readCurrentTime();

    HttpResponse<String> response = postReplacement(setup);

    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
    assertThat(json.readTree(response.body()).required("roomId").textValue())
        .isEqualTo(String.valueOf(setup.currentRoomId()));
    assertThat(json.readTree(response.body()).required("gameId").textValue())
        .isEqualTo(Long.toString(assertCompletedReplacement(setup, before)));
  }

  @Test
  @DisplayName("H-09: 자연 만료 직후에도 다른 공개 Room으로 새 세대를 완성한다")
  void replacesExpiredGenerationImmediatelyAfterNaturalExpiry() throws Exception {
    ExpiredReplacementFixture setup = expiredReplacementFixture();
    ReplacementBefore before = replacementBefore(setup);
    willReturn(setup.expiry().plusNanos(1_000)).given(clock).readCurrentTime();

    HttpResponse<String> response = postReplacement(setup);

    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
    assertThat(json.readTree(response.body()).required("roomId").textValue())
        .isEqualTo(String.valueOf(setup.currentRoomId()));
    assertThat(json.readTree(response.body()).required("gameId").textValue())
        .isEqualTo(Long.toString(assertCompletedReplacement(setup, before)));
  }

  @ParameterizedTest(name = "stage={0}")
  @ValueSource(
      strings = {
        "GAME_AFTER",
        "CHOICE_1",
        "CHOICE_2",
        "CHOICE_3",
        "CHOICE_4",
        "CORRECT_AFTER_FLUSH"
      })
  @DisplayName("H-10: 실제 Game·Choice 조립 단계 실패는 HTTP 500과 전체 롤백이다")
  void rollsBackEveryAssemblyStage(String stage) throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    String before = fullSnapshot(fixture, key);
    AtomicBoolean injected = new AtomicBoolean();
    assemblyFaults.activate(
        stage,
        () -> {
          injected.set(true);
          throw new IllegalStateException("synthetic assembly failure: " + stage);
        });
    try {
      HttpResponse<String> response = post(fixture, key, fixture.choiceIds());

      assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(500);
      assertThat(json.readTree(response.body()).required("code").textValue())
          .isEqualTo("INTERNAL_ERROR");
      assertThat(injected).isTrue();
      assertThat(fullSnapshot(fixture, key)).isEqualTo(before);
    } finally {
      assemblyFaults.clear();
    }
  }

  @Test
  @DisplayName("H-10: 실제 Choice 저장 결과가 불완전하면 verifyComplete 실패과 전체 롤백이다")
  void rollsBackWhenChoiceAssemblyVerificationFails() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    String before = fullSnapshot(fixture, key);
    AtomicBoolean returnedIncompleteChoices = new AtomicBoolean();
    AtomicBoolean observedIncompleteGame = observeIncompleteGameFailure();
    willAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              List<Choice> requested = invocation.getArgument(0);
              List<Choice> saved = requested.subList(0, 3);
              saved.forEach(entityManager::persist);
              entityManager.flush();
              returnedIncompleteChoices.set(true);
              return saved;
            })
        .given(choiceRepository)
        .saveAllAndFlush(any());
    try {
      HttpResponse<String> response = post(fixture, key, fixture.choiceIds());

      assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(500);
      assertThat(json.readTree(response.body()).required("code").textValue())
          .isEqualTo("INTERNAL_ERROR");
      assertThat(returnedIncompleteChoices).isTrue();
      assertThat(observedIncompleteGame).isTrue();
      assertThat(fullSnapshot(fixture, key)).isEqualTo(before);
    } finally {
      reset(addKickService, choiceRepository);
    }
  }

  @Test
  @DisplayName("H-10: Choice 조립 flush 실패는 신규 Room/Game/Choice/claim을 모두 롤백한다")
  void rollsBackFreshAssemblyWhenChoiceTriggerFails() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    String before = fullSnapshot(fixture, key);
    AtomicBoolean observedChoiceAssemblyFailure = observeChoiceAssemblyFailure();
    installChoiceAssemblyFailureTrigger();
    try {
      HttpResponse<String> response = post(fixture, key, fixture.choiceIds());

      assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(500);
      assertThat(json.readTree(response.body()).required("code").textValue())
          .isEqualTo("INTERNAL_ERROR");
      assertThat(observedChoiceAssemblyFailure).isTrue();
      assertThat(fullSnapshot(fixture, key)).isEqualTo(before);
    } finally {
      reset(addKickService);
      dropChoiceAssemblyFailureTrigger();
    }
  }

  @Test
  @DisplayName("H-10: Choice 조립 flush 실패는 만료 기록 교체와 기존 Game을 모두 롤백한다")
  void rollsBackExpiredReplacementAssemblyWhenChoiceTriggerFails() throws Exception {
    ExpiredReplacementFixture setup = expiredReplacementFixture();
    String before = fullSnapshot(setup.fixture(), setup.key());
    willReturn(setup.expiry()).given(clock).readCurrentTime();
    AtomicBoolean observedChoiceAssemblyFailure = observeChoiceAssemblyFailure();
    installChoiceAssemblyFailureTrigger();
    try {
      HttpResponse<String> response =
          new AddKickHttpFixture(port, json, jdbc, users, logins)
              .postAt(
                  setup.fixture(),
                  setup.currentRoomId(),
                  setup.key(),
                  setup.fixture().choiceIds(),
                  setup.fixture().choiceIds().getFirst());

      assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(500);
      assertThat(json.readTree(response.body()).required("code").textValue())
          .isEqualTo("INTERNAL_ERROR");
      assertThat(observedChoiceAssemblyFailure).isTrue();
      assertThat(fullSnapshot(setup.fixture(), setup.key())).isEqualTo(before);
    } finally {
      reset(addKickService);
      dropChoiceAssemblyFailureTrigger();
    }
  }

  @Test
  @DisplayName("H-10: 실제 claim 식별자 변조로 완료 UPDATE가 0이면 HTTP 500과 전체 롤백이다")
  void rollsBackWhenActualClaimCompletionUpdateAffectsNoRow() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    String before = fullSnapshot(fixture, key);
    AtomicBoolean observedCompletionFailure = new AtomicBoolean();
    PublicationIdempotencyRepository target = AopTestUtils.getTargetObject(records);
    willAnswer(
            invocation -> {
              Object claim = invocation.getArgument(0);
              long userId = fixture.user().getId();
              jdbc.sql(
                      "update publication_idempotency set claim_id = :claimId where user_id = :userId and request_key = :key")
                  .param("claimId", UUID.randomUUID())
                  .param("userId", userId)
                  .param("key", key)
                  .update();
              try {
                return invocation.callRealMethod();
              } catch (DomainException exception) {
                assertThat(exception.getErrorCode())
                    .isEqualTo(PublicationErrorCode.PUBLICATION_CLAIM_COMPLETION_FAILED);
                observedCompletionFailure.set(true);
                throw exception;
              }
            })
        .given(target)
        .completeClaim(any(), anyLong(), any());
    try {
      HttpResponse<String> response = post(fixture, key, fixture.choiceIds());
      assertThat(response.statusCode()).isEqualTo(500);
      assertThat(observedCompletionFailure).isTrue();
      assertThat(fullSnapshot(fixture, key)).isEqualTo(before);
    } finally {
      reset(target);
    }
  }

  private AddKickHttpFixture.Fixture fixture() throws Exception {
    return new AddKickHttpFixture(port, json, jdbc, users, logins).fixture();
  }

  private HttpResponse<String> post(
      AddKickHttpFixture.Fixture fixture, String key, List<Long> choiceIds) throws Exception {
    return new AddKickHttpFixture(port, json, jdbc, users, logins)
        .post(fixture, key, choiceIds, fixture.choiceIds().getFirst());
  }

  private static String jdbcUrl() {
    return AddKickHttpFixture.withCurrentSchema(
        PostgresTestDatabase.container().getJdbcUrl(), "nk76_http_acceptance");
  }

  private String roomInstant(long roomId, String column) {
    String databaseValue =
        jdbc.sql("select " + column + "::text from rooms where id = :roomId")
            .param("roomId", roomId)
            .query(String.class)
            .single();
    return OffsetDateTime.parse(databaseValue.replace(' ', 'T')).toInstant().toString();
  }

  private String recordSnapshot(AddKickHttpFixture.Fixture fixture, String key) {
    return jdbc.sql(
            """
            select normalized_input::text || '|' || game_id::text || '|' || record_expires_at::text || '|'
                   || claim_id::text
            from publication_idempotency
            where user_id = :userId and request_key = :key
            """)
        .param("userId", fixture.user().getId())
        .param("key", key)
        .query(String.class)
        .single();
  }

  private void setRecordExpiry(AddKickHttpFixture.Fixture fixture, String key, String expiry) {
    jdbc.sql(
            "update publication_idempotency set record_expires_at = cast(:expiry as timestamptz) "
                + "where user_id = :userId and request_key = :key")
        .param("expiry", expiry)
        .param("userId", fixture.user().getId())
        .param("key", key)
        .update();
  }

  private void assertError(HttpResponse<String> response, int status, String code)
      throws Exception {
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
    assertThat(json.readTree(response.body()).required("code").textValue()).isEqualTo(code);
    assertThat(response.body()).doesNotContain("gameId", "openedAt", "closesAt");
  }

  private HttpResponse<String> postReplacement(ExpiredReplacementFixture setup) throws Exception {
    return new AddKickHttpFixture(port, json, jdbc, users, logins)
        .postAt(
            setup.fixture(),
            setup.currentRoomId(),
            setup.key(),
            setup.fixture().choiceIds(),
            setup.fixture().choiceIds().getFirst());
  }

  private ReplacementBefore replacementBefore(ExpiredReplacementFixture setup) {
    long oldGameId =
        jdbc.sql(
                "select game_id from publication_idempotency where user_id = :userId and request_key = :key")
            .param("userId", setup.fixture().user().getId())
            .param("key", setup.key())
            .query(Long.class)
            .single();
    UUID oldClaimId =
        jdbc.sql(
                "select claim_id from publication_idempotency where user_id = :userId and request_key = :key")
            .param("userId", setup.fixture().user().getId())
            .param("key", setup.key())
            .query(UUID.class)
            .single();
    return new ReplacementBefore(
        oldGameId,
        oldClaimId,
        roomSnapshot(setup.fixture().roomId()),
        roomSnapshot(setup.currentRoomId()),
        gameSnapshot(oldGameId));
  }

  private long assertCompletedReplacement(
      ExpiredReplacementFixture setup, ReplacementBefore before) {
    Long currentGameId =
        jdbc.sql(
                "select game_id from publication_idempotency where user_id = :userId and request_key = :key")
            .param("userId", setup.fixture().user().getId())
            .param("key", setup.key())
            .query(Long.class)
            .single();
    UUID currentClaimId =
        jdbc.sql(
                "select claim_id from publication_idempotency where user_id = :userId and request_key = :key")
            .param("userId", setup.fixture().user().getId())
            .param("key", setup.key())
            .query(UUID.class)
            .single();
    Instant currentExpiry =
        jdbc.sql(
                "select record_expires_at from publication_idempotency where user_id = :userId and request_key = :key")
            .param("userId", setup.fixture().user().getId())
            .param("key", setup.key())
            .query(Instant.class)
            .single();
    Instant expectedExpiry =
        jdbc.sql("select opened_at + interval '24 hours 35 minutes' from rooms where id = :roomId")
            .param("roomId", setup.currentRoomId())
            .query(Instant.class)
            .single();
    assertThat(currentGameId).isNotEqualTo(before.oldGameId());
    assertThat(currentClaimId).isNotEqualTo(before.oldClaimId());
    assertThat(currentExpiry).isEqualTo(expectedExpiry);
    assertThat(
            jdbc.sql("select room_id from games where id = :gameId")
                .param("gameId", currentGameId)
                .query(Long.class)
                .single())
        .isEqualTo(setup.currentRoomId());
    assertThat(roomSnapshot(setup.fixture().roomId())).isEqualTo(before.originalRoom());
    assertThat(roomSnapshot(setup.currentRoomId())).isEqualTo(before.currentRoom());
    assertThat(gameSnapshot(before.oldGameId())).isEqualTo(before.oldGame());
    return currentGameId;
  }

  private String gameSnapshot(long gameId) {
    return jdbc.sql(
            """
            select to_jsonb(g)::text || '|' || coalesce((
                select jsonb_agg(to_jsonb(c) order by c.id)::text
                from choices c where c.game_id = g.id
            ), '[]')
            from games g where g.id = :gameId
            """)
        .param("gameId", gameId)
        .query(String.class)
        .single();
  }

  private AtomicBoolean observeIncompleteGameFailure() {
    AtomicBoolean observed = new AtomicBoolean();
    willAnswer(
            invocation -> {
              try {
                return invocation.callRealMethod();
              } catch (DomainException exception) {
                assertThat(exception.getErrorCode()).isEqualTo(GameErrorCode.INCOMPLETE_GAME);
                observed.set(true);
                throw exception;
              }
            })
        .given(addKickService)
        .add(any(), any(), any());
    return observed;
  }

  private AtomicBoolean observeChoiceAssemblyFailure() {
    AtomicBoolean observed = new AtomicBoolean();
    willAnswer(
            invocation -> {
              try {
                return invocation.callRealMethod();
              } catch (RuntimeException exception) {
                assertThat(exception).hasStackTraceContaining("choice assembly failure");
                observed.set(true);
                throw exception;
              }
            })
        .given(addKickService)
        .add(any(), any(), any());
    return observed;
  }

  private ExpiredReplacementFixture expiredReplacementFixture() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    assertThat(post(fixture, key, fixture.choiceIds()).statusCode()).isEqualTo(201);
    Instant expiry =
        jdbc.sql(
                "select record_expires_at from publication_idempotency where user_id = :userId and request_key = :key")
            .param("userId", fixture.user().getId())
            .param("key", key)
            .query(Instant.class)
            .single();
    jdbc.sql("update rooms set status = 'CLOSED' where id = :roomId")
        .param("roomId", fixture.roomId())
        .update();
    long currentRoomId =
        jdbc.sql(
                """
                insert into rooms
                    (host_user_id, selected_reward_preset_id, status, result_state, opened_at, closes_at, created_at)
                values (:userId, :rewardId, 'OPEN', 'NOT_READY', :openedAt, :closesAt, clock_timestamp())
                returning id
                """)
            .param("userId", fixture.user().getId())
            .param("rewardId", fixture.rewardId())
            .param("openedAt", expiry.minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
            .param("closesAt", expiry.plus(Duration.ofHours(23)).atOffset(ZoneOffset.UTC))
            .query(Long.class)
            .single();
    copyOriginalFirstGame(fixture, currentRoomId);
    return new ExpiredReplacementFixture(fixture, key, currentRoomId, expiry);
  }

  private void copyOriginalFirstGame(AddKickHttpFixture.Fixture fixture, long targetRoomId) {
    long sourceGameId =
        jdbc.sql("select id from games where room_id = :roomId order by id limit 1")
            .param("roomId", fixture.roomId())
            .query(Long.class)
            .single();
    long targetGameId =
        jdbc.sql(
                """
                insert into games (room_id, question_preset_id, question_text_snapshot, created_at)
                select :targetRoomId, question_preset_id, question_text_snapshot, created_at
                from games where id = :sourceGameId
                returning id
                """)
            .param("targetRoomId", targetRoomId)
            .param("sourceGameId", sourceGameId)
            .query(Long.class)
            .single();
    jdbc.sql(
            """
            insert into choices (game_id, position, text_snapshot)
            select :targetGameId, position, text_snapshot
            from choices where game_id = :sourceGameId order by position
            """)
        .param("targetGameId", targetGameId)
        .param("sourceGameId", sourceGameId)
        .update();
    jdbc.sql(
            """
            update games set correct_choice_id = (
                select target.id from choices target
                where target.game_id = :targetGameId
                  and target.position = (
                    select source.position from choices source
                    where source.id = (select correct_choice_id from games where id = :sourceGameId)
                  )
            )
            where id = :targetGameId
            """)
        .param("targetGameId", targetGameId)
        .param("sourceGameId", sourceGameId)
        .update();
  }

  private void installChoiceAssemblyFailureTrigger() {
    jdbc.sql(
            """
            create or replace function nk76_choice_assembly_failure() returns trigger language plpgsql as $$
            begin
              raise exception 'choice assembly failure';
            end;
            $$
            """)
        .update();
    jdbc.sql(
            """
            create trigger nk76_choice_assembly_failure
            before insert on choices
            for each row execute function nk76_choice_assembly_failure()
            """)
        .update();
  }

  private void dropChoiceAssemblyFailureTrigger() {
    jdbc.sql("drop trigger if exists nk76_choice_assembly_failure on choices").update();
    jdbc.sql("drop function if exists nk76_choice_assembly_failure()").update();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class AssemblyFaultConfig {
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
      listeners.appendListeners(
          EventType.POST_INSERT,
          new PostInsertEventListener() {
            @Override
            public void onPostInsert(PostInsertEvent event) {
              AssemblyFault fault = active.get();
              if (fault != null) {
                fault.afterInsert(event.getEntity());
              }
            }

            @Override
            public boolean requiresPostCommitHandling(EntityPersister persister) {
              return false;
            }
          });
      listeners.appendListeners(
          EventType.FLUSH,
          (FlushEventListener)
              event -> {
                AssemblyFault fault = active.get();
                if (fault != null) {
                  fault.afterFlush();
                }
              });
    }

    private void activate(String stage, Runnable fail) {
      active.set(new AssemblyFault(stage, fail));
    }

    private void clear() {
      active.set(null);
    }
  }

  private static final class AssemblyFault {
    private final String stage;
    private final Runnable fail;
    private Game game;
    private int insertedChoices;

    private AssemblyFault(String stage, Runnable fail) {
      this.stage = stage;
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

    private void afterFlush() {
      if (game != null && insertedChoices == 4 && game.getCorrectChoiceId() != null) {
        reach("CORRECT_AFTER_FLUSH");
      }
    }

    private void reach(String reached) {
      if (stage.equals(reached)) {
        fail.run();
      }
    }
  }

  private record ExpiredReplacementFixture(
      AddKickHttpFixture.Fixture fixture, String key, long currentRoomId, Instant expiry) {}

  private record ReplacementBefore(
      long oldGameId, UUID oldClaimId, String originalRoom, String currentRoom, String oldGame) {}

  private String roomSnapshot(long roomId) {
    return jdbc.sql(
            """
            select concat_ws(':', host_user_id, status, result_state, selected_reward_preset_id, opened_at, closes_at)
            from rooms
            where id = :roomId
            """)
        .param("roomId", roomId)
        .query(String.class)
        .single();
  }

  private String fullSnapshot(AddKickHttpFixture.Fixture fixture, String key) {
    return jdbc.sql(
            """
            select
                coalesce((
                    select jsonb_agg(to_jsonb(r) order by r.id)::text
                    from rooms r
                    where r.id = :roomId or r.host_user_id = :userId
                ), '[]')
                || '|' || coalesce((
                    select jsonb_agg(to_jsonb(g) order by g.id)::text
                    from games g
                    join rooms r on r.id = g.room_id
                    where r.id = :roomId or r.host_user_id = :userId
                ), '[]')
                || '|' || coalesce((
                    select jsonb_agg(to_jsonb(c) order by c.id)::text
                    from choices c
                    join games g on g.id = c.game_id
                    join rooms r on r.id = g.room_id
                    where r.id = :roomId or r.host_user_id = :userId
                ), '[]')
                || '|' || coalesce((
                    select to_jsonb(p)::text
                    from publication_idempotency p
                    where p.user_id = :userId and p.request_key = :key
                ), '')
            """)
        .param("roomId", fixture.roomId())
        .param("userId", fixture.user().getId())
        .param("key", key)
        .query(String.class)
        .single();
  }
}

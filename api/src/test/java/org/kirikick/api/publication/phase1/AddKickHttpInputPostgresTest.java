package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.domain.UserStatus;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(OpenRoomHttpPostgresTest.DomainEntities.class)
class AddKickHttpInputPostgresTest {
  @LocalServerPort private int port;
  @Autowired private ObjectMapper json;
  @Autowired private JdbcClient jdbc;
  @Autowired private UserRepository users;
  @Autowired private LoginSessionService logins;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    registry.add("spring.datasource.url", AddKickHttpInputPostgresTest::jdbcUrl);
    registry.add("spring.datasource.username", PostgresTestDatabase.container()::getUsername);
    registry.add("spring.datasource.password", PostgresTestDatabase.container()::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.default-schema", () -> "nk76_http_input");
    registry.add("publication.grace", () -> "PT5M");
  }

  @BeforeEach
  void requirePostgres17() {
    assertThat(jdbc.sql("show server_version").query(String.class).single()).startsWith("17.");
  }

  @Test
  @DisplayName("추가 출제는 완전한 본문에서도 문자열 ID와 정확히 네 개의 고유 보기를 강제한다")
  void postRejectsInvalidInputWithoutAnyPublicationMutation() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture().fixture();
    PublicationRows before = publicationRows(fixture);
    String choices = quotedChoices(fixture.choiceIds());
    List<String> invalidBodies =
        List.of(
            body(fixture, "1", choices, quote(fixture.choiceIds().getFirst())),
            body(fixture, "1.5", choices, quote(fixture.choiceIds().getFirst())),
            body(fixture, "null", choices, quote(fixture.choiceIds().getFirst())),
            body(fixture, quote("0"), choices, quote(fixture.choiceIds().getFirst())),
            body(fixture, quote("-1"), choices, quote(fixture.choiceIds().getFirst())),
            body(
                fixture,
                quote("9223372036854775808"),
                choices,
                quote(fixture.choiceIds().getFirst())),
            body(
                fixture,
                quote(fixture.questionId()),
                quotedChoices(fixture.choiceIds().subList(0, 3)),
                quote(fixture.choiceIds().getFirst())),
            body(
                fixture,
                quote(fixture.questionId()),
                quotedChoices(
                    List.of(
                        fixture.choiceIds().get(0),
                        fixture.choiceIds().get(1),
                        fixture.choiceIds().get(2),
                        fixture.choiceIds().get(3),
                        fixture.choiceIds().get(0))),
                quote(fixture.choiceIds().getFirst())),
            body(
                fixture,
                quote(fixture.questionId()),
                quotedChoices(
                    List.of(
                        fixture.choiceIds().getFirst(),
                        fixture.choiceIds().getFirst(),
                        fixture.choiceIds().get(2),
                        fixture.choiceIds().get(3))),
                quote(fixture.choiceIds().getFirst())),
            body(fixture, quote(fixture.questionId()), choices, quote("999999999")),
            body(
                    fixture,
                    quote(fixture.questionId()),
                    choices,
                    quote(fixture.choiceIds().getFirst()))
                .replace("}", ",\"rewardPresetId\":\"1\"}"),
            body(
                    fixture,
                    quote(fixture.questionId()),
                    choices,
                    quote(fixture.choiceIds().getFirst()))
                .replace("}", ",\"unknown\":true}"));
    for (String body : invalidBodies) {
      HttpResponse<String> response =
          fixture().postRaw(fixture, UUID.randomUUID().toString(), true, body);
      assertError(response, 400, "INVALID_PUBLICATION_INPUT");
      assertThat(publicationRows(fixture)).isEqualTo(before);
    }
  }

  @Test
  @DisplayName("존재하지 않는 질문 preset은 추가 출제를 저장하지 않고 404를 반환한다")
  void postRejectsMissingQuestionPreset() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture().fixture();
    String body =
        json.writeValueAsString(
            new Request(
                "999999999",
                fixture.choiceIds().stream().map(String::valueOf).toList(),
                String.valueOf(fixture.choiceIds().getFirst())));
    PublicationRows before = publicationRows(fixture);
    HttpResponse<String> response =
        fixture().postRaw(fixture, UUID.randomUUID().toString(), true, body);
    assertError(response, 404, "PRESET_NOT_FOUND");
    assertThat(publicationRows(fixture)).isEqualTo(before);
  }

  @Test
  @DisplayName("현재 방장이 아닌 호출자와 비활성 호출자는 추가 출제 결과를 보지 못한다")
  void postHidesPublicationFromUnauthorizedAndInactiveCallers() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture().fixture();
    String body = validBody(fixture);
    PublicationSnapshot before = snapshot(fixture);
    User other =
        users.saveAndFlush(User.create("o" + UUID.randomUUID().toString().substring(0, 6), null));
    HttpResponse<String> forbidden =
        fixture()
            .postRawAs(
                fixture, UUID.randomUUID().toString(), logins.login(other).accessToken(), body);
    assertError(forbidden, 403, "PUBLICATION_FORBIDDEN");
    assertThat(snapshot(fixture)).isEqualTo(before);

    ReflectionTestUtils.setField(fixture.user(), "status", UserStatus.SUSPENDED);
    users.saveAndFlush(fixture.user());
    HttpResponse<String> inactive =
        fixture().postRaw(fixture, UUID.randomUUID().toString(), true, body);
    assertError(inactive, 403, "ACCOUNT_INACTIVE");
    assertThat(snapshot(fixture)).isEqualTo(before);
  }

  @Test
  @DisplayName("현재 대상이 없거나 공개 완료 상태가 아니면 새 기록 없이 404를 반환한다")
  void postRejectsMissingAndUnpublishedCurrentRoomWithoutMutation() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture().fixture();
    PublicationSnapshot before = snapshot(fixture);
    HttpResponse<String> missing =
        fixture()
            .postAt(
                fixture,
                Long.MAX_VALUE,
                UUID.randomUUID().toString(),
                fixture.choiceIds(),
                fixture.choiceIds().getFirst());
    assertError(missing, 404, "ROOM_NOT_FOUND");
    assertThat(snapshot(fixture)).isEqualTo(before);

    jdbc.sql("update rooms set opened_at = null, closes_at = null where id = :roomId")
        .param("roomId", fixture.roomId())
        .update();
    PublicationSnapshot unpublished = snapshot(fixture);
    HttpResponse<String> response = fixture().post(fixture, UUID.randomUUID().toString());
    assertError(response, 404, "ROOM_NOT_FOUND");
    assertThat(snapshot(fixture)).isEqualTo(unpublished);
  }

  @Test
  @DisplayName("비유한 공개 시각과 잘못된 보기 및 기존 질문은 새 저장 없이 거절한다")
  void postRejectsCorruptRoomChoicesAndDuplicateQuestionWithoutMutation() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture().fixture();
    jdbc.sql(
            "update rooms set opened_at = now(), closes_at = now() + interval '23 hours' where id ="
                + " :roomId")
        .param("roomId", fixture.roomId())
        .update();
    PublicationSnapshot non24 = snapshot(fixture);
    HttpResponse<String> non24Response = fixture().post(fixture, UUID.randomUUID().toString());
    assertError(non24Response, 404, "ROOM_NOT_FOUND");
    assertThat(snapshot(fixture)).isEqualTo(non24);

    jdbc.sql("update rooms set opened_at = '-infinity', closes_at = 'infinity' where id = :roomId")
        .param("roomId", fixture.roomId())
        .update();
    PublicationSnapshot corrupt = snapshot(fixture);
    HttpResponse<String> corruptResponse = fixture().post(fixture, UUID.randomUUID().toString());
    assertError(corruptResponse, 404, "ROOM_NOT_FOUND");
    assertThat(snapshot(fixture)).isEqualTo(corrupt);

    jdbc.sql(
            "update rooms set opened_at = now(), closes_at = now() + interval '24 hours' where id ="
                + " :roomId")
        .param("roomId", fixture.roomId())
        .update();
    PublicationSnapshot choicesBefore = snapshot(fixture);
    String missingChoiceBody =
        body(
            fixture,
            quote(fixture.questionId()),
            quotedChoices(
                List.of(
                    fixture.choiceIds().get(0),
                    fixture.choiceIds().get(1),
                    fixture.choiceIds().get(2),
                    Long.MAX_VALUE)),
            quote(fixture.choiceIds().getFirst()));
    HttpResponse<String> missingChoiceResponse =
        fixture().postRaw(fixture, UUID.randomUUID().toString(), true, missingChoiceBody);
    assertError(missingChoiceResponse, 404, "PRESET_NOT_FOUND");
    assertThat(snapshot(fixture)).isEqualTo(choicesBefore);

    long foreignQuestion =
        jdbc.sql("insert into question_presets(question_text) values ('foreign') returning id")
            .query(Long.class)
            .single();
    long foreignChoice =
        jdbc.sql(
                "insert into question_preset_choices(question_preset_id, choice_text) values"
                    + " (:questionId, 'foreign') returning id")
            .param("questionId", foreignQuestion)
            .query(Long.class)
            .single();
    String wrongQuestionChoiceBody =
        body(
            fixture,
            quote(fixture.questionId()),
            quotedChoices(
                List.of(
                    foreignChoice,
                    fixture.choiceIds().get(1),
                    fixture.choiceIds().get(2),
                    fixture.choiceIds().get(3))),
            quote(foreignChoice));
    HttpResponse<String> wrongQuestionChoice =
        fixture().postRaw(fixture, UUID.randomUUID().toString(), true, wrongQuestionChoiceBody);
    assertError(wrongQuestionChoice, 400, "INVALID_PUBLICATION_INPUT");
    assertThat(snapshot(fixture)).isEqualTo(choicesBefore);

    long existingQuestion =
        jdbc.sql("select question_preset_id from games where id = :gameId")
            .param("gameId", fixture.existingGameId())
            .query(Long.class)
            .single();
    List<Long> existingChoices =
        jdbc.sql(
                "select id from question_preset_choices where question_preset_id = :questionId"
                    + " order by id")
            .param("questionId", existingQuestion)
            .query(Long.class)
            .list();
    String duplicateBody =
        body(
            fixture,
            quote(existingQuestion),
            quotedChoices(existingChoices),
            quote(existingChoices.getFirst()));
    HttpResponse<String> duplicate =
        fixture().postRaw(fixture, UUID.randomUUID().toString(), true, duplicateBody);
    assertError(duplicate, 409, "QUESTION_ALREADY_PUBLISHED");
    assertThat(snapshot(fixture)).isEqualTo(choicesBefore);
  }

  @Test
  @DisplayName("실제 HTTP의 누락 또는 공백 요청 키는 출제 기록을 남기지 않는다")
  void postRejectsMissingAndWhitespaceRequestKeysWithoutMutation() throws Exception {
    AddKickHttpFixture.Fixture fixture = fixture().fixture();
    PublicationSnapshot before = snapshot(fixture);
    assertError(
        fixture().postRaw(fixture, null, true, validBody(fixture)),
        400,
        "INVALID_PUBLICATION_REQUEST_KEY");
    assertThat(snapshot(fixture)).isEqualTo(before);
    assertError(
        fixture()
            .postRaw(
                fixture,
                UUID.randomUUID().toString().substring(0, 18)
                    + " "
                    + UUID.randomUUID().toString().substring(19),
                true,
                validBody(fixture)),
        400,
        "INVALID_PUBLICATION_REQUEST_KEY");
    assertThat(snapshot(fixture)).isEqualTo(before);
  }

  private AddKickHttpFixture fixture() {
    return new AddKickHttpFixture(port, json, jdbc, users, logins);
  }

  private PublicationSnapshot snapshot(AddKickHttpFixture.Fixture fixture) {
    RoomState room =
        jdbc.sql(
                "select status, result_state, selected_reward_preset_id, opened_at::text,"
                    + " closes_at::text from rooms where id = :roomId")
            .param("roomId", fixture.roomId())
            .query((row, rowNum) ->
                new RoomState(
                    row.getString("status"),
                    row.getString("result_state"),
                    row.getLong("selected_reward_preset_id"),
                    row.getString("opened_at"),
                    row.getString("closes_at")))
            .single();
    return new PublicationSnapshot(publicationRows(fixture), room);
  }

  private String validBody(AddKickHttpFixture.Fixture fixture) throws Exception {
    return json.writeValueAsString(
        new Request(
            String.valueOf(fixture.questionId()),
            fixture.choiceIds().stream().map(String::valueOf).toList(),
            String.valueOf(fixture.choiceIds().getFirst())));
  }

  private PublicationRows publicationRows(AddKickHttpFixture.Fixture fixture) {
    long rooms =
        jdbc.sql("select count(*) from rooms where host_user_id = :userId")
            .param("userId", fixture.user().getId())
            .query(Long.class)
            .single();
    long games =
        jdbc.sql("select count(*) from games where room_id = :roomId")
            .param("roomId", fixture.roomId())
            .query(Long.class)
            .single();
    long choices =
        jdbc.sql(
                "select count(*) from choices c join games g on g.id = c.game_id where g.room_id ="
                    + " :roomId")
            .param("roomId", fixture.roomId())
            .query(Long.class)
            .single();
    long records =
        jdbc.sql("select count(*) from publication_idempotency where user_id = :userId")
            .param("userId", fixture.user().getId())
            .query(Long.class)
            .single();
    return new PublicationRows(rooms, games, choices, records);
  }

  private static String body(
      AddKickHttpFixture.Fixture fixture,
      String questionId,
      String choiceIds,
      String correctChoiceId) {
    return "{\"questionPresetId\":"
        + questionId
        + ",\"choiceIds\":"
        + choiceIds
        + ",\"correctChoiceId\":"
        + correctChoiceId
        + "}";
  }

  private static String quotedChoices(List<Long> choiceIds) {
    return choiceIds.stream()
        .map(AddKickHttpInputPostgresTest::quote)
        .collect(Collectors.joining(",", "[", "]"));
  }

  private static String quote(long id) {
    return quote(Long.toString(id));
  }

  private static String quote(String value) {
    return "\"" + value + "\"";
  }

  private record PublicationRows(long rooms, long games, long choices, long records) {}

  private record PublicationSnapshot(PublicationRows rows, RoomState room) {}

  private record RoomState(
      String status, String result, long rewardId, String openedAt, String closesAt) {}

  private void assertError(HttpResponse<String> response, int status, String code)
      throws Exception {
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
    JsonNode error = json.readTree(response.body());
    assertThat(error.required("code").textValue()).isEqualTo(code);
  }

  private static String jdbcUrl() {
    return AddKickHttpFixture.withCurrentSchema(
        PostgresTestDatabase.container().getJdbcUrl(), "nk76_http_input");
  }

  private record Request(String questionPresetId, List<String> choiceIds, String correctChoiceId) {}
}

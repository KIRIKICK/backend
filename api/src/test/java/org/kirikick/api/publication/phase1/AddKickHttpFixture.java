package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.kirikick.api.auth.domain.LoginGrant;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Shared actual-auth fixture for adding a game to an already published room. */
final class AddKickHttpFixture {
  private static final String ORIGIN = "http://localhost:5173";
  private static final String CSRF_HEADER = "X-XSRF-TOKEN";
  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

  static String withCurrentSchema(String jdbcUrl, String schema) {
    return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
  }

  private final int port;
  private final ObjectMapper json;
  private final JdbcClient jdbc;
  private final UserRepository users;
  private final LoginSessionService logins;

  AddKickHttpFixture(
      int port,
      ObjectMapper json,
      JdbcClient jdbc,
      UserRepository users,
      LoginSessionService logins) {
    this.port = port;
    this.json = json;
    this.jdbc = jdbc;
    this.users = users;
    this.logins = logins;
  }

  Fixture fixture() throws Exception {
    User user =
        users.saveAndFlush(User.create("ka" + UUID.randomUUID().toString().substring(0, 6), null));
    long rewardId =
        jdbc.sql(
                "insert into reward_presets (title, description) values (:title, 'synthetic')"
                    + " returning id")
            .param("title", "reward-" + UUID.randomUUID())
            .query(Long.class)
            .single();
    long alternateRewardId =
        jdbc.sql(
                "insert into reward_presets (title, description) values (:title, 'synthetic')"
                    + " returning id")
            .param("title", "reward-alt-" + UUID.randomUUID())
            .query(Long.class)
            .single();
    long existingQuestionId = question("existing-question-");
    List<Long> existingChoices = choices(existingQuestionId);
    long roomId =
        jdbc.sql(
                "insert into rooms(host_user_id, selected_reward_preset_id, status, result_state,"
                    + " opened_at, closes_at, created_at) values (:userId, :rewardId, 'OPEN',"
                    + " 'NOT_READY', now(), now() + interval '24 hours', now()) returning id")
            .param("userId", user.getId())
            .param("rewardId", rewardId)
            .query(Long.class)
            .single();
    long existingGameId =
        jdbc.sql(
                "insert into games(room_id, question_preset_id, question_text_snapshot, created_at)"
                    + " values (:roomId, :questionId, 'existing snapshot', now()) returning id")
            .param("roomId", roomId)
            .param("questionId", existingQuestionId)
            .query(Long.class)
            .single();
    List<Long> existingGameChoices = gameChoices(existingGameId);
    jdbc.sql("update games set correct_choice_id = :choiceId where id = :gameId")
        .param("choiceId", existingGameChoices.getFirst())
        .param("gameId", existingGameId)
        .update();
    long questionId = question("candidate-question-");
    List<Long> choiceIds = choices(questionId);
    Fixture fixture =
        new Fixture(
            user,
            logins.login(user),
            roomId,
            existingGameId,
            rewardId,
            alternateRewardId,
            questionId,
            List.copyOf(choiceIds));
    assertThat(
            HTTP.send(
                    HttpRequest.newBuilder(backend("/users/me/profile"))
                        .timeout(TIMEOUT)
                        .header(
                            HttpHeaders.AUTHORIZATION, "Bearer " + fixture.grant().accessToken())
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode())
        .withFailMessage("known authenticated GET must work before POST assessed")
        .isEqualTo(200);
    return fixture;
  }

  HttpResponse<String> post(Fixture fixture, String key) throws Exception {
    return post(fixture, key, fixture.choiceIds(), fixture.choiceIds().getFirst());
  }

  HttpResponse<String> post(Fixture fixture, String key, List<Long> choiceIds, long correctChoiceId)
      throws Exception {
    return postRaw(fixture, key, true, choiceIds, correctChoiceId, null);
  }

  HttpResponse<String> postRaw(Fixture fixture, String key, boolean authenticated, String body)
      throws Exception {
    return send(
        fixture, fixture.roomId(), key, authenticated ? fixture.grant().accessToken() : null, body);
  }

  HttpResponse<String> postRawAs(Fixture fixture, String key, String accessToken, String body)
      throws Exception {
    return send(fixture, fixture.roomId(), key, accessToken, body);
  }

  HttpResponse<String> postAt(
      Fixture fixture, long roomId, String key, List<Long> choiceIds, long correctChoiceId)
      throws Exception {
    String body =
        json.writeValueAsString(
            new Request(
                String.valueOf(fixture.questionId()),
                choiceIds.stream().map(String::valueOf).toList(),
                String.valueOf(correctChoiceId)));
    return send(fixture, roomId, key, fixture.grant().accessToken(), body);
  }

  HttpResponse<String> postAs(
      Fixture fixture,
      LoginGrant grant,
      long roomId,
      String key,
      List<Long> choiceIds,
      long correctChoiceId)
      throws Exception {
    String body =
        json.writeValueAsString(
            new Request(
                String.valueOf(fixture.questionId()),
                choiceIds.stream().map(String::valueOf).toList(),
                String.valueOf(correctChoiceId)));
    return send(fixture, roomId, key, grant.accessToken(), body);
  }

  HttpResponse<String> postRaw(
      Fixture fixture,
      String key,
      boolean authenticated,
      List<Long> choiceIds,
      long correctChoiceId,
      String extraJson)
      throws Exception {
    String body =
        json.writeValueAsString(
            new Request(
                String.valueOf(fixture.questionId()),
                choiceIds.stream().map(String::valueOf).toList(),
                String.valueOf(correctChoiceId)));
    if (extraJson != null) {
      body = body.substring(0, body.length() - 1) + "," + extraJson + "}";
    }
    return send(
        fixture, fixture.roomId(), key, authenticated ? fixture.grant().accessToken() : null, body);
  }

  private HttpResponse<String> send(
      Fixture fixture, long roomId, String key, String accessToken, String body) throws Exception {
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    String csrf = csrf(cookies);
    HttpRequest.Builder request =
        HttpRequest.newBuilder(backend("/rooms/" + roomId + "/games"))
            .timeout(TIMEOUT)
            .header(HttpHeaders.ORIGIN, ORIGIN)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .header(CSRF_HEADER, csrf)
            .header(HttpHeaders.COOKIE, cookieHeader(cookies))
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (accessToken != null) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
    }
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private long question(String prefix) {
    return jdbc.sql("insert into question_presets (question_text) values (:text) returning id")
        .param("text", prefix + UUID.randomUUID())
        .query(Long.class)
        .single();
  }

  private List<Long> choices(long questionId) {
    return jdbc.sql(
            "insert into question_preset_choices(question_preset_id, choice_text) "
                + "select :questionId, 'choice-' || generate_series(1, 4) returning id")
        .param("questionId", questionId)
        .query(Long.class)
        .list();
  }

  private List<Long> gameChoices(long gameId) {
    return jdbc.sql(
            "insert into choices(game_id, position, text_snapshot) "
                + "select :gameId, generate_series(0, 3), 'existing' returning id")
        .param("gameId", gameId)
        .query(Long.class)
        .list();
  }

  private URI backend(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private String csrf(CookieManager cookies) throws Exception {
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(backend("/auth/csrf")).timeout(TIMEOUT).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    cookies.put(backend("/auth/csrf"), response.headers().map());
    return json.readTree(response.body()).required("token").textValue();
  }

  private static String cookieHeader(CookieManager cookies) {
    List<String> values = new ArrayList<>();
    cookies
        .getCookieStore()
        .getCookies()
        .forEach(cookie -> values.add(cookie.getName() + "=" + cookie.getValue()));
    return String.join("; ", values);
  }

  record Fixture(
      User user,
      LoginGrant grant,
      long roomId,
      long existingGameId,
      long rewardId,
      long alternateRewardId,
      long questionId,
      List<Long> choiceIds) {}

  private record Request(String questionPresetId, List<String> choiceIds, String correctChoiceId) {}
}

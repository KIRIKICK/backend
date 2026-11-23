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

/** Shared actual-auth request fixture; it intentionally has no transaction fault behavior. */
final class NkCreateHttpFixture {

  private static final String ORIGIN = "http://localhost:5173";
  private static final String CSRF_HEADER = "X-XSRF-TOKEN";
  private static final Duration TIMEOUT = Duration.ofSeconds(15);

  static String withCurrentSchema(String jdbcUrl, String schema) {
    return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
  }

  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

  private final int port;
  private final ObjectMapper json;
  private final JdbcClient jdbc;
  private final UserRepository users;
  private final LoginSessionService logins;

  NkCreateHttpFixture(
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
        users.saveAndFlush(User.create("nk" + UUID.randomUUID().toString().substring(0, 6), null));
    long rewardId =
        jdbc.sql(
                "insert into reward_presets (title, description) values (:title, :description) returning id")
            .param("title", "reward-" + UUID.randomUUID())
            .param("description", "synthetic")
            .query(Long.class)
            .single();
    long alternateRewardId =
        jdbc.sql(
                "insert into reward_presets (title, description) values (:title, :description) returning id")
            .param("title", "reward-alt-" + UUID.randomUUID())
            .param("description", "synthetic")
            .query(Long.class)
            .single();
    long questionId =
        jdbc.sql("insert into question_presets (question_text) values (:text) returning id")
            .param("text", "question-" + UUID.randomUUID())
            .query(Long.class)
            .single();
    List<Long> choiceIds =
        jdbc.sql(
                "insert into question_preset_choices (question_preset_id, choice_text) "
                    + "select :questionId, 'choice-' || generate_series(1, 4) returning id")
            .param("questionId", questionId)
            .query(Long.class)
            .list();
    Fixture fixture =
        new Fixture(
            user,
            logins.login(user),
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
        .withFailMessage("known authenticated GET must work before POST is assessed")
        .isEqualTo(200);
    return fixture;
  }

  HttpResponse<String> post(
      Fixture fixture, String key, List<Long> choiceIds, long correctChoiceId, long rewardId)
      throws Exception {
    return postRaw(fixture, key, true, choiceIds, correctChoiceId, rewardId);
  }

  HttpResponse<String> postRaw(Fixture fixture, String key, boolean authenticated)
      throws Exception {
    return postRaw(
        fixture,
        key,
        authenticated,
        fixture.choiceIds(),
        fixture.choiceIds().getFirst(),
        fixture.rewardId());
  }

  URI backend(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private HttpResponse<String> postRaw(
      Fixture fixture,
      String key,
      boolean authenticated,
      List<Long> choiceIds,
      long correctChoiceId,
      long rewardId)
      throws Exception {
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    String csrf = csrf(cookies);
    String body =
        json.writeValueAsString(
            new Request(
                String.valueOf(fixture.questionId()),
                choiceIds.stream().map(String::valueOf).toList(),
                String.valueOf(correctChoiceId),
                String.valueOf(rewardId)));
    HttpRequest.Builder request =
        HttpRequest.newBuilder(backend("/games"))
            .timeout(TIMEOUT)
            .header(HttpHeaders.ORIGIN, ORIGIN)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .header(CSRF_HEADER, csrf)
            .header(HttpHeaders.COOKIE, cookieHeader(cookies))
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (authenticated) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.grant().accessToken());
    }
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
      long rewardId,
      long alternateRewardId,
      long questionId,
      List<Long> choiceIds) {}

  private record Request(
      String questionPresetId,
      List<String> choiceIds,
      String correctChoiceId,
      String rewardPresetId) {}
}

package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;

class MvpPresetSeedMigrationPostgresTest {

  private static final PublicationPhase1Postgres POSTGRES = new PublicationPhase1Postgres();
  private static final List<SeedQuestion> QUESTIONS =
      List.of(
          new SeedQuestion(
              "{name}이(가) 주말에 제일 많이 하는 일은?", List.of("넷플릭스 정주행", "카페에서 수다", "하루 종일 자기", "즉흥 여행")),
          new SeedQuestion("{name}이(가) 제일 좋아하는 야식은?", List.of("치킨", "떡볶이", "라면", "족발")),
          new SeedQuestion(
              "{name}이(가) 하루 동안 투명인간이 된다면 가장 먼저 할 일은?",
              List.of("몰래 영화관 가기", "친구들 놀래키기", "놀이공원 프리패스", "집에서 낮잠")),
          new SeedQuestion("{name}이(가) 요즘 제일 자주 듣는 음악은?", List.of("발라드", "케이팝 댄스", "힙합", "인디 밴드")),
          new SeedQuestion(
              "{name}이(가) 아침에 눈뜨자마자 하는 일은?", List.of("휴대폰 확인", "물 한 잔", "다시 잠들기", "스트레칭")),
          new SeedQuestion("{name}이(가) 가장 가고 싶어 하는 여행지는?", List.of("제주도", "일본", "유럽", "동남아")),
          new SeedQuestion(
              "{name}이(가) 스트레스를 받을 때 가장 자주 하는 일은?", List.of("매운 거 먹기", "잠자기", "수다 떨기", "운동하기")),
          new SeedQuestion("{name}이(가) 제일 좋아하는 계절은?", List.of("봄", "여름", "가을", "겨울")),
          new SeedQuestion(
              "{name}이(가) 카페에서 제일 자주 시키는 음료는?", List.of("아이스 아메리카노", "라떼", "스무디", "차 종류")),
          new SeedQuestion("{name}이(가) 갖고 싶은 초능력은?", List.of("순간이동", "시간 멈추기", "마음 읽기", "하늘 날기")),
          new SeedQuestion(
              "{name}이(가) 복권에 당첨되면 제일 먼저 할 일은?", List.of("집 사기", "여행 가기", "부모님 선물", "일단 저축")),
          new SeedQuestion("{name}이(가) 키우고 싶은 동물은?", List.of("강아지", "고양이", "햄스터", "앵무새")));
  private static final List<List<String>> REWARDS =
      List.of(
          List.of("리액션 한가득", "방장이 1등에게 신나게 리액션해 줘요."),
          List.of("칭찬 세 마디", "방장이 1등에게 칭찬 세 마디를 들려줘요."),
          List.of("메뉴 결정권", "방장과 밥 먹을 때, 1등이 메뉴를 골라요."));

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.close();
  }

  @Test
  @DisplayName("V6: 빈 PostgreSQL 17 설치는 원문·보기 순서를 보존한 MVP 프리셋만 추가한다")
  void installsInitialPresetsWithGeneratedIdsAndOriginalChoiceOrder() throws SQLException {
    String schema = POSTGRES.newSchema();
    try (var pool = POSTGRES.openSchema(schema)) {
      var flyway = PublicationPhase1Postgres.flyway(pool, schema, "6");
      JdbcClient jdbc = JdbcClient.create(pool);

      assertThat(flyway.migrate().migrationsExecuted).isEqualTo(6);
      flyway.validate();
      assertThat(history(jdbc))
          .extracting(row -> row.get("version"))
          .containsExactly("1", "2", "3", "4", "5", "6");
      assertCatalog(jdbc, 0);
      for (String table :
          List.of("users", "rooms", "games", "choices", "publication_idempotency")) {
        assertThat(domainRows(jdbc).get(table)).isEmpty();
      }

      var before = domainRows(jdbc);
      assertThat(flyway.migrate().migrationsExecuted).isZero();
      assertThat(domainRows(jdbc)).isEqualTo(before);
    }
  }

  @Test
  @DisplayName("V6: V5 업그레이드는 기존 프리셋·방·킥·멱등 기록과 체크섬을 보존한다")
  void upgradesV5WithoutOverwritingExistingRowsOrAssumingPresetIds() throws SQLException {
    String schema = POSTGRES.newSchema();
    try (var pool = POSTGRES.openSchema(schema)) {
      JdbcClient jdbc = JdbcClient.create(pool);
      assertThat(PublicationPhase1Postgres.flyway(pool, schema, "5").migrate().migrationsExecuted)
          .isEqualTo(5);
      for (String table :
          List.of("question_presets", "question_preset_choices", "reward_presets")) {
        assertThat(domainRows(jdbc).get(table)).isEmpty();
      }
      insertLegacyRows(jdbc);
      var oldRows = domainRows(jdbc);
      var oldHistory = history(jdbc);

      var flyway = PublicationPhase1Postgres.flyway(pool, schema, "6");
      assertThat(flyway.migrate().migrationsExecuted).isOne();
      flyway.validate();
      assertThat(history(jdbc).subList(0, 5)).isEqualTo(oldHistory);
      assertThat(history(jdbc))
          .extracting(row -> row.get("version"))
          .containsExactly("1", "2", "3", "4", "5", "6");
      assertCatalog(jdbc, 1);
      var after = domainRows(jdbc);
      oldRows.forEach((table, rows) -> {
        if (List.of("question_presets", "question_preset_choices", "reward_presets")
            .contains(table)) {
          assertThat(after.get(table)).containsAll(rows);
        } else {
          assertThat(after.get(table)).isEqualTo(rows);
        }
      });
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"QUESTION", "REWARD"})
  @DisplayName("V6: 수동 등록된 후보 프리셋 정황이 있으면 병합 없이 중단하고 기존 데이터만 남긴다")
  void rejectsMatchingManualPresetsWithoutPartialSeedOrHistory(String type) throws SQLException {
    String schema = POSTGRES.newSchema();
    try (var pool = POSTGRES.openSchema(schema)) {
      JdbcClient jdbc = JdbcClient.create(pool);
      assertThat(PublicationPhase1Postgres.flyway(pool, schema, "5").migrate().migrationsExecuted)
          .isEqualTo(5);
      insertLegacyRows(jdbc);
      if (type.equals("QUESTION")) {
        SeedQuestion manual = QUESTIONS.getLast();
        jdbc.sql("update question_presets set question_text = ?").param(manual.text()).update();
        var ids =
            jdbc.sql("select id from question_preset_choices order by id").query(Long.class).list();
        for (int index = 0; index < ids.size(); index++) {
          jdbc.sql("update question_preset_choices set choice_text = ? where id = ?")
              .params(manual.choices().get(index), ids.get(index))
              .update();
        }
      } else {
        var manual = REWARDS.getLast();
        jdbc.sql("update reward_presets set title = ?, description = ?")
            .params(manual.getFirst(), manual.getLast())
            .update();
      }
      var oldRows = domainRows(jdbc);
      var oldHistory = history(jdbc);

      var flyway = PublicationPhase1Postgres.flyway(pool, schema, "6");
      assertThatThrownBy(flyway::migrate)
          .isInstanceOf(FlywayException.class)
          .rootCause()
          .hasMessageStartingWith("ERROR: MVP presets may already be registered");
      assertThat(domainRows(jdbc)).isEqualTo(oldRows);
      assertThat(history(jdbc)).isEqualTo(oldHistory);
      PublicationPhase1Postgres.flyway(pool, schema, "5").validate();
    }
  }

  private static void assertCatalog(JdbcClient jdbc, int existingPresets) {
    assertThat(jdbc.sql("select count(*) from question_presets").query(Long.class).single())
        .isEqualTo(12L + existingPresets);
    assertThat(jdbc.sql("select count(*) from question_preset_choices").query(Long.class).single())
        .isEqualTo(48L + 4L * existingPresets);
    assertThat(jdbc.sql("select count(*) from reward_presets").query(Long.class).single())
        .isEqualTo(3L + existingPresets);
    var questions =
        jdbc.sql("select id, question_text from question_presets order by id offset ?")
            .param(existingPresets)
            .query((row, number) -> new QuestionRow(row.getLong(1), row.getString(2)))
            .list();
    assertThat(questions)
        .extracting(QuestionRow::text)
        .containsExactlyElementsOf(QUESTIONS.stream().map(SeedQuestion::text).toList());
    for (int index = 0; index < QUESTIONS.size(); index++) {
      QuestionRow question = questions.get(index);
      assertThat(question.text()).startsWith("{name}이(가)");
      assertThat(
              jdbc.sql(
                      "select choice_text from question_preset_choices where question_preset_id = ? order by id")
                  .param(question.id())
                  .query(String.class)
                  .list())
          .containsExactlyElementsOf(QUESTIONS.get(index).choices());
    }
    assertThat(
            jdbc.sql("select title, description from reward_presets order by id offset ?")
                .param(existingPresets)
                .query((row, number) -> List.of(row.getString(1), row.getString(2)))
                .list())
        .containsExactlyElementsOf(REWARDS);
  }

  private static List<Map<String, Object>> history(JdbcClient jdbc) {
    return jdbc.sql(
            "select version, script, checksum, success from flyway_schema_history where type = 'SQL' order by installed_rank")
        .query()
        .listOfRows();
  }

  private static Map<String, List<String>> domainRows(JdbcClient jdbc) {
    Map<String, List<String>> rows = new LinkedHashMap<>();
    for (String table :
        List.of(
            "users",
            "question_presets",
            "question_preset_choices",
            "reward_presets",
            "rooms",
            "games",
            "choices",
            "publication_idempotency")) {
      rows.put(
          table,
          jdbc.sql("select to_jsonb(t)::text from " + table + " t order by to_jsonb(t)::text")
              .query(String.class)
              .list());
    }
    return rows;
  }

  private static void insertLegacyRows(JdbcClient jdbc) {
    long user =
        jdbc.sql(
                "insert into users(uuid, display_name, status, created_at, updated_at) values (?, 'legacy-host', 'ACTIVE', now(), now()) returning id")
            .param(UUID.randomUUID())
            .query(Long.class)
            .single();
    long reward =
        jdbc.sql(
                "insert into reward_presets(title, description) values ('기존 보상', '기존 설명') returning id")
            .query(Long.class)
            .single();
    long question =
        jdbc.sql(
                "insert into question_presets(question_text) values ('기존 질문 {name}이(가)') returning id")
            .query(Long.class)
            .single();
    jdbc.sql(
            "insert into question_preset_choices(question_preset_id, choice_text) select ?, '기존 보기-' || n from generate_series(1, 4) n")
        .param(question)
        .update();
    long room =
        jdbc.sql(
                "insert into rooms(host_user_id, selected_reward_preset_id, status, result_state, opened_at, closes_at, created_at) values (?, ?, 'CLOSED', 'FINAL', now() - interval '2 days', now() - interval '1 day', now()) returning id")
            .params(user, reward)
            .query(Long.class)
            .single();
    long game =
        jdbc.sql(
                "insert into games(room_id, question_preset_id, question_text_snapshot, created_at) values (?, ?, '기존 질문 {name}이(가)', now()) returning id")
            .params(room, question)
            .query(Long.class)
            .single();
    var choices =
        jdbc.sql(
                "insert into choices(game_id, position, text_snapshot) select ?, n - 1, '기존 보기-' || n from generate_series(1, 4) n returning id")
            .param(game)
            .query(Long.class)
            .list();
    jdbc.sql("update games set correct_choice_id = ? where id = ?")
        .params(choices.getFirst(), game)
        .update();
    jdbc.sql(
            "insert into publication_idempotency(user_id, request_key, normalized_input, game_id, record_expires_at) values (?, 'legacy-publication', '{}'::jsonb, ?, now() + interval '1 day')")
        .params(user, game)
        .update();
  }

  private record SeedQuestion(String text, List<String> choices) {}

  private record QuestionRow(long id, String text) {}
}

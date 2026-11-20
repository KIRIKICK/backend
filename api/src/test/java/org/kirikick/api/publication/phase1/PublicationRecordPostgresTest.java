package org.kirikick.api.publication.phase1;

import static java.time.ZoneOffset.UTC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.config.PublicationProperties;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.domain.PublicationInput.Kind;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.kirikick.api.publication.repository.PublicationIdempotencyRepository;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PublicationRecordPostgresTest {

  private static final PublicationPhase1Postgres POSTGRES = new PublicationPhase1Postgres();

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.close();
  }

  @Test
  @DisplayName("P1-05: UTF-8 요청 키를 변경 없이 사용자의 PK 값으로 보존한다")
  void storesUtf8KeysAsLiteralPrimaryKeyValues() throws SQLException {
    inSchema((context, jdbc) -> {
      long userId = user(jdbc);
      String key = "한글🔑";

      jdbc.sql(
              """
              insert into publication_idempotency (user_id, request_key, normalized_input)
              values (:userId, :key, cast(:input as jsonb))
              """)
          .param("userId", userId)
          .param("key", key)
          .param("input", inputJson())
          .update();

      assertThat(
              jdbc.sql(
                      """
                      select request_key from publication_idempotency
                      where user_id = :userId and request_key = :key
                      """)
                  .param("userId", userId)
                  .param("key", key)
                  .query(String.class)
                  .single())
          .isEqualTo(key);
      assertThat(
              jdbc.sql(
                      "select count(*) from publication_idempotency where user_id = :userId and request_key = :key")
                  .param("userId", userId)
                  .param("key", key)
                  .query(Long.class)
                  .single())
          .isEqualTo(1L);
      System.out.printf("P1-05 UTF8 key=%s literal-pk-match=true%n", key);
    });
  }

  @Test
  @DisplayName("P1-05: 요청 키의 ASCII·멀티바이트 255/256바이트 경계를 검증한다")
  void measuresRequestKeyLimitInUtf8Bytes() throws SQLException {
    inSchema((context, jdbc) -> {
      var types =
          jdbc.sql(
                  """
                  select column_name || ':' || data_type || ':' || is_nullable
                  from information_schema.columns
                  where table_schema = current_schema() and table_name = 'publication_idempotency'
                  """)
              .query(String.class)
              .list();
      assertThat(types)
          .contains(
              "request_key:text:NO",
              "normalized_input:jsonb:NO",
              "game_id:bigint:YES",
              "record_expires_at:timestamp with time zone:YES");
      var constraints =
          jdbc.sql(
                  """
                  select conname, pg_get_constraintdef(oid) as def from pg_constraint
                  where conrelid = 'publication_idempotency'::regclass order by conname
                  """)
              .query()
              .listOfRows();
      assertThat(constraints)
          .anySatisfy(row ->
              assertThat(row)
                  .containsEntry("conname", "pk_publication_idempotency")
                  .containsEntry("def", "PRIMARY KEY (user_id, request_key)"));
      assertThat(
              jdbc.sql(
                      """
                      select count(*) from pg_trigger where tgrelid = 'publication_idempotency'::regclass
                        and not tgisinternal and tgdeferrable
                      """)
                  .query(Long.class)
                  .single())
          .isZero();
      System.out.printf("P1-05 catalog %s %s%n", types, constraints);
      long userId = user(jdbc);
      String accepted = "가".repeat(85); // 255 UTF-8 bytes, not 255 Java characters.
      jdbc.sql(
              """
              insert into publication_idempotency (user_id, request_key, normalized_input)
              values (:userId, :key, cast(:input as jsonb))
              """)
          .param("userId", userId)
          .param("key", accepted)
          .param("input", inputJson())
          .update();
      assertThat(
              jdbc.sql(
                      "select octet_length(request_key) from publication_idempotency where user_id = :userId")
                  .param("userId", userId)
                  .query(Integer.class)
                  .single())
          .isEqualTo(255);

      assertConstraint(
          () ->
              jdbc.sql(
                      """
                      insert into publication_idempotency (user_id, request_key, normalized_input)
                      values (:userId, :key, cast(:input as jsonb))
                      """)
                  .param("userId", userId)
                  .param("key", accepted + "x")
                  .param("input", inputJson())
                  .update(),
          "23514",
          "ck_publication_idempotency_key_length");
      String ascii = "a".repeat(255);
      for (String key : List.of(ascii, " literal ", "literal")) {
        put(jdbc, userId, key, inputJson());
        assertThat(
                jdbc.sql(
                        """
                        select request_key from publication_idempotency where user_id = ? and request_key = ?
                        """)
                    .params(userId, key)
                    .query(String.class)
                    .single())
            .isEqualTo(key);
      }
      assertConstraint(
          () -> put(jdbc, userId, ascii + "a", inputJson()),
          "23514",
          "ck_publication_idempotency_key_length");
      assertConstraint(
          () -> put(jdbc, userId, "", inputJson()),
          "23514",
          "ck_publication_idempotency_key_length");
      assertConstraint(() -> put(jdbc, userId, null, inputJson()), "23502", "request_key");
      assertConstraint(
          () -> put(jdbc, userId, ascii, inputJson()), "23505", "pk_publication_idempotency");
      put(jdbc, user(jdbc), ascii, inputJson());
      System.out.println(
          "P1-05 ASCII/multibyte 255/256; shared PK guarded, other user independent, nonUUID literal");
    });
  }

  @Test
  @DisplayName("P1-05: SQL NULL·JSON null 및 비객체를 각각 지정한 제약으로 거절한다")
  void rejectsNullAndNonObjectJsonWithDeclaredSqlStateAndConstraint() throws SQLException {
    inSchema((context, jdbc) -> {
      long userId = user(jdbc);
      assertConstraint(
          () ->
              jdbc.sql(
                      """
                      insert into publication_idempotency (user_id, request_key, normalized_input)
                      values (:userId, 'null-column', null)
                      """)
                  .param("userId", userId)
                  .update(),
          "23502",
          "normalized_input");
      for (String json : List.of("null", "[]", "1", "\"{}\"", "true")) {
        assertConstraint(
            () ->
                jdbc.sql(
                        """
                        insert into publication_idempotency (user_id, request_key, normalized_input)
                        values (:userId, :key, cast(:json as jsonb))
                        """)
                    .param("userId", userId)
                    .param("key", "bad-" + json.hashCode())
                    .param("json", json)
                    .update(),
            "23514",
            "ck_publication_idempotency_input_object");
      }
    });
  }

  @Test
  @DisplayName("P1-05: 두 요청 종류를 타입으로 복원하고 선택지 집합을 값으로 비교한다")
  void repositoryRoundTripsTypedObjectsAndDoesNotCompareJsonStringsOrChoiceOrder()
      throws SQLException {
    inSchema((context, jdbc) -> {
      long user = user(jdbc);
      var create = input();
      var add =
          new PublicationInput(Kind.NK_KICK_ADD, 7L, null, 2L, List.of(14L, 11L, 13L, 12L), 11L);
      inTransaction(
          context,
          ignored -> {
            assertThat(repository(context).tryClaim(user, "create", create)).isPresent();
            assertThat(repository(context).tryClaim(user, "create", add)).isEmpty();
            assertThat(repository(context).tryClaim(user, "add", add)).isPresent();
            assertThat(
                    repository(context)
                        .findForUpdate(user, "create")
                        .orElseThrow()
                        .normalizedInput())
                .isEqualTo(create);
            assertThat(
                    repository(context).findForUpdate(user, "add").orElseThrow().normalizedInput())
                .isEqualTo(add);
          });
      var fields =
          jdbc.sql(
                  """
                  select request_key || ':' || jsonb_typeof(normalized_input)
                  from publication_idempotency where user_id = ? order by request_key
                  """)
              .param(user)
              .query(String.class)
              .list();
      assertThat(fields).containsExactly("add:object", "create:object");
      put(
          jdbc,
          user,
          "reordered",
          """
          { "correctChoiceId":11, "choiceIds":[14,11,13,12], "questionPresetId":2,
            "rewardPresetId":1, "roomId":null, "kind":"NK_CREATE" }
          """);
      inTransaction(
          context,
          ignored -> {
            var reloaded =
                repository(context)
                    .findForUpdate(user, "reordered")
                    .orElseThrow()
                    .normalizedInput();
            assertThat(reloaded).isEqualTo(create).isNotEqualTo(add);
            assertThat(reloaded)
                .isNotEqualTo(
                    new PublicationInput(
                        Kind.NK_CREATE, null, 2L, 2L, List.of(11L, 12L, 13L, 14L), 11L));
          });
      System.out.printf(
          "P1-05 typed JSON %s; reordered value same, other reward/kind differs%n", fields);
    });
  }

  @Test
  @DisplayName("P1-05: 없는 User나 Game을 참조하면 지정한 FK로 거절한다")
  void rejectsUnknownUserAndUnknownGameWithDeclaredForeignKeyConstraints() throws SQLException {
    inSchema((context, jdbc) -> {
      assertConstraint(
          () ->
              jdbc.sql(
                      """
                      insert into publication_idempotency (user_id, request_key, normalized_input)
                      values (999999, 'missing-user', cast(:input as jsonb))
                      """)
                  .param("input", inputJson())
                  .update(),
          "23503",
          "fk_publication_idempotency_user");

      long userId = user(jdbc);
      assertConstraint(
          () ->
              jdbc.sql(
                      """
                      insert into publication_idempotency (user_id, request_key, normalized_input, game_id, record_expires_at)
                      values (:userId, 'missing-game', cast(:input as jsonb), 999999, now())
                      """)
                  .param("userId", userId)
                  .param("input", inputJson())
                  .update(),
          "23503",
          "fk_publication_idempotency_game");
    });
  }

  @Test
  @DisplayName("P1-05: 필수 입력이 없는 객체는 복원 실패로 격리하고 원 행을 보존한다")
  void rejectsSemanticallyMalformedJsonObjectWhenTheApplicationReloadsIt() throws SQLException {
    inSchema((context, jdbc) -> {
      long userId = user(jdbc);
      long game = game(jdbc, userId);
      jdbc.sql(
              """
              insert into publication_idempotency (user_id, request_key, normalized_input, game_id, record_expires_at)
              values (:userId, 'missing-fields', '{}'::jsonb, :game, now() + interval '1 hour')
              """)
          .param("userId", userId)
          .param("game", game)
          .update();

      assertThatThrownBy(() ->
              inTransaction(
                  context, ignored -> repository(context).findForUpdate(userId, "missing-fields")))
          .isInstanceOfSatisfying(
              DomainException.class,
              e ->
                  assertThat(e.getErrorCode())
                      .isEqualTo(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD));
      assertThat(
              jdbc.sql(
                      "select normalized_input::text from publication_idempotency where user_id = :userId and request_key = 'missing-fields'")
                  .param("userId", userId)
                  .query(String.class)
                  .single())
          .isEqualTo("{}");
      System.out.println("P1-05 malformed object remained committed and was signalled unreadable");
    });
  }

  @Test
  @DisplayName("P1-05: 의미 입력 필드의 잘못된 타입을 복원에서 거절한다")
  void rejectsWrongJsonTypeWhenTheApplicationReloadsIt() throws SQLException {
    inSchema((context, jdbc) -> {
      long userId = user(jdbc);
      jdbc.sql(
              """
              insert into publication_idempotency (user_id, request_key, normalized_input)
              values (:userId, 'wrong-kind-type', '{"kind":1}'::jsonb)
              """)
          .param("userId", userId)
          .update();

      assertThatThrownBy(() ->
              inTransaction(
                  context, ignored -> repository(context).findForUpdate(userId, "wrong-kind-type")))
          .isInstanceOfSatisfying(
              DomainException.class,
              e ->
                  assertThat(e.getErrorCode())
                      .isEqualTo(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD));
    });
  }

  @Test
  @DisplayName("P1-05: 서로 다른 선택지 ID 3개는 선점 전에 거절한다")
  void rejectsThreeChoicesBeforeAClaimCanBeStored() {
    assertThatThrownBy(
            () -> new PublicationInput(Kind.NK_CREATE, null, 1L, 2L, List.of(1L, 2L, 3L), 1L))
        .isInstanceOfSatisfying(
            DomainException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(PublicationErrorCode.INVALID_PUBLICATION_CHOICES));
  }

  @Test
  @DisplayName("P1-05: 서로 다른 선택지 ID 5개는 선점 전에 거절한다")
  void rejectsFiveChoicesBeforeAClaimCanBeStored() {
    assertThatThrownBy(() ->
            new PublicationInput(Kind.NK_CREATE, null, 1L, 2L, List.of(1L, 2L, 3L, 4L, 5L), 1L))
        .isInstanceOfSatisfying(
            DomainException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(PublicationErrorCode.INVALID_PUBLICATION_CHOICES));
  }

  @Test
  @DisplayName("P1-05: 4개 입력 중 중복된 선택지 ID는 선점 전에 거절한다")
  void rejectsDuplicateChoicesBeforeAClaimCanBeStored() {
    assertThatThrownBy(
            () -> new PublicationInput(Kind.NK_CREATE, null, 1L, 2L, List.of(1L, 2L, 3L, 3L), 1L))
        .isInstanceOfSatisfying(
            DomainException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(PublicationErrorCode.INVALID_PUBLICATION_CHOICES));
  }

  @Test
  @DisplayName("P1-05: 커밋된 불완전 기록을 완성 기록으로 읽거나 변경하지 않는다")
  void leavesIncompleteClaimUntouchedAndReportsItAsIncomplete() throws SQLException {
    inSchema((context, jdbc) -> {
      long userId = user(jdbc);
      inTransaction(
          context,
          ignored ->
              assertThat(repository(context).tryClaim(userId, "incomplete", input())).isPresent());

      inTransaction(
          context,
          ignored ->
              assertThat(
                      repository(context)
                          .findForUpdate(userId, "incomplete")
                          .orElseThrow()
                          .isComplete())
                  .isFalse());
      inTransaction(
          context,
          ignored ->
              assertThat(repository(context).tryClaim(userId, "incomplete", input())).isEmpty());
      long gameId = game(jdbc, userId);
      assertConstraint(
          () ->
              jdbc.sql(
                      """
                      update publication_idempotency set game_id = ? where user_id = ? and request_key = 'incomplete'
                      """)
                  .params(gameId, userId)
                  .update(),
          "23514",
          "ck_publication_idempotency_completion");
      assertConstraint(
          () ->
              jdbc.sql(
                      """
                      update publication_idempotency set record_expires_at = now() where user_id = ? and request_key = 'incomplete'
                      """)
                  .param(userId)
                  .update(),
          "23514",
          "ck_publication_idempotency_completion");
      assertThat(
              jdbc.sql(
                      "select game_id is null and record_expires_at is null from publication_idempotency where user_id = :userId and request_key = 'incomplete'")
                  .param("userId", userId)
                  .query(Boolean.class)
                  .single())
          .isTrue();
      System.out.println("P1-05 incomplete committed claim reloaded without a write");
    });
  }

  @Test
  @DisplayName("P1-05/G2: 정확히 선점한 행을 유한한 고정 만료값으로 완성한다")
  void completesOnlyItsExactClaimAndPersistsFiniteConfiguredExpiry() throws SQLException {
    inSchema((context, jdbc) -> {
      long userId = user(jdbc);
      long gameId = game(jdbc, userId);
      Instant openedAt = Instant.parse("2026-11-20T00:00:00.123456Z");
      Instant expiry = new PublicationProperties(Duration.ofMinutes(5)).recordExpiresAt(openedAt);

      inTransaction(
          context,
          ignored -> {
            PublicationIdempotencyRepository.Claim claim =
                repository(context).tryClaim(userId, "complete", input()).orElseThrow();
            repository(context).completeClaim(claim, gameId, expiry);
          });

      assertThat(
              jdbc.sql(
                      """
                      select isfinite(record_expires_at) and record_expires_at = :expiry
                      from publication_idempotency where user_id = :userId and request_key = 'complete'
                      """)
                  .param("expiry", expiry.atOffset(UTC))
                  .param("userId", userId)
                  .query(Boolean.class)
                  .single())
          .isTrue();
      System.out.printf("G2 expiry=%s finite=true%n", expiry);
    });
  }

  @Test
  @DisplayName("G2: 새 Grace를 신규 기록에만 반영하고 기존 만료값을 보존한다")
  void preservesAnOldExpiryWhenASeparateConfigurationIsCalculatedLater() throws SQLException {
    inSchema((context, jdbc) -> {
      long userId = user(jdbc);
      long gameId = game(jdbc, userId);
      Instant openedAt = Instant.parse("2026-11-20T00:00:00Z");
      Instant oldExpiry = new PublicationProperties(Duration.ZERO).recordExpiresAt(openedAt);

      inTransaction(
          context,
          ignored -> {
            PublicationIdempotencyRepository.Claim claim =
                repository(context).tryClaim(userId, "old-config", input()).orElseThrow();
            repository(context).completeClaim(claim, gameId, oldExpiry);
          });
      String oldExpirySql =
          "select record_expires_at from publication_idempotency where user_id = ? and request_key = 'old-config'";
      Instant beforeChange =
          jdbc.sql(oldExpirySql).param(userId).query(OffsetDateTime.class).single().toInstant();
      assertThat(beforeChange).isEqualTo(oldExpiry);

      Instant newExpiry =
          new PublicationProperties(Duration.ofMinutes(5)).recordExpiresAt(openedAt);
      long other = user(jdbc);
      long newerGame = game(jdbc, other);
      inTransaction(
          context,
          ignored ->
              repository(context)
                  .completeClaim(
                      repository(context).tryClaim(other, "new-config", input()).orElseThrow(),
                      newerGame,
                      newExpiry));
      assertThat(
              jdbc.sql(oldExpirySql).param(userId).query(OffsetDateTime.class).single().toInstant())
          .isEqualTo(beforeChange);
      assertThat(
              jdbc.sql(
                      """
                      select record_expires_at from publication_idempotency where user_id = ? and request_key = 'new-config'
                      """)
                  .param(other)
                  .query(OffsetDateTime.class)
                  .single()
                  .toInstant())
          .isEqualTo(newExpiry);
      assertThat(
              jdbc.sql(
                      """
                      select (? >= record_expires_at) from publication_idempotency
                      where user_id = ? and request_key = 'old-config'
                      """)
                  .params(oldExpiry.atOffset(UTC), userId)
                  .query(Boolean.class)
                  .single())
          .isTrue();
      assertThat(
              jdbc.sql(
                      """
                      select (? >= record_expires_at) from publication_idempotency
                      where user_id = ? and request_key = 'old-config'
                      """)
                  .params(oldExpiry.minusNanos(1_000).atOffset(UTC), userId)
                  .query(Boolean.class)
                  .single())
          .isFalse();
      assertThat(oldExpiry).isBefore(newExpiry);
      System.out.printf(
          "G2 old-expiry=%s new-expiry=%s comparator=unchanged%n", oldExpiry, newExpiry);
    });
  }

  @Test
  @DisplayName("P1-05: 현재 존재하는 두 성공 기록이 한 Game을 공유하지 못한다")
  void rejectsReusingTheSameGameForAnotherCompletedRecord() throws SQLException {
    inSchema((context, jdbc) -> {
      long userId = user(jdbc);
      long gameId = game(jdbc, userId);
      jdbc.sql(
              """
              insert into publication_idempotency (user_id, request_key, normalized_input, game_id, record_expires_at)
              values (:userId, 'first-game', cast(:input as jsonb), :gameId, now())
              """)
          .param("userId", userId)
          .param("input", inputJson())
          .param("gameId", gameId)
          .update();

      assertConstraint(
          () ->
              jdbc.sql(
                      """
                      insert into publication_idempotency (user_id, request_key, normalized_input, game_id, record_expires_at)
                      values (:userId, 'duplicate-game', cast(:input as jsonb), :gameId, now())
                      """)
                  .param("userId", userId)
                  .param("input", inputJson())
                  .param("gameId", gameId)
                  .update(),
          "23505",
          "uk_publication_idempotency_game");
    });
  }

  @Test
  @DisplayName("P1-05: 입력 또는 선점 트랜잭션 불일치의 완료를 거절한다")
  void rejectsCompletionAfterSyntheticInputChangeInsteadOfUpdatingAnotherState()
      throws SQLException {
    inSchema((context, jdbc) -> {
      long userId = user(jdbc);
      long gameId = game(jdbc, userId);
      assertThatThrownBy(() ->
              inTransaction(
                  context,
                  ignored -> {
                    PublicationIdempotencyRepository.Claim claim =
                        repository(context).tryClaim(userId, "cas", input()).orElseThrow();
                    context
                        .getBean(JdbcClient.class)
                        .sql(
                            "update publication_idempotency set normalized_input = '{}'::jsonb where user_id = :userId and request_key = 'cas'")
                        .param("userId", userId)
                        .update();
                    repository(context)
                        .completeClaim(claim, gameId, Instant.parse("2026-11-21T00:30:00Z"));
                  }))
          .isInstanceOfSatisfying(
              DomainException.class,
              e ->
                  assertThat(e.getErrorCode())
                      .isEqualTo(PublicationErrorCode.PUBLICATION_CLAIM_COMPLETION_FAILED));
      assertThat(
              jdbc.sql(
                      "select count(*) from publication_idempotency where user_id = :userId and request_key = 'cas'")
                  .param("userId", userId)
                  .query(Long.class)
                  .single())
          .isZero();
      var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
      var stale =
          transaction.execute(
              status -> repository(context).tryClaim(userId, "other-tx", input()).orElseThrow());
      assertThatThrownBy(() ->
              inTransaction(
                  context,
                  ignored ->
                      repository(context)
                          .completeClaim(stale, gameId, Instant.parse("2026-11-21T00:30:00Z"))))
          .isInstanceOfSatisfying(
              DomainException.class,
              e ->
                  assertThat(e.getErrorCode())
                      .isEqualTo(PublicationErrorCode.PUBLICATION_CLAIM_COMPLETION_FAILED));
      assertThat(
              jdbc.sql(
                      """
                      select game_id is null and record_expires_at is null from publication_idempotency
                      where user_id = ? and request_key = 'other-tx'
                      """)
                  .param(userId)
                  .query(Boolean.class)
                  .single())
          .isTrue();
      System.out.println(
          "P1-05 CAS mismatched input/foreign xid -> zero rows; aborted claim absent, another-xid claim unchanged");
    });
  }

  @ParameterizedTest
  @ValueSource(strings = {"duration-overflow", "instant-overflow", "pg-range", "missing-expiry"})
  @DisplayName("G2: 만료 계산·저장 실패 시 합성 업무와 선점을 모두 롤백한다")
  void rollsBackSyntheticWritesWhenExpiryCannotBeCompleted(String invalid) throws SQLException {
    inSchema((context, jdbc) -> {
      long user = user(jdbc);
      long[] ids = new long[2];
      Throwable failure =
          catchThrowable(() ->
              inTransaction(
                  context,
                  ignored -> {
                    var claim = repository(context).tryClaim(user, invalid, input()).orElseThrow();
                    ids[1] = game(jdbc, user);
                    ids[0] =
                        jdbc.sql("select room_id from games where id = ?")
                            .param(ids[1])
                            .query(Long.class)
                            .single();
                    assertThat(
                            jdbc.sql(
                                    """
                                    insert into choices (game_id, position, text_snapshot)
                                    select ?, p, 'G2 rollback fixture' from generate_series(0,3) p
                                    """)
                                .param(ids[1])
                                .update())
                        .isEqualTo(4);
                    Instant opened = Instant.parse("2030-01-02T00:15:30.123456Z");
                    Instant expiry =
                        switch (invalid) {
                          case "duration-overflow" ->
                              new PublicationProperties(Duration.ofSeconds(Long.MAX_VALUE))
                                  .recordExpiresAt(opened);
                          case "instant-overflow" ->
                              new PublicationProperties(Duration.ofDays(1))
                                  .recordExpiresAt(Instant.MAX);
                          case "pg-range" ->
                              new PublicationProperties(Duration.ofDays(365L * 300_000))
                                  .recordExpiresAt(opened);
                          default -> null;
                        };
                    repository(context).completeClaim(claim, ids[1], expiry);
                  }));
      assertThat(ids[0]).isPositive();
      assertThat(ids[1]).isPositive();
      switch (invalid) {
        case "duration-overflow" -> assertThat(failure).isInstanceOf(ArithmeticException.class);
        case "instant-overflow" -> assertThat(failure).isInstanceOf(DateTimeException.class);
        case "pg-range" -> {
          if (failure instanceof DataAccessException) {
            assertThat(cause(failure).getSQLState()).isEqualTo("22008");
          } else {
            assertThat(failure)
                .isInstanceOfSatisfying(
                    DomainException.class,
                    e ->
                        assertThat(e.getErrorCode())
                            .isEqualTo(PublicationErrorCode.PUBLICATION_CLAIM_COMPLETION_FAILED));
          }
        }
        default -> assertThat(failure).isInstanceOf(RuntimeException.class);
      }
      assertThat(
              jdbc.sql(
                      """
                      select (select count(*) from rooms where id=?) +
                             (select count(*) from games where id=?) +
                             (select count(*) from choices where game_id=?) +
                             (select count(*) from publication_idempotency where user_id=? and request_key=?)
                      """)
                  .params(ids[0], ids[1], ids[1], user, invalid)
                  .query(Long.class)
                  .single())
          .isZero();
      System.out.printf(
          "G2/P1-05 %s rejected %s; rollback room=%d game/choices=%d claim=%s%n",
          invalid, failure.getClass().getSimpleName(), ids[0], ids[1], invalid);
    });
  }

  private static void inSchema(BiConsumer<ApplicationContext, JdbcClient> test)
      throws SQLException {
    POSTGRES.inMigratedSchema(
        "P1-05", (context, pool) -> test.accept(context, JdbcClient.create(pool)));
  }

  private static void inTransaction(ApplicationContext context, Consumer<Void> action) {
    new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
        .executeWithoutResult(status -> action.accept(null));
  }

  private static PublicationIdempotencyRepository repository(ApplicationContext context) {
    return context.getBean(PublicationIdempotencyRepository.class);
  }

  private static long user(JdbcClient jdbc) {
    return jdbc.sql(
            """
            insert into users (uuid, display_name, status, created_at, updated_at)
            values (:uuid, 'tester', 'ACTIVE', now(), now()) returning id
            """)
        .param("uuid", UUID.randomUUID())
        .query(Long.class)
        .single();
  }

  private static long game(JdbcClient jdbc, long userId) {
    long rewardId =
        jdbc.sql(
                "insert into reward_presets (title, description) values ('reward', 'description') returning id")
            .query(Long.class)
            .single();
    long questionId =
        jdbc.sql("insert into question_presets (question_text) values ('question') returning id")
            .query(Long.class)
            .single();
    long roomId =
        jdbc.sql(
                """
                insert into rooms (host_user_id, selected_reward_preset_id, status, created_at)
                values (:userId, :rewardId, 'CLOSED', now()) returning id
                """)
            .param("userId", userId)
            .param("rewardId", rewardId)
            .query(Long.class)
            .single();
    return jdbc.sql(
            """
            insert into games (room_id, question_preset_id, question_text_snapshot, created_at)
            values (:roomId, :questionId, 'question', now()) returning id
            """)
        .param("roomId", roomId)
        .param("questionId", questionId)
        .query(Long.class)
        .single();
  }

  private static PublicationInput input() {
    return new PublicationInput(Kind.NK_CREATE, null, 1L, 2L, List.of(11L, 12L, 13L, 14L), 11L);
  }

  private static String inputJson() {
    return "{\"kind\":\"NK_CREATE\",\"roomId\":null,\"rewardPresetId\":1,\"questionPresetId\":2,\"choiceIds\":[11,12,13,14],\"correctChoiceId\":11}";
  }

  private static void put(JdbcClient jdbc, long user, String key, String json) {
    assertThat(
            jdbc.sql(
                    """
                    insert into publication_idempotency (user_id, request_key, normalized_input)
                    values (?, ?, cast(? as jsonb))
                    """)
                .params(user, key, json)
                .update())
        .isOne();
  }

  private static void assertConstraint(Runnable operation, String state, String constraint) {
    Throwable thrown = catchThrowable(operation::run);
    assertThat(thrown).isInstanceOf(DataAccessException.class);
    SQLException sql = cause(thrown);
    assertThat(sql.getSQLState()).isEqualTo(state);
    assertThat(sql.getMessage()).contains("\"" + constraint + "\"");
    System.out.printf("P1-05 SQLSTATE=%s constraint/column=%s%n", state, constraint);
  }

  private static SQLException cause(Throwable throwable) {
    Throwable current = throwable;
    while (current != null && !(current instanceof SQLException)) {
      current = current.getCause();
    }
    assertThat(current).isInstanceOf(SQLException.class);
    return (SQLException) current;
  }
}

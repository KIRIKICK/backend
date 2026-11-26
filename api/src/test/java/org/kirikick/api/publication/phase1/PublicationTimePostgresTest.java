package org.kirikick.api.publication.phase1;

import static java.time.ZoneOffset.UTC;
import static java.time.temporal.ChronoUnit.MICROS;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.config.PublicationProperties;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.domain.PublicationKind;
import org.kirikick.api.publication.repository.PublicationIdempotencyRepository;
import org.kirikick.api.room.repository.RoomRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PublicationTimePostgresTest {

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
  @DisplayName("C1: JDBC 정밀도와 Room·식별 기록의 마이크로초 만료 경계를 검증한다")
  void c1MeasuresPrecisionAndComparesReadBackInstantsAtBothBoundaries() throws SQLException {
    POSTGRES.inMigratedSchema(
        "C1",
        (context, pool) -> {
          var jdbc = context.getBean(JdbcClient.class);
          var properties = context.getBean(PublicationProperties.class);
          assertThat(ZoneId.systemDefault()).isEqualTo(ZoneId.of("Asia/Seoul"));
          assertThat(jdbc.sql("show timezone").query(String.class).single())
              .isEqualTo("Pacific/Honolulu");
          var types =
              jdbc.sql(
                      """
                      select table_name || '.' || column_name || ':' || udt_name || '(' || datetime_precision || ')'
                      from information_schema.columns
                      where table_schema = current_schema() and
                        ((table_name = 'rooms' and column_name in ('created_at','opened_at','closes_at'))
                        or (table_name='games' and column_name='created_at')
                        or (table_name='publication_idempotency' and column_name='record_expires_at'))
                      """)
                  .query(String.class)
                  .list();
          assertThat(types)
              .containsExactlyInAnyOrder(
                  "rooms.created_at:timestamptz(6)",
                  "rooms.opened_at:timestamptz(6)",
                  "rooms.closes_at:timestamptz(6)",
                  "games.created_at:timestamptz(6)",
                  "publication_idempotency.record_expires_at:timestamptz(6)");
          Instant base = Instant.parse("2030-01-02T00:15:30.999999Z");
          for (int nanos : List.of(1, 499, 500, 999)) {
            Instant submicro = base.plusNanos(nanos);
            Instant jdbcRounded = castInstant(jdbc, submicro.atOffset(UTC));
            Instant textRounded = castInstant(jdbc, submicro.toString());
            assertThat(jdbcRounded).isIn(base, base.plus(1, MICROS));
            assertThat(textRounded).isIn(base, base.plus(1, MICROS));
            assertThat(castInstant(jdbc, submicro.truncatedTo(MICROS).atOffset(UTC)))
                .isEqualTo(base);
            System.out.printf(
                "C1 precision raw=%s JDBC=%s PG-text=%s truncated=%s%n",
                submicro, jdbcRounded, textRounded, base);
          }
          long user =
              jdbc.sql(
                      """
                      insert into users (uuid, display_name, status, created_at, updated_at)
                      values (gen_random_uuid(), 'C1 user', 'ACTIVE', now(), now()) returning id
                      """)
                  .query(Long.class)
                  .single();
          long reward =
              jdbc.sql(
                      "insert into reward_presets (title, description) values ('C1','C1') returning id")
                  .query(Long.class)
                  .single();
          long question =
              jdbc.sql("insert into question_presets (question_text) values ('C1') returning id")
                  .query(Long.class)
                  .single();
          Instant opened = base.plusNanos(999).truncatedTo(MICROS);
          Instant closed = opened.plus(Duration.ofHours(24));
          Instant expires = properties.recordExpiresAt(opened);
          long room =
              jdbc.sql(
                      """
                      insert into rooms (host_user_id, selected_reward_preset_id, status, opened_at, closes_at, created_at)
                      values (?, ?, 'OPEN', ?, ?, ?) returning id
                      """)
                  .params(
                      user,
                      reward,
                      opened.atOffset(UTC),
                      closed.atOffset(UTC),
                      opened.atOffset(UTC))
                  .query(Long.class)
                  .single();
          long game =
              jdbc.sql(
                      """
                      insert into games (room_id, question_preset_id, question_text_snapshot, created_at)
                      values (?, ?, 'C1', ?) returning id
                      """)
                  .params(room, question, opened.atOffset(UTC))
                  .query(Long.class)
                  .single();
          var records = context.getBean(PublicationIdempotencyRepository.class);
          var input =
              new PublicationInput(
                  PublicationKind.NK_CREATE, null, reward, question, List.of(1L, 2L, 3L, 4L), 1L);
          var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
          tx.executeWithoutResult(status ->
              records.completeClaim(
                  records.tryClaim(user, "c1", input).orElseThrow(), game, expires));
          var persisted = tx.execute(status -> records.findForUpdate(user, "c1").orElseThrow());
          assertThat(persisted.recordExpiresAt()).isEqualTo(expires);
          var rooms = context.getBean(RoomRepository.class);
          var state = rooms.findCurrentState(room).orElseThrow();
          assertThat(state.getOpenedAt()).isEqualTo(opened);
          assertThat(state.getClosesAt()).isEqualTo(closed);
          for (int micros : List.of(-1, 0, 1)) {
            Instant roomProbe = closed.plus(micros, MICROS);
            Instant recordProbe = expires.plus(micros, MICROS);
            boolean expected = micros >= 0;
            assertThat(!roomProbe.isBefore(state.getClosesAt())).isEqualTo(expected);
            assertThat(!recordProbe.isBefore(persisted.recordExpiresAt())).isEqualTo(expected);
            assertThat(rooms.findUnsettledCandidateIds(roomProbe).contains(room))
                .isEqualTo(expected);
            var boundaries =
                jdbc.sql(
                        """
                        select ? >= r.closes_at as room_expired, ? >= i.record_expires_at as record_expired
                        from rooms r join games g on g.room_id = r.id
                        join publication_idempotency i on i.game_id = g.id
                        where r.id = ? and i.user_id = ? and i.request_key = 'c1'
                        """)
                    .params(roomProbe.atOffset(UTC), recordProbe.atOffset(UTC), room, user)
                    .query()
                    .singleRow();
            assertThat(boundaries)
                .containsEntry("room_expired", expected)
                .containsEntry("record_expired", expected);
            System.out.printf(
                "C1 tz=%s/%s room=%d game=%d opened=%s closed=%s expiry=%s delta=%dµs %s%n",
                ZoneId.systemDefault(),
                "Pacific/Honolulu",
                room,
                game,
                opened,
                closed,
                expires,
                micros,
                boundaries);
          }
        });
  }

  private static Instant castInstant(JdbcClient jdbc, Object param) {
    return jdbc.sql("select cast(? as timestamptz(6))")
        .param(param)
        .query(OffsetDateTime.class)
        .single()
        .toInstant();
  }
}

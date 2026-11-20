package org.kirikick.api.publication.repository;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.ZoneOffset.UTC;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.common.infra.DbTimeSource;
import org.kirikick.api.publication.domain.PublicationIdempotencyRecord;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PublicationIdempotencyRepository {

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
          .build();

  private final JdbcClient jdbc;
  private final DbTimeSource clock;

  /** Reserve or recover the first publication inside the caller's READ COMMITTED transaction. */
  public Reservation reserveNkCreate(long userId, String requestKey, PublicationInput input) {
    if (input.kind() != PublicationInput.Kind.NK_CREATE) {
      throw new DomainException(PublicationErrorCode.INVALID_PUBLICATION_TARGET);
    }
    Optional<Claim> first = tryClaim(userId, requestKey, input);
    if (first.isPresent()) {
      return first.get();
    }

    // In READ COMMITTED, the conflict winner may not be visible in the INSERT snapshot.
    PublicationIdempotencyRecord locked =
        findForUpdate(userId, requestKey)
            .orElseThrow(
                () -> new DomainException(PublicationErrorCode.PUBLICATION_CLAIM_STATE_INVALID));
    Replay original = restore(locked);
    Instant cutoff = clock.readCurrentTime();
    if (!cutoff.isBefore(locked.recordExpiresAt())) {
      return replaceExpired(locked, input, cutoff);
    }
    if (!input.equals(locked.normalizedInput())) {
      throw new DomainException(PublicationErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }
    return original;
  }

  private Replay restore(PublicationIdempotencyRecord locked) {
    if (!locked.isComplete()) {
      throw new DomainException(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD);
    }
    return jdbc.sql(
            """
            select r.id as room_id, g.id as game_id, r.opened_at, r.closes_at
            from games g join rooms r on r.id = g.room_id
            where g.id = :gameId and r.host_user_id = :userId
              and isfinite(r.opened_at) and isfinite(r.closes_at)
              and (select count(*) from choices c where c.game_id = g.id) = 4
              and exists (select 1 from choices c where c.game_id = g.id and c.id = g.correct_choice_id)
            """)
        .param("gameId", locked.gameId())
        .param("userId", locked.userId())
        .query((row, rowNum) ->
            new Replay(
                row.getLong("room_id"),
                row.getLong("game_id"),
                row.getObject("opened_at", OffsetDateTime.class).toInstant(),
                row.getObject("closes_at", OffsetDateTime.class).toInstant()))
        .optional()
        .filter(replay ->
            Duration.between(replay.openedAt(), replay.closesAt()).equals(Duration.ofHours(24)))
        .orElseThrow(() -> new DomainException(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD));
  }

  private Claim replaceExpired(
      PublicationIdempotencyRecord locked, PublicationInput input, Instant cutoff) {
    return jdbc.sql(
            """
            update publication_idempotency
               set claim_id = gen_random_uuid(), normalized_input = cast(:input as jsonb),
                   game_id = null, record_expires_at = null
             where user_id = :userId and request_key = :requestKey
               and claim_id = :oldClaimId and game_id = :oldGameId
               and record_expires_at = :oldExpiry and record_expires_at <= :cutoff
            returning claim_id, pg_current_xact_id()::text as xid
            """)
        .param("input", writeInput(input))
        .param("userId", locked.userId())
        .param("requestKey", locked.requestKey())
        .param("oldClaimId", locked.claimId())
        .param("oldGameId", locked.gameId())
        .param("oldExpiry", locked.recordExpiresAt().atOffset(UTC))
        .param("cutoff", cutoff.atOffset(UTC))
        .query((row, rowNum) ->
            new Claim(
                locked.userId(),
                locked.requestKey(),
                input,
                row.getString("xid"),
                row.getObject("claim_id", UUID.class)))
        .optional()
        .orElseThrow(
            () -> new DomainException(PublicationErrorCode.PUBLICATION_CLAIM_STATE_INVALID));
  }

  public sealed interface Reservation permits Claim, Replay {}

  public record Replay(long roomId, long gameId, Instant openedAt, Instant closesAt)
      implements Reservation {}

  public Optional<Claim> tryClaim(long userId, String requestKey, PublicationInput input) {
    validateRequestKey(requestKey);
    return jdbc.sql(
            """
            insert into publication_idempotency (user_id, request_key, normalized_input)
            values (:userId, :requestKey, cast(:input as jsonb))
            on conflict (user_id, request_key) do nothing
            returning cast(pg_current_xact_id() as text) as xid, claim_id
            """)
        .param("userId", userId)
        .param("requestKey", requestKey)
        .param("input", writeInput(input))
        .query((row, rowNum) ->
            new Claim(
                userId,
                requestKey,
                input,
                row.getString("xid"),
                row.getObject("claim_id", UUID.class)))
        .optional();
  }

  public Optional<PublicationIdempotencyRecord> findForUpdate(long userId, String requestKey) {
    validateRequestKey(requestKey);
    return jdbc.sql(
            """
            select user_id, request_key, normalized_input, game_id, record_expires_at, claim_id,
                   isfinite(record_expires_at) as finite_expiry
            from publication_idempotency
            where user_id = :userId and request_key = :requestKey
            for update
            """)
        .param("userId", userId)
        .param("requestKey", requestKey)
        .query((row, rowNum) -> readRecord(row))
        .optional();
  }

  public void completeClaim(Claim claim, long gameId, Instant recordExpiresAt) {
    if (recordExpiresAt == null) {
      throw new DomainException(PublicationErrorCode.PUBLICATION_CLAIM_COMPLETION_FAILED);
    }
    List<Boolean> completed =
        jdbc.sql(
                """
                update publication_idempotency
                set game_id = :gameId, record_expires_at = :expiresAt
                where user_id = :userId and request_key = :requestKey
                  and game_id is null and record_expires_at is null
                  and normalized_input = cast(:input as jsonb)
                  and claim_id = :claimId
                  and pg_current_xact_id() = cast(:claimXid as xid8)
                  and isfinite(cast(:expiresAt as timestamptz))
                returning game_id, record_expires_at
                """)
            .param("gameId", gameId)
            .param("expiresAt", recordExpiresAt.atOffset(UTC))
            .param("userId", claim.userId)
            .param("requestKey", claim.requestKey)
            .param("input", writeInput(claim.input))
            .param("claimId", claim.id)
            .param("claimXid", claim.xid)
            .query((row, rowNum) ->
                row.getLong("game_id") == gameId
                    && recordExpiresAt.equals(
                        row.getObject("record_expires_at", OffsetDateTime.class).toInstant()))
            .list();
    if (!completed.equals(List.of(true))) {
      throw new DomainException(PublicationErrorCode.PUBLICATION_CLAIM_COMPLETION_FAILED);
    }
  }

  private static void validateRequestKey(String key) {
    if (key == null
        || key.isEmpty()
        || key.length() > 255
        || key.indexOf('\0') >= 0
        || !UTF_8.newEncoder().canEncode(key)
        || key.getBytes(UTF_8).length > 255) {
      throw new DomainException(PublicationErrorCode.INVALID_PUBLICATION_REQUEST_KEY);
    }
  }

  private static PublicationIdempotencyRecord readRecord(ResultSet row) throws SQLException {
    String json = row.getString("normalized_input");
    PublicationInput input;
    try {
      input = JSON.readValue(json, PublicationInput.class);
    } catch (JsonProcessingException exception) {
      throw new DomainException(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD);
    }
    Long gameId = row.getObject("game_id", Long.class);
    OffsetDateTime expiry = row.getObject("record_expires_at", OffsetDateTime.class);
    if (input == null
        || (gameId == null) != (expiry == null)
        || (expiry != null && !row.getBoolean("finite_expiry"))) {
      throw new DomainException(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD);
    }
    return new PublicationIdempotencyRecord(
        row.getLong("user_id"),
        row.getString("request_key"),
        input,
        gameId,
        expiry == null ? null : expiry.toInstant(),
        row.getObject("claim_id", UUID.class));
  }

  private static String writeInput(PublicationInput input) {
    try {
      return JSON.writeValueAsString(Objects.requireNonNull(input));
    } catch (JsonProcessingException exception) {
      throw new DomainException(
          PublicationErrorCode.PUBLICATION_INPUT_SERIALIZATION_FAILED, exception);
    }
  }

  // Only the transaction that reserved this generation can complete its claim.
  @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
  public static final class Claim implements Reservation {

    private final long userId;
    private final String requestKey;
    private final PublicationInput input;
    private final String xid;
    private final UUID id;
  }
}

package org.kirikick.api.publication.repository;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.ZoneOffset.UTC;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.common.exception.DomainException;
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
            select user_id, request_key, normalized_input, game_id, record_expires_at,
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
        expiry == null ? null : expiry.toInstant());
  }

  private static String writeInput(PublicationInput input) {
    try {
      return JSON.writeValueAsString(Objects.requireNonNull(input));
    } catch (JsonProcessingException exception) {
      throw new DomainException(
          PublicationErrorCode.PUBLICATION_INPUT_SERIALIZATION_FAILED, exception);
    }
  }

  // Only a successful INSERT can issue a claim, and only its transaction can complete it.
  @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
  public static final class Claim {

    private final long userId;
    private final String requestKey;
    private final PublicationInput input;
    private final String xid;
    private final UUID id;
  }
}

package org.kirikick.api.publication.repository;

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
import lombok.RequiredArgsConstructor;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.common.infra.DbTimeSource;
import org.kirikick.api.publication.domain.PublicationIdempotencyRecord;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.domain.PublicationKind;
import org.kirikick.api.publication.domain.PublicationRequestKey;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.kirikick.api.publication.repository.model.ExistingReservation;
import org.kirikick.api.publication.repository.model.LockedReservation;
import org.kirikick.api.publication.repository.model.PublicationClaim;
import org.kirikick.api.publication.repository.model.PublicationReplay;
import org.kirikick.api.publication.repository.model.PublicationReservation;
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

  public PublicationReservation reserveNkCreate(
      long userId, PublicationRequestKey requestKey, PublicationInput input) {
    if (input.kind() != PublicationKind.NK_CREATE)
      throw new DomainException(PublicationErrorCode.INVALID_PUBLICATION_TARGET);
    Optional<PublicationClaim> first = tryClaim(userId, requestKey, input);
    if (first.isPresent()) return first.get();
    PublicationIdempotencyRecord locked =
        findForUpdate(userId, requestKey)
            .orElseThrow(
                () -> new DomainException(PublicationErrorCode.PUBLICATION_CLAIM_STATE_INVALID));
    PublicationReplay original = restore(locked);
    Instant cutoff = clock.readCurrentTime();
    if (!cutoff.isBefore(locked.recordExpiresAt())) return replaceExpired(locked, input, cutoff);
    if (!input.equals(locked.normalizedInput()))
      throw new DomainException(PublicationErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    return original;
  }

  /** Acquires a kick-add record without interpreting stored JSON or expiry before authorization. */
  public LockedReservation reserveNkKickAdd(
      long userId, PublicationRequestKey requestKey, PublicationInput input) {
    if (input.kind() != PublicationKind.NK_KICK_ADD)
      throw new DomainException(PublicationErrorCode.INVALID_PUBLICATION_TARGET);
    Optional<PublicationClaim> first = tryClaim(userId, requestKey, input);
    if (first.isPresent()) return first.get();
    return findRawForUpdate(userId, requestKey)
        .orElseThrow(
            () -> new DomainException(PublicationErrorCode.PUBLICATION_CLAIM_STATE_INVALID));
  }

  /**
   * Checks only the raw completion foreign key after authorization; it deliberately does not decode
   * the stored input or expiry.
   */
  public boolean existingReservationReferencesGameInRoom(
      ExistingReservation existing, long roomId) {
    if (existing.gameId() == null) {
      return false;
    }
    return jdbc.sql("select exists (select 1 from games where id = :gameId and room_id = :roomId)")
        .param("gameId", existing.gameId())
        .param("roomId", roomId)
        .query(Boolean.class)
        .single();
  }

  /** Resolves an authorized, locked kick-add record. */
  public PublicationReservation resolveNkKickAdd(
      ExistingReservation existing, PublicationInput request, Instant cutoff) {
    if (request.kind() != PublicationKind.NK_KICK_ADD || cutoff == null)
      throw new DomainException(PublicationErrorCode.INVALID_PUBLICATION_TARGET);
    PublicationIdempotencyRecord locked = readRecord(existing);
    PublicationReplay replay = restore(locked);
    if (!cutoff.isBefore(locked.recordExpiresAt())) return replaceExpired(locked, request, cutoff);
    if (!request.equals(locked.normalizedInput()))
      throw new DomainException(PublicationErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    return replay;
  }

  private PublicationReplay restore(PublicationIdempotencyRecord locked) {
    if (!locked.isComplete())
      throw new DomainException(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD);
    return switch (locked.normalizedInput().kind()) {
      case NK_CREATE -> restoreNkCreate(locked);
      case NK_KICK_ADD -> restoreNkKickAdd(locked);
    };
  }

  private PublicationReplay restoreNkCreate(PublicationIdempotencyRecord locked) {
    return jdbc.sql(
            """
            select r.id as room_id, g.id as game_id, r.opened_at, r.closes_at
            from games g join rooms r on r.id = g.room_id
            where g.id = :gameId and r.host_user_id = :userId
              and g.question_preset_id = :questionPresetId
              and r.selected_reward_preset_id = :rewardPresetId
              and isfinite(r.opened_at) and isfinite(r.closes_at)
              and (select count(*) from choices c where c.game_id = g.id) = 4
              and exists (select 1 from choices c where c.game_id = g.id and c.id = g.correct_choice_id)
            """)
        .param("gameId", locked.gameId())
        .param("userId", locked.userId())
        .param("questionPresetId", locked.normalizedInput().questionPresetId())
        .param("rewardPresetId", locked.normalizedInput().rewardPresetId())
        .query((row, rowNum) -> replay(row))
        .optional()
        .filter(replay ->
            Duration.between(replay.openedAt(), replay.closesAt()).equals(Duration.ofHours(24)))
        .orElseThrow(() -> new DomainException(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD));
  }

  private PublicationReplay restoreNkKickAdd(PublicationIdempotencyRecord locked) {
    return jdbc.sql(
            """
            select r.id as room_id, g.id as game_id, r.opened_at, r.closes_at
            from games g join rooms r on r.id = g.room_id
            where g.id = :gameId and r.host_user_id = :userId
              and r.id = :roomId and g.question_preset_id = :questionPresetId
              and isfinite(r.opened_at) and isfinite(r.closes_at)
              and (select count(*) from choices c where c.game_id = g.id) = 4
              and exists (select 1 from choices c where c.game_id = g.id and c.id = g.correct_choice_id)
            """)
        .param("gameId", locked.gameId())
        .param("userId", locked.userId())
        .param("roomId", locked.normalizedInput().roomId())
        .param("questionPresetId", locked.normalizedInput().questionPresetId())
        .query((row, rowNum) -> replay(row))
        .optional()
        .filter(replay ->
            Duration.between(replay.openedAt(), replay.closesAt()).equals(Duration.ofHours(24)))
        .orElseThrow(() -> new DomainException(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD));
  }

  private static PublicationReplay replay(ResultSet row) throws SQLException {
    return new PublicationReplay(
        row.getLong("room_id"),
        row.getLong("game_id"),
        row.getObject("opened_at", OffsetDateTime.class).toInstant(),
        row.getObject("closes_at", OffsetDateTime.class).toInstant());
  }

  private PublicationClaim replaceExpired(
      PublicationIdempotencyRecord locked, PublicationInput input, Instant cutoff) {
    return jdbc.sql(
            """
            update publication_idempotency
               set claim_id = gen_random_uuid(), normalized_input = cast(:input as jsonb), game_id = null, record_expires_at = null
             where user_id = :userId and request_key = :requestKey and claim_id = :oldClaimId and game_id = :oldGameId
               and record_expires_at = :oldExpiry and record_expires_at <= :cutoff
            returning claim_id, pg_current_xact_id()::text as xid
            """)
        .param("input", writeInput(input))
        .param("userId", locked.userId())
        .param("requestKey", locked.requestKey().value())
        .param("oldClaimId", locked.claimId())
        .param("oldGameId", locked.gameId())
        .param("oldExpiry", locked.recordExpiresAt().atOffset(UTC))
        .param("cutoff", cutoff.atOffset(UTC))
        .query((row, rowNum) ->
            new PublicationClaim(
                locked.userId(),
                locked.requestKey(),
                input,
                row.getString("xid"),
                row.getObject("claim_id", UUID.class)))
        .optional()
        .orElseThrow(
            () -> new DomainException(PublicationErrorCode.PUBLICATION_CLAIM_STATE_INVALID));
  }

  public Optional<PublicationClaim> tryClaim(
      long userId, PublicationRequestKey requestKey, PublicationInput input) {
    return jdbc.sql(
            """
            insert into publication_idempotency (user_id, request_key, normalized_input)
            values (:userId, :requestKey, cast(:input as jsonb))
            on conflict (user_id, request_key) do nothing
            returning cast(pg_current_xact_id() as text) as xid, claim_id
            """)
        .param("userId", userId)
        .param("requestKey", requestKey.value())
        .param("input", writeInput(input))
        .query((row, rowNum) ->
            new PublicationClaim(
                userId,
                requestKey,
                input,
                row.getString("xid"),
                row.getObject("claim_id", UUID.class)))
        .optional();
  }

  public Optional<PublicationIdempotencyRecord> findForUpdate(
      long userId, PublicationRequestKey requestKey) {
    return findRawForUpdate(userId, requestKey).map(PublicationIdempotencyRepository::readRecord);
  }

  private Optional<ExistingReservation> findRawForUpdate(
      long userId, PublicationRequestKey requestKey) {
    return jdbc.sql(
            """
            select user_id, request_key, normalized_input, game_id, record_expires_at::text as raw_expiry, claim_id,
                   isfinite(record_expires_at) as finite_expiry
            from publication_idempotency
            where user_id = :userId and request_key = :requestKey
            for update
            """)
        .param("userId", userId)
        .param("requestKey", requestKey.value())
        .query((row, rowNum) ->
            new ExistingReservation(
                row.getLong("user_id"),
                new PublicationRequestKey(row.getString("request_key")),
                row.getString("normalized_input"),
                row.getObject("game_id", Long.class),
                row.getString("raw_expiry"),
                row.getBoolean("finite_expiry"),
                row.getObject("claim_id", UUID.class)))
        .optional();
  }

  public void completeClaim(PublicationClaim claim, long gameId, Instant recordExpiresAt) {
    if (recordExpiresAt == null)
      throw new DomainException(PublicationErrorCode.PUBLICATION_CLAIM_COMPLETION_FAILED);
    verifyPublishedGameMatchesInput(claim.userId(), gameId, claim.input());
    List<Boolean> completed =
        jdbc.sql(
                """
                update publication_idempotency set game_id = :gameId, record_expires_at = :expiresAt
                 where user_id = :userId and request_key = :requestKey and game_id is null and record_expires_at is null
                   and normalized_input = cast(:input as jsonb) and claim_id = :claimId
                   and pg_current_xact_id() = cast(:claimXid as xid8) and isfinite(cast(:expiresAt as timestamptz))
                returning game_id, record_expires_at
                """)
            .param("gameId", gameId)
            .param("expiresAt", recordExpiresAt.atOffset(UTC))
            .param("userId", claim.userId())
            .param("requestKey", claim.requestKey().value())
            .param("input", writeInput(claim.input()))
            .param("claimId", claim.id())
            .param("claimXid", claim.xid())
            .query((row, rowNum) ->
                row.getLong("game_id") == gameId
                    && recordExpiresAt.equals(
                        row.getObject("record_expires_at", OffsetDateTime.class).toInstant()))
            .list();
    if (!completed.equals(List.of(true)))
      throw new DomainException(PublicationErrorCode.PUBLICATION_CLAIM_COMPLETION_FAILED);
  }

  private void verifyPublishedGameMatchesInput(long userId, long gameId, PublicationInput input) {
    String roomCondition =
        input.kind() == PublicationKind.NK_CREATE
            ? "and r.selected_reward_preset_id = :rewardPresetId"
            : "and r.id = :roomId";
    boolean matches =
        jdbc.sql(
                """
                select exists(select 1 from games g join rooms r on r.id = g.room_id
                where g.id = :gameId and r.host_user_id = :userId and g.question_preset_id = :questionPresetId
                """
                    + roomCondition
                    + ")")
            .param("gameId", gameId)
            .param("userId", userId)
            .param("questionPresetId", input.questionPresetId())
            .param("rewardPresetId", input.rewardPresetId())
            .param("roomId", input.roomId())
            .query(Boolean.class)
            .single();
    if (!matches) throw new DomainException(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD);
  }

  private static PublicationIdempotencyRecord readRecord(ExistingReservation row) {
    PublicationInput input;
    try {
      input = JSON.readValue(row.rawInput(), PublicationInput.class);
    } catch (JsonProcessingException | RuntimeException exception) {
      throw new DomainException(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD);
    }
    if (input == null
        || (row.gameId() == null) != (row.rawExpiry() == null)
        || (row.rawExpiry() != null && !row.finiteExpiry()))
      throw new DomainException(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD);
    Instant expiry = null;
    if (row.rawExpiry() != null)
      try {
        expiry = OffsetDateTime.parse(row.rawExpiry().replace(" ", "T")).toInstant();
      } catch (RuntimeException exception) {
        throw new DomainException(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD);
      }
    return new PublicationIdempotencyRecord(
        row.userId(), row.requestKey(), input, row.gameId(), expiry, row.claimId());
  }

  private static String writeInput(PublicationInput input) {
    try {
      return JSON.writeValueAsString(Objects.requireNonNull(input));
    } catch (JsonProcessingException exception) {
      throw new DomainException(
          PublicationErrorCode.PUBLICATION_INPUT_SERIALIZATION_FAILED, exception);
    }
  }
}

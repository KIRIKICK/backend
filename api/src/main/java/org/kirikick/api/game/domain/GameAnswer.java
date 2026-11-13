package org.kirikick.api.game.domain;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.kirikick.api.common.entity.BaseCreatedTimeEntity;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.game.domain.exception.GameErrorCode;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
    name = "game_answers",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_game_answers_uuid", columnNames = "uuid"),
      @UniqueConstraint(name = "uk_game_answers_participant", columnNames = "participant_id")
    })
public class GameAnswer extends BaseCreatedTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, updatable = false)
  private UUID uuid;

  @Column(name = "game_id", nullable = false, updatable = false)
  private Long gameId;

  @Column(name = "participant_id", nullable = false, updatable = false)
  private Long participantId;

  @Column(name = "selected_option_key", updatable = false, length = 1)
  private String selectedOptionKey;

  @Column(name = "target_participant_id", updatable = false)
  private Long targetParticipantId;

  @Column(name = "accepted_at", nullable = false, updatable = false)
  private Instant acceptedAt;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification = "Private constructor validates domain invariants.")
  private GameAnswer(
      Long gameId,
      Long participantId,
      String selectedOptionKey,
      Long targetParticipantId,
      Instant acceptedAt) {
    boolean optionAnswer =
        selectedOptionKey != null
            && selectedOptionKey.matches("[1-4]")
            && targetParticipantId == null;
    boolean voteAnswer =
        selectedOptionKey == null
            && targetParticipantId != null
            && targetParticipantId > 0
            && !targetParticipantId.equals(participantId);
    if (gameId == null
        || gameId <= 0
        || participantId == null
        || participantId <= 0
        || acceptedAt == null
        || (!optionAnswer && !voteAnswer)) {
      throw new DomainException(GameErrorCode.INVALID_ANSWER);
    }
    this.uuid = UUID.randomUUID();
    this.gameId = gameId;
    this.participantId = participantId;
    this.selectedOptionKey = selectedOptionKey;
    this.targetParticipantId = targetParticipantId;
    this.acceptedAt = acceptedAt;
  }

  public static GameAnswer forOption(
      Long gameId, Long participantId, String selectedOptionKey, Instant acceptedAt) {
    return new GameAnswer(gameId, participantId, selectedOptionKey, null, acceptedAt);
  }

  public static GameAnswer forVote(
      Long gameId, Long participantId, Long targetParticipantId, Instant acceptedAt) {
    return new GameAnswer(gameId, participantId, null, targetParticipantId, acceptedAt);
  }
}

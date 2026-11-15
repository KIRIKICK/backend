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
import org.kirikick.api.common.entity.BaseTimeEntity;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.game.domain.exception.GameErrorCode;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
    name = "game_participants",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_game_participants_uuid", columnNames = "uuid"),
      @UniqueConstraint(
          name = "uk_game_participants_game_user",
          columnNames = {"game_id", "user_id"}),
      @UniqueConstraint(
          name = "uk_game_participants_game_id",
          columnNames = {"game_id", "id"})
    })
public class GameParticipant extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, updatable = false)
  private UUID uuid;

  @Column(name = "game_id", nullable = false, updatable = false)
  private Long gameId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private Long userId;

  @Column(name = "started_at")
  private Instant startedAt;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification = "Private constructor validates domain invariants.")
  private GameParticipant(Long gameId, Long userId) {
    if (gameId == null || gameId <= 0 || userId == null || userId <= 0) {
      throw new DomainException(GameErrorCode.INVALID_PARTICIPANT);
    }
    this.uuid = UUID.randomUUID();
    this.gameId = gameId;
    this.userId = userId;
  }

  public static GameParticipant create(Long gameId, Long userId) {
    return new GameParticipant(gameId, userId);
  }

  public void start(Instant startedAt) {
    if (startedAt == null) {
      throw new DomainException(GameErrorCode.INVALID_PARTICIPANT);
    }
    if (this.startedAt == null) {
      this.startedAt = startedAt;
    }
  }
}

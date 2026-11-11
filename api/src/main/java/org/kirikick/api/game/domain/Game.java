package org.kirikick.api.game.domain;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
    name = "games",
    uniqueConstraints = @UniqueConstraint(name = "uk_games_uuid", columnNames = "uuid"))
public class Game extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, updatable = false)
  private UUID uuid;

  @Column(name = "room_id", nullable = false, updatable = false)
  private Long roomId;

  @Column(name = "creator_id", nullable = false, updatable = false)
  private Long creatorId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false, length = 20)
  private GameType type;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private GameStatus status;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification = "Private constructor validates domain invariants.")
  private Game(Long roomId, Long creatorId, GameType type, Instant expiresAt) {
    if (roomId == null
        || roomId <= 0
        || creatorId == null
        || creatorId <= 0
        || type == null
        || expiresAt == null) {
      throw new DomainException(GameErrorCode.INVALID_GAME);
    }
    this.uuid = UUID.randomUUID();
    this.roomId = roomId;
    this.creatorId = creatorId;
    this.type = type;
    this.status = GameStatus.IN_PROGRESS;
    this.expiresAt = expiresAt;
  }

  public static Game create(Long roomId, Long creatorId, GameType type, Instant expiresAt) {
    return new Game(roomId, creatorId, type, expiresAt);
  }
}

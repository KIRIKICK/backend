package org.kirikick.api.room.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.kirikick.api.common.entity.BaseCreatedTimeEntity;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.reward.domain.RewardPreset;
import org.kirikick.api.room.domain.exception.RoomErrorCode;
import org.kirikick.api.user.domain.User;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "rooms")
@DynamicUpdate
public class Room extends BaseCreatedTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "host_user_id", nullable = false, updatable = false)
  private User host;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "selected_reward_preset_id", nullable = false, updatable = false)
  private RewardPreset selectedRewardPreset;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private RoomStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "result_state", nullable = false, length = 20)
  private RoomResultState resultState;

  @Column(name = "opened_at")
  private Instant openedAt;

  @Column(name = "closes_at")
  private Instant closesAt;

  public static Room prepare(User host, RewardPreset selectedRewardPreset) {
    Room room = new Room();
    room.host = Objects.requireNonNull(host);
    room.selectedRewardPreset = Objects.requireNonNull(selectedRewardPreset);
    room.status = RoomStatus.OPEN;
    room.resultState = RoomResultState.NOT_READY;
    return room;
  }

  public void publish(Instant openedAt, Instant closesAt) {
    if (status != RoomStatus.OPEN || this.openedAt != null || this.closesAt != null) {
      throw new DomainException(RoomErrorCode.ROOM_ALREADY_PUBLISHED);
    }
    if (openedAt == null || !openedAt.plus(Duration.ofHours(24)).equals(closesAt)) {
      throw new DomainException(RoomErrorCode.INVALID_CLOSING_TIME);
    }
    this.openedAt = openedAt;
    this.closesAt = closesAt;
  }

  public void closeIntake() {
    if (openedAt == null || closesAt == null) {
      throw new DomainException(RoomErrorCode.INCOMPLETE_PUBLICATION_TIME);
    }
    status = RoomStatus.CLOSED;
  }
}

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
import org.kirikick.api.reward.domain.RewardPreset;
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
  private Status status;

  @Enumerated(EnumType.STRING)
  @Column(name = "result_state", nullable = false, length = 20)
  private ResultState resultState;

  @Column(name = "opened_at")
  private Instant openedAt;

  @Column(name = "closes_at")
  private Instant closesAt;

  public static Room prepare(User host, RewardPreset selectedRewardPreset) {
    Room room = new Room();
    room.host = Objects.requireNonNull(host);
    room.selectedRewardPreset = Objects.requireNonNull(selectedRewardPreset);
    room.status = Status.OPEN;
    room.resultState = ResultState.NOT_READY;
    return room;
  }

  public void publish(Instant openedAt, Instant closesAt) {
    if (status != Status.OPEN || this.openedAt != null || this.closesAt != null) {
      throw new IllegalStateException("조립 중인 방만 공개할 수 있습니다.");
    }
    if (openedAt == null || !openedAt.plus(Duration.ofHours(24)).equals(closesAt)) {
      throw new IllegalArgumentException("마감 시각은 공개 시각에서 24시간 뒤여야 합니다.");
    }
    this.openedAt = openedAt;
    this.closesAt = closesAt;
  }

  public void closeIntake() {
    if (openedAt == null || closesAt == null) {
      throw new IllegalStateException("공개 시각이 불완전한 방은 접수 종료할 수 없습니다.");
    }
    status = Status.CLOSED;
  }

  public enum Status {
    OPEN,
    CLOSED
  }

  public enum ResultState {
    NOT_READY,
    FINAL
  }
}

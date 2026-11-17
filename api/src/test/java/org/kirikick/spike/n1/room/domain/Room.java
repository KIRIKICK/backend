package org.kirikick.spike.n1.room.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.kirikick.api.common.entity.BaseCreatedTimeEntity;
import org.kirikick.api.user.domain.User;
import org.kirikick.spike.n1.reward.domain.RewardPreset;

@Getter
@Entity
@Table(name = "rooms")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

  @Column(name = "opened_at")
  private Instant openedAt;

  @Column(name = "confirmed_early_end_at")
  private Instant confirmedEarlyEndAt;

  private Room(User host, RewardPreset selectedRewardPreset) {
    if (host == null || selectedRewardPreset == null) {
      throw new IllegalArgumentException("방장과 선택 보상이 필요합니다.");
    }
    this.host = host;
    this.selectedRewardPreset = selectedRewardPreset;
  }

  public static Room create(User host, RewardPreset selectedRewardPreset) {
    return new Room(host, selectedRewardPreset);
  }

  public void open(Instant openedAt) {
    if (this.openedAt != null) {
      throw new IllegalStateException("방의 공개 기준 시각은 이미 기록되어 있습니다.");
    }
    if (openedAt == null) {
      throw new IllegalArgumentException("서버 공개 기준 시각이 필요합니다.");
    }
    this.openedAt = openedAt;
  }

  public Instant getClosesAt() {
    if (openedAt == null) {
      throw new IllegalStateException("아직 방의 공개 기준 시각이 없습니다.");
    }
    return openedAt.plus(24, ChronoUnit.HOURS);
  }
}

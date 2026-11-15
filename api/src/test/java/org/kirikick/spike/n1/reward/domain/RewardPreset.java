package org.kirikick.spike.n1.reward.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "reward_presets")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RewardPreset {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "title", nullable = false, columnDefinition = "TEXT")
  private String title;

  @Column(name = "description", nullable = false, columnDefinition = "TEXT")
  private String description;

  private RewardPreset(String title, String description) {
    if (title == null || title.isBlank() || description == null || description.isBlank()) {
      throw new IllegalArgumentException("보상 제목과 설명이 필요합니다.");
    }
    this.title = title;
    this.description = description;
  }

  public static RewardPreset create(String title, String description) {
    return new RewardPreset(title, description);
  }
}

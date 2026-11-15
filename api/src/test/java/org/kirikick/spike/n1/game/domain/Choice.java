package org.kirikick.spike.n1.game.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "choices")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Choice {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "game_id", nullable = false, updatable = false)
  private Game game;

  @Column(name = "position", nullable = false, updatable = false)
  private int position;

  @Column(name = "text_snapshot", nullable = false, updatable = false, columnDefinition = "TEXT")
  private String textSnapshot;

  private Choice(Game game, int position, String textSnapshot) {
    if (game == null || textSnapshot == null || textSnapshot.isBlank()) {
      throw new IllegalArgumentException("소속 게임과 선택지 문구가 필요합니다.");
    }
    this.game = game;
    this.position = position;
    this.textSnapshot = textSnapshot;
  }

  public static Choice create(Game game, int position, String textSnapshot) {
    return new Choice(game, position, textSnapshot);
  }
}

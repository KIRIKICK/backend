package org.kirikick.api.game.domain;

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
import org.kirikick.api.question.domain.QuestionPresetChoice;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "choices")
public class Choice {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "game_id", nullable = false, updatable = false)
  private Game game;

  @Column(nullable = false, updatable = false)
  private int position;

  @Column(name = "text_snapshot", nullable = false, updatable = false, columnDefinition = "text")
  private String textSnapshot;

  public static Choice copyOf(Game game, QuestionPresetChoice source, int position) {
    Long questionId = game.getQuestionPreset().getId();
    if (questionId == null
        || position < 0
        || position >= 4
        || !questionId.equals(source.getQuestionPreset().getId())) {
      throw new IllegalArgumentException("선택지는 같은 질문의 원본과 0~3의 위치가 필요합니다.");
    }
    Choice choice = new Choice();
    choice.game = game;
    choice.position = position;
    choice.textSnapshot = source.getText();
    return choice;
  }
}

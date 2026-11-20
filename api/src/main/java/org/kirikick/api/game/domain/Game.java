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
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.kirikick.api.common.entity.BaseCreatedTimeEntity;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.game.domain.exception.GameErrorCode;
import org.kirikick.api.question.domain.QuestionPreset;
import org.kirikick.api.room.domain.Room;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "games")
public class Game extends BaseCreatedTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "room_id", nullable = false, updatable = false)
  private Room room;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "question_preset_id", nullable = false, updatable = false)
  private QuestionPreset questionPreset;

  @Column(
      name = "question_text_snapshot",
      nullable = false,
      updatable = false,
      columnDefinition = "text")
  private String questionTextSnapshot;

  @Column(name = "correct_choice_id")
  private Long correctChoiceId;

  public static Game prepare(Room room, QuestionPreset questionPreset) {
    Game game = new Game();
    game.room = Objects.requireNonNull(room);
    game.questionPreset = Objects.requireNonNull(questionPreset);
    game.questionTextSnapshot = questionPreset.getText();
    return game;
  }

  public void initializeCorrectChoice(Choice choice) {
    if (correctChoiceId != null) {
      throw new DomainException(GameErrorCode.CORRECT_CHOICE_ALREADY_INITIALIZED);
    }
    if (id == null
        || choice == null
        || choice.getId() == null
        || !id.equals(choice.getGame().getId())) {
      throw new DomainException(GameErrorCode.INVALID_CORRECT_CHOICE);
    }
    correctChoiceId = choice.getId();
  }

  public void verifyComplete(List<Choice> persistedChoices) {
    if (id == null
        || correctChoiceId == null
        || persistedChoices.size() != 4
        || persistedChoices.stream()
            .anyMatch(choice -> choice.getId() == null || !id.equals(choice.getGame().getId()))
        || persistedChoices.stream().map(Choice::getId).distinct().count() != 4
        || persistedChoices.stream().noneMatch(choice -> correctChoiceId.equals(choice.getId()))) {
      throw new DomainException(GameErrorCode.INCOMPLETE_GAME);
    }
  }
}

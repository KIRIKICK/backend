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
import org.kirikick.api.common.entity.BaseCreatedTimeEntity;
import org.kirikick.spike.n1.room.domain.Room;

@Getter
@Entity
@Table(name = "games")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Game extends BaseCreatedTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "room_id", nullable = false, updatable = false)
  private Room room;

  @Column(name = "ordinal", nullable = false, updatable = false)
  private int ordinal;

  @Column(
      name = "question_text_snapshot",
      nullable = false,
      updatable = false,
      columnDefinition = "TEXT")
  private String questionTextSnapshot;

  @Column(name = "correct_choice_id")
  private Long correctChoiceId;

  private Game(Room room, int ordinal, String questionTextSnapshot) {
    if (room == null || questionTextSnapshot == null || questionTextSnapshot.isBlank()) {
      throw new IllegalArgumentException("소속 방과 질문 문구가 필요합니다.");
    }
    this.room = room;
    this.ordinal = ordinal;
    this.questionTextSnapshot = questionTextSnapshot;
  }

  public static Game create(Room room, int ordinal, String questionTextSnapshot) {
    return new Game(room, ordinal, questionTextSnapshot);
  }

  public void initializeCorrectChoice(Choice choice) {
    if (correctChoiceId != null) {
      throw new IllegalStateException("정답은 이미 연결되어 있습니다.");
    }
    if (id == null
        || choice == null
        || choice.getId() == null
        || !id.equals(choice.getGame().getId())) {
      throw new IllegalArgumentException("저장된 같은 게임의 선택지가 필요합니다.");
    }
    correctChoiceId = choice.getId();
  }
}

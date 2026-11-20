package org.kirikick.api.game.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.game.domain.exception.GameErrorCode;
import org.kirikick.api.question.domain.QuestionPreset;
import org.kirikick.api.question.domain.QuestionPresetChoice;
import org.kirikick.api.room.domain.Room;

class GameTest {

  @Test
  @DisplayName("이미 연결된 정답은 도메인 오류 코드로 거절한다")
  void rejectsAlreadyInitializedCorrectChoice() {
    Game game = game();
    setField(game, "correctChoiceId", 2L);

    assertCode(
        () -> game.initializeCorrectChoice(null), GameErrorCode.CORRECT_CHOICE_ALREADY_INITIALIZED);
  }

  @Test
  @DisplayName("다른 게임의 선택지는 도메인 오류 코드로 거절한다")
  void rejectsCorrectChoiceFromAnotherGame() {
    Game game = game();
    Choice choice = mock(Choice.class);
    Game anotherGame = mock(Game.class);
    given(choice.getId()).willReturn(2L);
    given(choice.getGame()).willReturn(anotherGame);
    given(anotherGame.getId()).willReturn(3L);

    assertCode(() -> game.initializeCorrectChoice(choice), GameErrorCode.INVALID_CORRECT_CHOICE);
  }

  @Test
  @DisplayName("공개 요건을 갖추지 않은 게임은 도메인 오류 코드로 거절한다")
  void rejectsIncompleteGame() {
    assertCode(() -> game().verifyComplete(List.of()), GameErrorCode.INCOMPLETE_GAME);
  }

  @Test
  @DisplayName("잘못된 선택지 원본은 도메인 오류 코드로 거절한다")
  void rejectsInvalidChoiceSource() {
    Game game = game();
    QuestionPresetChoice source = mock(QuestionPresetChoice.class);
    QuestionPreset anotherQuestion = mock(QuestionPreset.class);
    given(source.getQuestionPreset()).willReturn(anotherQuestion);
    given(anotherQuestion.getId()).willReturn(2L);

    assertCode(() -> Choice.copyOf(game, source, 0), GameErrorCode.INVALID_CHOICE);
  }

  private static Game game() {
    QuestionPreset question = mock(QuestionPreset.class);
    given(question.getText()).willReturn("질문");
    given(question.getId()).willReturn(1L);
    Game game = Game.prepare(mock(Room.class), question);
    setField(game, "id", 1L);
    return game;
  }

  private static void assertCode(Runnable action, GameErrorCode errorCode) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
  }
}

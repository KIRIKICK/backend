package org.kirikick.api.game.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.common.exception.DomainException;

class GameDomainTest {

  private static final Instant STARTED_AT = Instant.parse("2030-01-02T00:00:00Z");
  private static final Instant ACCEPTED_AT = STARTED_AT.plusSeconds(10);

  @ParameterizedTest
  @EnumSource(GameType.class)
  void createsInProgressGameWithExplicitDeadline(GameType type) {
    Game game = Game.create(1L, 2L, type, STARTED_AT.plusSeconds(7200));

    assertThat(game.getUuid()).isNotNull();
    assertThat(game.getRoomId()).isEqualTo(1L);
    assertThat(game.getCreatorId()).isEqualTo(2L);
    assertThat(game.getType()).isEqualTo(type);
    assertThat(game.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
    assertThat(game.getExpiresAt()).isEqualTo(STARTED_AT.plusSeconds(7200));
  }

  @Test
  void rejectsMissingGameReferencesTypeAndDeadline() {
    assertThatThrownBy(() -> Game.create(null, 1L, GameType.NAZAL, STARTED_AT))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> Game.create(1L, 0L, GameType.NAZAL, STARTED_AT))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> Game.create(1L, 2L, null, STARTED_AT))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> Game.create(1L, 2L, GameType.NAZAL, null))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void rosterEntryDoesNotStartUntilRequestedAndRetainsFirstStart() {
    GameParticipant participant = GameParticipant.create(1L, 2L);
    assertThat(participant.getStartedAt()).isNull();
    assertThat(participant.getUuid()).isNotNull();

    participant.start(STARTED_AT);
    participant.start(ACCEPTED_AT);

    assertThat(participant.getStartedAt()).isEqualTo(STARTED_AT);
    assertThatThrownBy(() -> participant.start(null)).isInstanceOf(DomainException.class);
    assertThat(participant.getStartedAt()).isEqualTo(STARTED_AT);
  }

  @Test
  void rejectsInvalidParticipantReferences() {
    assertThatThrownBy(() -> GameParticipant.create(null, 1L)).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> GameParticipant.create(1L, 0L)).isInstanceOf(DomainException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "2", "3", "4"})
  void optionAnswerStoresOnlyOptionAndServerReceipt(String option) {
    GameAnswer answer = GameAnswer.forOption(1L, 2L, option, ACCEPTED_AT);

    assertThat(answer.getUuid()).isNotNull();
    assertThat(answer.getGameId()).isEqualTo(1L);
    assertThat(answer.getParticipantId()).isEqualTo(2L);
    assertThat(answer.getSelectedOptionKey()).isEqualTo(option);
    assertThat(answer.getTargetParticipantId()).isNull();
    assertThat(answer.getAcceptedAt()).isEqualTo(ACCEPTED_AT);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"0", "5", "11", " 1", "A"})
  void rejectsInvalidOption(String option) {
    assertThatThrownBy(() -> GameAnswer.forOption(1L, 2L, option, ACCEPTED_AT))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void voteStoresOnlyTheOtherParticipant() {
    GameAnswer answer = GameAnswer.forVote(1L, 2L, 3L, ACCEPTED_AT);

    assertThat(answer.getSelectedOptionKey()).isNull();
    assertThat(answer.getTargetParticipantId()).isEqualTo(3L);
    assertThat(answer.getAcceptedAt()).isEqualTo(ACCEPTED_AT);
  }

  @Test
  void rejectsSelfVoteMissingTargetAndMissingReceipt() {
    for (Long target : new Long[] {null, 0L, -1L, 2L}) {
      assertThatThrownBy(() -> GameAnswer.forVote(1L, 2L, target, ACCEPTED_AT))
          .isInstanceOf(DomainException.class);
    }
    assertThatThrownBy(() -> GameAnswer.forOption(1L, 2L, "1", null))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> GameAnswer.forVote(1L, 2L, 3L, null))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> GameAnswer.forOption(0L, 2L, "1", ACCEPTED_AT))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> GameAnswer.forOption(1L, null, "1", ACCEPTED_AT))
        .isInstanceOf(DomainException.class);
  }
}

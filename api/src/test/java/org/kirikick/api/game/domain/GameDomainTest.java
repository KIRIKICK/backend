package org.kirikick.api.game.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
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
  @DisplayName("게임을 생성하면 종류와 마감 시각이 설정되고 진행 중 상태가 된다")
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
  @DisplayName("방 ID, 출제자 ID, 게임 종류 또는 마감 시각이 유효하지 않으면 게임을 생성할 수 없다")
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
  @DisplayName("참여자의 풀이 시작 시각은 처음 시작할 때 기록되며 이후 유지된다")
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
  @DisplayName("게임 ID 또는 회원 ID가 유효하지 않으면 참여자를 생성할 수 없다")
  void rejectsInvalidParticipantReferences() {
    assertThatThrownBy(() -> GameParticipant.create(null, 1L)).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> GameParticipant.create(1L, 0L)).isInstanceOf(DomainException.class);
  }

  @ParameterizedTest
  @DisplayName("선택지 답변에는 선택지와 접수 시각이 설정되고 투표 대상은 비어 있다")
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
  @DisplayName("선택지가 없거나 1부터 4까지의 문자열이 아니면 답변을 생성할 수 없다")
  @NullAndEmptySource
  @ValueSource(strings = {"0", "5", "11", " 1", "A"})
  void rejectsInvalidOption(String option) {
    assertThatThrownBy(() -> GameAnswer.forOption(1L, 2L, option, ACCEPTED_AT))
        .isInstanceOf(DomainException.class);
  }

  @Test
  @DisplayName("투표 답변에는 다른 참여자와 접수 시각이 설정되고 선택지는 비어 있다")
  void voteStoresOnlyTheOtherParticipant() {
    GameAnswer answer = GameAnswer.forVote(1L, 2L, 3L, ACCEPTED_AT);

    assertThat(answer.getSelectedOptionKey()).isNull();
    assertThat(answer.getTargetParticipantId()).isEqualTo(3L);
    assertThat(answer.getAcceptedAt()).isEqualTo(ACCEPTED_AT);
  }

  @Test
  @DisplayName("자기 자신에 대한 투표, 잘못된 참조 ID 또는 필수값 누락이 있는 답변은 생성할 수 없다")
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

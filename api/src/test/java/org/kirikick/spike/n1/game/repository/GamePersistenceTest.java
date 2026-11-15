package org.kirikick.spike.n1.game.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.ApiApplication;
import org.kirikick.api.support.H2JpaTest;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.spike.n1.N1JpaTestConfiguration;
import org.kirikick.spike.n1.game.domain.Choice;
import org.kirikick.spike.n1.game.domain.Game;
import org.kirikick.spike.n1.reward.domain.RewardPreset;
import org.kirikick.spike.n1.reward.repository.RewardPresetRepository;
import org.kirikick.spike.n1.room.domain.Room;
import org.kirikick.spike.n1.room.repository.RoomRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

@ContextConfiguration(classes = {ApiApplication.class, N1JpaTestConfiguration.class})
@H2JpaTest
@ActiveProfiles("test")
class GamePersistenceTest {

  @Autowired private UserRepository users;
  @Autowired private RewardPresetRepository rewards;
  @Autowired private RoomRepository rooms;
  @Autowired private GameRepository games;
  @Autowired private ChoiceRepository choices;
  @Autowired private EntityManager entityManager;

  @Test
  @DisplayName("H2 매핑 smoke: 저장 후 clear해도 관계와 게임별 문구·순서를 조회한다")
  void reloadsRelationsAndSnapshotsInStoredOrder() {
    User host = users.save(User.create("host", null));
    RewardPreset reward = rewards.save(RewardPreset.create("보상", "보상 설명"));
    Room room = rooms.save(Room.create(host, reward));
    Game game = games.save(Game.create(room, 7, "공개할 질문"));
    choices.save(Choice.create(game, 1, "두 번째"));
    choices.save(Choice.create(game, 0, "첫 번째"));
    entityManager.flush();
    entityManager.clear();

    Game reloaded = games.findById(game.getId()).orElseThrow();
    assertThat(reloaded.getQuestionTextSnapshot()).isEqualTo("공개할 질문");
    assertThat(reloaded.getOrdinal()).isEqualTo(7);
    assertThat(reloaded.getRoom().getHost().getId()).isEqualTo(host.getId());
    assertThat(reloaded.getRoom().getSelectedRewardPreset().getTitle()).isEqualTo("보상");
    assertThat(reloaded.getRoom().getCreatedAt()).isNotNull();
    assertThat(reloaded.getCreatedAt()).isNotNull();
    assertThat(reloaded.getRoom().getOpenedAt()).isNull();
    assertThat(reloaded.getCorrectChoiceId()).isNull();
    assertThat(choices.findByGame_IdOrderByPositionAscIdAsc(game.getId()))
        .extracting(Choice::getTextSnapshot)
        .containsExactly("첫 번째", "두 번째");
    assertThat(games.findByRoom_IdOrderByOrdinalAscIdAsc(room.getId()))
        .extracting(Game::getId)
        .containsExactly(game.getId());
  }

  @Test
  @DisplayName("저장된 같은 게임 선택지를 정답으로 최초 연결하고 재연결은 거절한다")
  void initializesCorrectChoiceOnlyOnce() {
    Room room = savedRoom();
    Game game = games.save(Game.create(room, 1, "질문"));
    Choice correct = choices.save(Choice.create(game, 0, "정답"));
    game.initializeCorrectChoice(correct);
    entityManager.flush();
    entityManager.clear();

    Game reloaded = games.findById(game.getId()).orElseThrow();
    assertThat(reloaded.getCorrectChoiceId()).isEqualTo(correct.getId());
    assertThatThrownBy(() -> reloaded.initializeCorrectChoice(correct))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("일반 FK와 별개로 다른 게임 선택지와 아직 저장하지 않은 선택지는 정답 연결을 거절한다")
  void rejectsAnotherGamesChoiceAndUnsavedChoices() {
    Room room = savedRoom();
    Game first = games.save(Game.create(room, 1, "첫 질문"));
    Game second = games.save(Game.create(room, 2, "둘째 질문"));
    Choice other = choices.save(Choice.create(second, 0, "다른 게임 선택지"));

    assertThatThrownBy(() -> first.initializeCorrectChoice(other))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> first.initializeCorrectChoice(Choice.create(first, 0, "미저장")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(first.getCorrectChoiceId()).isNull();
  }

  private Room savedRoom() {
    User host = users.save(User.create("host", null));
    RewardPreset reward = rewards.save(RewardPreset.create("보상", "보상 설명"));
    return rooms.save(Room.create(host, reward));
  }
}

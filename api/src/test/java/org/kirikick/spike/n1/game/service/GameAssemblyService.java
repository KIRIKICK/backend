package org.kirikick.spike.n1.game.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.spike.n1.game.domain.Choice;
import org.kirikick.spike.n1.game.domain.Game;
import org.kirikick.spike.n1.game.repository.ChoiceRepository;
import org.kirikick.spike.n1.game.repository.GameRepository;
import org.kirikick.spike.n1.reward.repository.RewardPresetRepository;
import org.kirikick.spike.n1.room.domain.Room;
import org.kirikick.spike.n1.room.repository.RoomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal DB assembly only; N3 must supply authorization, room selection and retry boundaries. */
@Service
@RequiredArgsConstructor
public class GameAssemblyService {

  private final UserRepository users;
  private final RewardPresetRepository rewards;
  private final RoomRepository rooms;
  private final GameRepository games;
  private final ChoiceRepository choices;
  private final Clock clock;

  @Transactional
  public Long createRoomWithFirstGame(
      Long hostUserId,
      Long rewardPresetId,
      int ordinal,
      String question,
      List<String> choiceTexts,
      int correctChoiceIndex) {
    Room room =
        rooms.save(
            Room.create(
                users.findById(hostUserId).orElseThrow(),
                rewards.findById(rewardPresetId).orElseThrow()));
    Game game = assembleGame(room, ordinal, question, choiceTexts, correctChoiceIndex);
    // Check the correct-choice UPDATE before recording the opening time.
    games.flush();
    room.open(clock.instant());
    games.flush();
    return game.getId();
  }

  @Transactional
  public Long addGame(
      Long roomId, int ordinal, String question, List<String> choiceTexts, int correctChoiceIndex) {
    Room room = rooms.findById(roomId).orElseThrow();
    if (room.getOpenedAt() == null) {
      throw new IllegalStateException("완성된 방에만 게임을 추가할 수 있습니다.");
    }
    Game game = assembleGame(room, ordinal, question, choiceTexts, correctChoiceIndex);
    games.flush();
    return game.getId();
  }

  private Game assembleGame(
      Room room, int ordinal, String question, List<String> choiceTexts, int correctChoiceIndex) {
    List<String> texts = List.copyOf(choiceTexts);
    Game game = games.save(Game.create(room, ordinal, question));
    List<Choice> savedChoices = new ArrayList<>();
    for (int position = 0; position < texts.size(); position++) {
      savedChoices.add(choices.save(Choice.create(game, position, texts.get(position))));
    }
    game.initializeCorrectChoice(savedChoices.get(correctChoiceIndex));
    return game;
  }
}

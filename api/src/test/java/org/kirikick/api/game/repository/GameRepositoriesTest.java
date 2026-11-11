package org.kirikick.api.game.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.kirikick.api.game.domain.Game;
import org.kirikick.api.game.domain.GameAnswer;
import org.kirikick.api.game.domain.GameParticipant;
import org.kirikick.api.game.domain.GameStatus;
import org.kirikick.api.game.domain.GameType;
import org.kirikick.api.room.domain.Room;
import org.kirikick.api.room.repository.RoomRepository;
import org.kirikick.api.support.H2JpaTest;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;

// Finder/auditing tests only. Flyway and FK/CHECK/UNIQUE behavior are verified on PostgreSQL
// separately.
@H2JpaTest
class GameRepositoriesTest {

  private static final Instant STARTED_AT = Instant.parse("2030-01-02T00:00:00Z");

  @Autowired private UserRepository users;
  @Autowired private RoomRepository rooms;
  @Autowired private GameRepository games;
  @Autowired private GameParticipantRepository participants;
  @Autowired private GameAnswerRepository answers;
  @Autowired private EntityManager entityManager;

  @Test
  void reloadsRoomGameParticipationAndOptionAnswerByPublicAndInternalKeys() {
    User user = users.save(User.create("출제자", null));
    User member = users.save(User.create("친구", null));
    Room room = rooms.save(Room.create(user.getId(), "친구방"));
    Game game =
        games.save(
            Game.create(room.getId(), user.getId(), GameType.NAZAL, STARTED_AT.plusSeconds(7200)));
    GameParticipant participant =
        participants.save(GameParticipant.create(game.getId(), member.getId()));
    participant.start(STARTED_AT);
    GameAnswer answer =
        answers.save(
            GameAnswer.forOption(
                game.getId(), participant.getId(), "2", STARTED_AT.plusSeconds(10)));
    entityManager.flush();
    entityManager.clear();

    Room savedRoom = rooms.findByUuid(room.getUuid()).orElseThrow();
    assertThat(savedRoom.getOwnerId()).isEqualTo(user.getId());
    assertThat(savedRoom.getName()).isEqualTo("친구방");
    assertThat(savedRoom.getCreatedAt()).isNotNull();
    assertThat(savedRoom.getUpdatedAt()).isNotNull();
    Game savedGame = games.findByUuid(game.getUuid()).orElseThrow();
    assertThat(savedGame.getRoomId()).isEqualTo(room.getId());
    assertThat(savedGame.getCreatorId()).isEqualTo(user.getId());
    assertThat(savedGame.getType()).isEqualTo(GameType.NAZAL);
    assertThat(savedGame.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
    assertThat(savedGame.getExpiresAt()).isEqualTo(STARTED_AT.plusSeconds(7200));
    GameParticipant savedParticipant =
        participants.findByGameIdAndUserId(game.getId(), member.getId()).orElseThrow();
    assertThat(savedParticipant.getUuid()).isEqualTo(participant.getUuid());
    assertThat(savedParticipant.getStartedAt()).isEqualTo(STARTED_AT);
    savedParticipant.start(STARTED_AT.plusSeconds(30));
    entityManager.flush();
    entityManager.clear();
    assertThat(participants.findByUuid(participant.getUuid()).orElseThrow().getStartedAt())
        .isEqualTo(STARTED_AT);
    GameAnswer savedAnswer = answers.findByParticipantId(participant.getId()).orElseThrow();
    assertThat(savedAnswer.getUuid()).isEqualTo(answer.getUuid());
    assertThat(savedAnswer.getGameId()).isEqualTo(game.getId());
    assertThat(savedAnswer.getSelectedOptionKey()).isEqualTo("2");
    assertThat(savedAnswer.getTargetParticipantId()).isNull();
    assertThat(savedAnswer.getAcceptedAt()).isEqualTo(STARTED_AT.plusSeconds(10));
    assertThat(savedAnswer.getCreatedAt()).isNotNull();
    assertThat(answers.findByUuid(answer.getUuid()).orElseThrow().getId())
        .isEqualTo(answer.getId());
  }

  @Test
  void scopesRostersAndVotesToOneGameIncludingTheSameUserInDifferentGames() {
    User first = users.save(User.create("첫째", null));
    User second = users.save(User.create("둘째", null));
    Room room = rooms.save(Room.create(first.getId(), "친구방"));
    Game game =
        games.save(
            Game.create(room.getId(), first.getId(), GameType.VOTE, STARTED_AT.plusSeconds(7200)));
    Game other =
        games.save(
            Game.create(room.getId(), first.getId(), GameType.VOTE, STARTED_AT.plusSeconds(7200)));
    GameParticipant voter = participants.save(GameParticipant.create(game.getId(), first.getId()));
    GameParticipant target =
        participants.save(GameParticipant.create(game.getId(), second.getId()));
    participants.save(GameParticipant.create(other.getId(), first.getId()));
    GameAnswer vote =
        answers.save(
            GameAnswer.forVote(
                game.getId(), voter.getId(), target.getId(), STARTED_AT.plusSeconds(10)));
    entityManager.flush();
    entityManager.clear();

    assertThat(participants.findAllByGameIdOrderByIdAsc(game.getId()))
        .extracting(GameParticipant::getId)
        .containsExactly(voter.getId(), target.getId());
    assertThat(answers.findAllByGameIdOrderByIdAsc(game.getId()))
        .extracting(GameAnswer::getId)
        .containsExactly(vote.getId());
    assertThat(answers.findAllByGameIdOrderByIdAsc(other.getId())).isEmpty();
    GameAnswer reloaded = answers.findByUuid(vote.getUuid()).orElseThrow();
    assertThat(reloaded.getTargetParticipantId()).isEqualTo(target.getId());
    assertThat(reloaded.getSelectedOptionKey()).isNull();
  }

  @Test
  void unknownIdentitiesAndUnansweredParticipantsReturnEmpty() {
    UUID missing = UUID.randomUUID();
    assertThat(rooms.findByUuid(missing)).isEmpty();
    assertThat(games.findByUuid(missing)).isEmpty();
    assertThat(participants.findByUuid(missing)).isEmpty();
    assertThat(participants.findByGameIdAndUserId(Long.MAX_VALUE, Long.MAX_VALUE)).isEmpty();
    assertThat(answers.findByUuid(missing)).isEmpty();
    assertThat(answers.findByParticipantId(Long.MAX_VALUE)).isEmpty();
  }
}

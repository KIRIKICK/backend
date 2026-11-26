package org.kirikick.api.publication.service;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.common.infra.DbTimeSource;
import org.kirikick.api.config.PublicationProperties;
import org.kirikick.api.game.domain.Choice;
import org.kirikick.api.game.domain.Game;
import org.kirikick.api.game.repository.ChoiceRepository;
import org.kirikick.api.game.repository.GameRepository;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.domain.PublicationKind;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.kirikick.api.publication.infra.PublicationCommitObserver;
import org.kirikick.api.publication.repository.PublicationIdempotencyRepository;
import org.kirikick.api.publication.repository.model.PublicationClaim;
import org.kirikick.api.publication.repository.model.PublicationReplay;
import org.kirikick.api.publication.repository.model.PublicationReservation;
import org.kirikick.api.question.domain.QuestionPreset;
import org.kirikick.api.question.domain.QuestionPresetChoice;
import org.kirikick.api.question.repository.QuestionPresetChoiceRepository;
import org.kirikick.api.question.repository.QuestionPresetRepository;
import org.kirikick.api.reward.domain.RewardPreset;
import org.kirikick.api.reward.repository.RewardPresetRepository;
import org.kirikick.api.room.domain.Room;
import org.kirikick.api.room.domain.RoomStatus;
import org.kirikick.api.room.repository.RoomRepository;
import org.kirikick.api.room.repository.projection.RoomState;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.domain.UserStatus;
import org.kirikick.api.user.domain.exception.UserErrorCode;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Transaction owner for first publication; registration stays outside this class. */
@RequiredArgsConstructor
public class OpenRoomService {
  private final PlatformTransactionManager transactionManager;
  private final PublicationIdempotencyRepository records;
  private final UserRepository users;
  private final RoomRepository rooms;
  private final GameRepository games;
  private final ChoiceRepository gameChoices;
  private final QuestionPresetRepository questions;
  private final QuestionPresetChoiceRepository questionChoices;
  private final RewardPresetRepository rewards;
  private final EntityManager entityManager;
  private final DbTimeSource clock;
  private final PublicationProperties properties;
  private final PublicationCommitObserver commitObserver;

  public PublicationResult publish(UUID userUuid, String requestKey, PublicationInput input) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return commitObserver.observe(
        () -> transaction.execute(status -> publishInTransaction(userUuid, requestKey, input)));
  }

  private PublicationResult publishInTransaction(
      UUID userUuid, String requestKey, PublicationInput input) {
    if (input.kind() != PublicationKind.NK_CREATE) {
      throw new DomainException(PublicationErrorCode.INVALID_PUBLICATION_INPUT);
    }
    long userId =
        users
            .findByUuid(userUuid)
            .map(User::getId)
            .orElseThrow(() -> new DomainException(UserErrorCode.ACCOUNT_NOT_FOUND));
    PublicationReservation reservation = records.reserveNkCreate(userId, requestKey, input);
    if (reservation instanceof PublicationReplay replay) {
      return PublicationResult.replayed(
          replay.roomId(), replay.gameId(), replay.openedAt(), replay.closesAt());
    }
    PublicationClaim claim = (PublicationClaim) reservation;
    String userStatus =
        users
            .lockAndFindStatusById(userId)
            .orElseThrow(() -> new DomainException(UserErrorCode.ACCOUNT_NOT_FOUND));
    if (!UserStatus.ACTIVE.name().equals(userStatus)) {
      throw new DomainException(AuthErrorCode.ACCOUNT_INACTIVE);
    }
    rooms.findStoredOpenRoomIdByHostUserId(userId).ifPresent(this::reconcileOpenRoom);
    QuestionPreset question =
        questions
            .findById(input.questionPresetId())
            .orElseThrow(() -> new DomainException(PublicationErrorCode.PRESET_NOT_FOUND));
    RewardPreset reward =
        rewards
            .findById(input.rewardPresetId())
            .orElseThrow(() -> new DomainException(PublicationErrorCode.PRESET_NOT_FOUND));
    List<QuestionPresetChoice> sources =
        questionChoices.findAllByQuestionPreset_IdAndIdInOrderByIdAsc(
            question.getId(), input.choiceIds());
    if (!sources.stream().map(QuestionPresetChoice::getId).toList().equals(input.choiceIds())) {
      if (questionChoices.countByIdIn(input.choiceIds()) != 4) {
        throw new DomainException(PublicationErrorCode.PRESET_NOT_FOUND);
      }
      throw new DomainException(PublicationErrorCode.INVALID_PUBLICATION_INPUT);
    }
    Room room = rooms.saveAndFlush(Room.prepare(users.getReferenceById(userId), reward));
    Game game = games.saveAndFlush(Game.prepare(room, question));
    List<Choice> choices =
        gameChoices.saveAllAndFlush(
            sources.stream()
                .map(source -> Choice.copyOf(game, source, sources.indexOf(source)))
                .toList());
    Choice correct = choices.get(input.choiceIds().indexOf(input.correctChoiceId()));
    game.initializeCorrectChoice(correct);
    game.verifyComplete(choices);
    Instant openedAt = clock.readCurrentTime();
    Instant closesAt = openedAt.plus(Duration.ofHours(24));
    room.publish(openedAt, closesAt);
    entityManager.flush();
    records.completeClaim(claim, game.getId(), properties.recordExpiresAt(openedAt));
    entityManager.flush();
    return PublicationResult.created(room.getId(), game.getId(), openedAt, closesAt);
  }

  private void reconcileOpenRoom(long roomId) {
    RoomState state =
        rooms
            .lockAndFindCurrentStateById(roomId)
            .orElseThrow(
                () -> new DomainException(PublicationErrorCode.PUBLICATION_CLAIM_STATE_INVALID));
    if (state.getStatus() != RoomStatus.OPEN) {
      return;
    }
    if (state.getOpenedAt() == null || state.getClosesAt() == null) {
      throw new DomainException(PublicationErrorCode.PUBLICATION_CLAIM_STATE_INVALID);
    }
    if (clock.readCurrentTime().isBefore(state.getClosesAt())) {
      throw new DomainException(PublicationErrorCode.OPEN_ROOM_EXISTS);
    }
    entityManager.clear();
    Room room = entityManager.find(Room.class, roomId);
    if (room == null) {
      throw new DomainException(PublicationErrorCode.PUBLICATION_CLAIM_STATE_INVALID);
    }
    room.closeIntake();
    entityManager.flush();
  }
}

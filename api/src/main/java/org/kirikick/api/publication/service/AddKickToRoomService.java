package org.kirikick.api.publication.service;

import jakarta.persistence.EntityManager;
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
import org.kirikick.api.publication.domain.PublicationRequestKey;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.kirikick.api.publication.infra.PublicationCommitObserver;
import org.kirikick.api.publication.infra.exception.PublicationCommitOutcomeUnknownException;
import org.kirikick.api.publication.repository.PublicationIdempotencyRepository;
import org.kirikick.api.publication.repository.model.ExistingReservation;
import org.kirikick.api.publication.repository.model.LockedReservation;
import org.kirikick.api.publication.repository.model.PublicationClaim;
import org.kirikick.api.publication.repository.model.PublicationReplay;
import org.kirikick.api.publication.repository.model.PublicationReservation;
import org.kirikick.api.publication.service.exception.AddKickOutcomeUnknownException;
import org.kirikick.api.publication.service.model.PublicationWindow;
import org.kirikick.api.question.domain.QuestionPreset;
import org.kirikick.api.question.domain.QuestionPresetChoice;
import org.kirikick.api.question.repository.QuestionPresetChoiceRepository;
import org.kirikick.api.question.repository.QuestionPresetRepository;
import org.kirikick.api.room.domain.Room;
import org.kirikick.api.room.domain.RoomResultState;
import org.kirikick.api.room.domain.RoomStatus;
import org.kirikick.api.room.repository.RoomRepository;
import org.kirikick.api.room.repository.projection.PublicationState;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.domain.UserStatus;
import org.kirikick.api.user.domain.exception.UserErrorCode;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Transaction owner for adding one kick to an already published room. */
@RequiredArgsConstructor
public class AddKickToRoomService {
  private final PlatformTransactionManager transactionManager;
  private final PublicationIdempotencyRepository records;
  private final UserRepository users;
  private final RoomRepository rooms;
  private final GameRepository games;
  private final ChoiceRepository gameChoices;
  private final QuestionPresetRepository questions;
  private final QuestionPresetChoiceRepository questionChoices;
  private final EntityManager entityManager;
  private final DbTimeSource clock;
  private final PublicationProperties properties;
  private final PublicationCommitObserver commitObserver;

  public PublicationResult add(
      UUID userUuid, PublicationRequestKey requestKey, PublicationInput input) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    try {
      return commitObserver.observe(
          () -> transaction.execute(status -> addInTransaction(userUuid, requestKey, input)));
    } catch (PublicationCommitOutcomeUnknownException exception) {
      throw new AddKickOutcomeUnknownException(exception);
    }
  }

  private PublicationResult addInTransaction(
      UUID userUuid, PublicationRequestKey requestKey, PublicationInput input) {
    if (input.kind() != PublicationKind.NK_KICK_ADD) {
      throw new DomainException(PublicationErrorCode.INVALID_PUBLICATION_INPUT);
    }
    long userId =
        users
            .findByUuid(userUuid)
            .map(User::getId)
            .orElseThrow(() -> new DomainException(UserErrorCode.ACCOUNT_NOT_FOUND));
    LockedReservation reservation = records.reserveNkKickAdd(userId, requestKey, input);
    String userStatus =
        users
            .lockAndFindStatusById(userId)
            .orElseThrow(() -> new DomainException(UserErrorCode.ACCOUNT_NOT_FOUND));
    if (!UserStatus.ACTIVE.name().equals(userStatus)) {
      throw new DomainException(AuthErrorCode.ACCOUNT_INACTIVE);
    }
    PublicationState roomState =
        rooms
            .lockAndFindPublicationStateById(input.roomId())
            .orElseThrow(() -> new DomainException(PublicationErrorCode.ROOM_NOT_FOUND));
    if (!roomState.getHostUserId().equals(userId)) {
      throw new DomainException(PublicationErrorCode.PUBLICATION_FORBIDDEN);
    }

    PublicationWindow window;
    try {
      window = PublicationWindow.from(roomState);
    } catch (DomainException exception) {
      if (exception.getErrorCode() == PublicationErrorCode.ROOM_NOT_FOUND
          && reservation instanceof ExistingReservation existing
          && records.referencesGameInRoom(existing, input.roomId())) {
        throw new DomainException(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD);
      }
      throw exception;
    }
    Instant now = clock.readCurrentTime();
    PublicationReservation resolved =
        reservation instanceof ExistingReservation existing
            ? records.resolveNkKickAdd(existing, input, now)
            : reservation;
    if (resolved instanceof PublicationReplay replay) {
      return PublicationResult.replayed(
          replay.roomId(), replay.gameId(), replay.openedAt(), replay.closesAt());
    }
    PublicationClaim claim = (PublicationClaim) resolved;

    if (!RoomStatus.OPEN.name().equals(roomState.getStatus())
        || RoomResultState.FINAL.name().equals(roomState.getResultState())
        || !now.isBefore(window.closesAt())) {
      throw new DomainException(PublicationErrorCode.ROOM_INTAKE_CLOSED);
    }
    if (games.existsByRoom_IdAndQuestionPreset_Id(input.roomId(), input.questionPresetId())) {
      throw new DomainException(PublicationErrorCode.QUESTION_ALREADY_PUBLISHED);
    }

    QuestionPreset question =
        questions
            .findById(input.questionPresetId())
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

    Room room = rooms.getReferenceById(input.roomId());
    Game game = games.saveAndFlush(Game.prepare(room, question));
    List<Choice> choices =
        gameChoices.saveAllAndFlush(
            sources.stream()
                .map(source -> Choice.copyOf(game, source, sources.indexOf(source)))
                .toList());
    Choice correct = choices.get(input.choiceIds().indexOf(input.correctChoiceId()));
    game.initializeCorrectChoice(correct);
    game.verifyComplete(choices);
    entityManager.flush();
    records.completeClaim(claim, game.getId(), properties.recordExpiresAt(window.openedAt()));
    entityManager.flush();
    return PublicationResult.created(
        input.roomId(), game.getId(), window.openedAt(), window.closesAt());
  }
}

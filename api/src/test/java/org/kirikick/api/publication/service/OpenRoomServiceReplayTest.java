package org.kirikick.api.publication.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.common.infra.DbTimeSource;
import org.kirikick.api.config.PublicationProperties;
import org.kirikick.api.game.repository.ChoiceRepository;
import org.kirikick.api.game.repository.GameRepository;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.domain.PublicationKind;
import org.kirikick.api.publication.infra.PublicationCommitObserver;
import org.kirikick.api.publication.repository.PublicationIdempotencyRepository;
import org.kirikick.api.publication.repository.PublicationIdempotencyRepository.Replay;
import org.kirikick.api.question.repository.QuestionPresetChoiceRepository;
import org.kirikick.api.question.repository.QuestionPresetRepository;
import org.kirikick.api.reward.repository.RewardPresetRepository;
import org.kirikick.api.room.repository.RoomRepository;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class OpenRoomServiceReplayTest {
  @Test
  @DisplayName("U-04: 성공 복구는 User·Room 업무 락과 현재 프리셋 조회 및 업무 쓰기를 실행하지 않는다")
  void replaySkipsNewPublicationEligibilityAndAssembly() {
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    given(transactions.getTransaction(any())).willReturn(mock(TransactionStatus.class));
    PublicationIdempotencyRepository records = mock(PublicationIdempotencyRepository.class);
    UserRepository users = mock(UserRepository.class);
    RoomRepository rooms = mock(RoomRepository.class);
    GameRepository games = mock(GameRepository.class);
    ChoiceRepository gameChoices = mock(ChoiceRepository.class);
    QuestionPresetRepository questions = mock(QuestionPresetRepository.class);
    QuestionPresetChoiceRepository choices = mock(QuestionPresetChoiceRepository.class);
    RewardPresetRepository rewards = mock(RewardPresetRepository.class);
    EntityManager entities = mock(EntityManager.class);
    DbTimeSource clock = mock(DbTimeSource.class);
    UUID subject = UUID.randomUUID();
    User user = mock(User.class);
    given(user.getId()).willReturn(42L);
    given(users.findByUuid(subject)).willReturn(Optional.of(user));
    String key = UUID.randomUUID().toString();
    PublicationInput input =
        new PublicationInput(PublicationKind.NK_CREATE, null, 1L, 2L, List.of(3L, 4L, 5L, 6L), 3L);
    Instant openedAt = Instant.parse("2040-01-01T00:00:00Z");
    Instant closesAt = openedAt.plus(Duration.ofHours(24));
    given(records.reserveNkCreate(42L, key, input))
        .willReturn(new Replay(10L, 20L, openedAt, closesAt));
    OpenRoomService service =
        new OpenRoomService(
            transactions,
            records,
            users,
            rooms,
            games,
            gameChoices,
            questions,
            choices,
            rewards,
            entities,
            clock,
            new PublicationProperties(Duration.ofMinutes(5)),
            new PublicationCommitObserver());

    assertThat(service.publish(subject, key, input))
        .isEqualTo(new PublicationResult(false, 10L, 20L, openedAt, closesAt));
    then(users).should(never()).lockById(anyLong());
    then(rooms).shouldHaveNoInteractions();
    then(games).shouldHaveNoInteractions();
    then(gameChoices).shouldHaveNoInteractions();
    then(questions).shouldHaveNoInteractions();
    then(choices).shouldHaveNoInteractions();
    then(rewards).shouldHaveNoInteractions();
    then(entities).shouldHaveNoInteractions();
    then(clock).shouldHaveNoInteractions();
  }
}

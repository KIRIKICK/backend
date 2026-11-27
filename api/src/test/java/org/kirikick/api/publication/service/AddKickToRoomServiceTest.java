package org.kirikick.api.publication.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.common.infra.DbTimeSource;
import org.kirikick.api.config.PublicationProperties;
import org.kirikick.api.game.repository.ChoiceRepository;
import org.kirikick.api.game.repository.GameRepository;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.domain.PublicationKind;
import org.kirikick.api.publication.domain.PublicationRequestKey;
import org.kirikick.api.publication.infra.PublicationCommitObserver;
import org.kirikick.api.publication.repository.PublicationIdempotencyRepository;
import org.kirikick.api.publication.repository.model.ExistingReservation;
import org.kirikick.api.publication.repository.model.PublicationReplay;
import org.kirikick.api.question.repository.QuestionPresetChoiceRepository;
import org.kirikick.api.question.repository.QuestionPresetRepository;
import org.kirikick.api.room.repository.RoomRepository;
import org.kirikick.api.room.repository.projection.PublicationState;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.domain.UserStatus;
import org.kirikick.api.user.repository.UserRepository;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AddKickToRoomServiceTest {
  private static final UUID USER_UUID = UUID.fromString("00000000-0000-4000-8000-000000000076");
  private static final long USER_ID = 76L;
  private static final long ROOM_ID = 701L;
  private static final Instant OPENED_AT = Instant.parse("2030-01-01T00:00:00Z");
  private static final Instant CLOSES_AT = OPENED_AT.plus(Duration.ofHours(24));

  @Test
  @DisplayName("U-04: 기존 성공 복구는 raw 선점·최신 인가 뒤에만 해석하고 신규 조립을 건너뛴다")
  void replaysOnlyAfterRawReservationAndCurrentAuthorization() {
    Dependencies dependencies = new Dependencies();
    ExistingReservation existing = mock(ExistingReservation.class);
    PublicationReplay replay = new PublicationReplay(ROOM_ID, 900L, OPENED_AT, CLOSES_AT);
    PublicationInput input = input();
    User user = mock(User.class);
    PublicationState room = mock(PublicationState.class);
    given(dependencies.records.reserveNkKickAdd(USER_ID, new PublicationRequestKey("key"), input))
        .willReturn(existing);
    given(dependencies.users.findByUuid(USER_UUID)).willReturn(Optional.of(user));
    given(user.getId()).willReturn(USER_ID);
    given(dependencies.users.lockAndFindStatusById(USER_ID))
        .willReturn(Optional.of(UserStatus.ACTIVE.name()));
    given(dependencies.rooms.lockAndFindPublicationStateById(ROOM_ID))
        .willReturn(Optional.of(room));
    given(room.getHostUserId()).willReturn(USER_ID);
    given(room.getOpenedAtPresent()).willReturn(true);
    given(room.getClosesAtPresent()).willReturn(true);
    given(room.getOpenedAtFinite()).willReturn(true);
    given(room.getClosesAtFinite()).willReturn(true);
    given(room.getRawOpenedAt()).willReturn("2030-01-01 00:00:00+00");
    given(room.getRawClosesAt()).willReturn("2030-01-02 00:00:00+00");
    given(dependencies.clock.readCurrentTime()).willReturn(OPENED_AT.plusSeconds(1));
    given(dependencies.records.resolveNkKickAdd(existing, input, OPENED_AT.plusSeconds(1)))
        .willReturn(replay);

    PublicationResult result =
        dependencies.service().add(USER_UUID, new PublicationRequestKey("key"), input);

    assertThat(result).isEqualTo(new PublicationResult(false, ROOM_ID, 900L, OPENED_AT, CLOSES_AT));
    InOrder order =
        inOrder(dependencies.records, dependencies.users, dependencies.rooms, dependencies.clock);
    order
        .verify(dependencies.records)
        .reserveNkKickAdd(USER_ID, new PublicationRequestKey("key"), input);
    order.verify(dependencies.users).lockAndFindStatusById(USER_ID);
    order.verify(dependencies.rooms).lockAndFindPublicationStateById(ROOM_ID);
    order.verify(dependencies.clock).readCurrentTime();
    order.verify(dependencies.records).resolveNkKickAdd(existing, input, OPENED_AT.plusSeconds(1));
    then(dependencies.games).shouldHaveNoInteractions();
    then(dependencies.questionPresets).shouldHaveNoInteractions();
    then(dependencies.questionChoices).shouldHaveNoInteractions();
    then(dependencies.choices).shouldHaveNoInteractions();
    then(dependencies.records)
        .should(times(1))
        .resolveNkKickAdd(existing, input, OPENED_AT.plusSeconds(1));
    then(dependencies.records).should(never()).completeClaim(any(), anyLong(), any());
    then(dependencies.observer).should(times(1)).observe(any());
    assertThat(dependencies.observing).isFalse();
  }

  private static PublicationInput input() {
    return new PublicationInput(
        PublicationKind.NK_KICK_ADD, ROOM_ID, null, 401L, List.of(1L, 2L, 3L, 4L), 2L);
  }

  private static final class Dependencies {
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final PublicationIdempotencyRepository records =
        mock(PublicationIdempotencyRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final GameRepository games = mock(GameRepository.class);
    private final ChoiceRepository choices = mock(ChoiceRepository.class);
    private final QuestionPresetRepository questionPresets = mock(QuestionPresetRepository.class);
    private final QuestionPresetChoiceRepository questionChoices =
        mock(QuestionPresetChoiceRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final DbTimeSource clock = mock(DbTimeSource.class);
    private final PublicationCommitObserver observer = mock(PublicationCommitObserver.class);
    private final AtomicBoolean observing = new AtomicBoolean();

    private Dependencies() {
      TransactionStatus status = new SimpleTransactionStatus();
      given(transactions.getTransaction(any()))
          .willAnswer(invocation -> {
            assertThat(observing).isTrue();
            return status;
          });
      willAnswer(
              invocation -> {
                assertThat(observing).isTrue();
                return null;
              })
          .given(transactions)
          .commit(any());
      given(observer.observe(any()))
          .willAnswer(invocation -> {
            observing.set(true);
            try {
              return ((Supplier<?>) invocation.getArgument(0)).get();
            } finally {
              observing.set(false);
            }
          });
    }

    private AddKickToRoomService service() {
      return new AddKickToRoomService(
          transactions,
          records,
          users,
          rooms,
          games,
          choices,
          questionPresets,
          questionChoices,
          entityManager,
          clock,
          new PublicationProperties(Duration.ZERO),
          observer);
    }
  }
}

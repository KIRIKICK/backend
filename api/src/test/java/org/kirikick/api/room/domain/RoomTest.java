package org.kirikick.api.room.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.reward.domain.RewardPreset;
import org.kirikick.api.room.domain.exception.RoomErrorCode;
import org.kirikick.api.user.domain.User;

class RoomTest {

  private static final Instant OPENED_AT = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  @DisplayName("이미 공개된 방은 도메인 오류 코드로 거절한다")
  void rejectsAlreadyPublishedRoom() {
    Room room = room();
    room.publish(OPENED_AT, OPENED_AT.plusSeconds(86_400));

    assertCode(
        () -> room.publish(OPENED_AT, OPENED_AT.plusSeconds(86_400)),
        RoomErrorCode.ROOM_ALREADY_PUBLISHED);
  }

  @Test
  @DisplayName("24시간이 아닌 마감 시각은 도메인 오류 코드로 거절한다")
  void rejectsInvalidClosingTime() {
    assertCode(
        () -> room().publish(OPENED_AT, OPENED_AT.plusSeconds(86_399)),
        RoomErrorCode.INVALID_CLOSING_TIME);
  }

  @Test
  @DisplayName("공개 시각이 불완전한 방의 접수 종료는 도메인 오류 코드로 거절한다")
  void rejectsClosingIncompleteRoom() {
    assertCode(room()::closeIntake, RoomErrorCode.INCOMPLETE_PUBLICATION_TIME);
  }

  private static Room room() {
    return Room.prepare(mock(User.class), mock(RewardPreset.class));
  }

  private static void assertCode(Runnable action, RoomErrorCode errorCode) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
  }
}

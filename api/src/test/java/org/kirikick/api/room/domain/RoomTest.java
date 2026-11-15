package org.kirikick.api.room.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.common.exception.DomainException;

class RoomTest {

  @Test
  @DisplayName("유효한 방장 ID와 이름으로 방을 생성한다")
  void createsRoomWithSeparatePublicIdentityAndOwner() {
    Room room = Room.create(1L, "친구방");

    assertThat(room.getId()).isNull();
    assertThat(room.getUuid()).isNotNull();
    assertThat(room.getOwnerId()).isEqualTo(1L);
    assertThat(room.getName()).isEqualTo("친구방");
  }

  @ParameterizedTest
  @DisplayName("방 이름이 null이거나 비어 있거나 공백이면 생성할 수 없다")
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t\n"})
  void rejectsMissingName(String name) {
    assertThatThrownBy(() -> Room.create(1L, name)).isInstanceOf(DomainException.class);
  }

  @Test
  @DisplayName("방장 ID가 null이거나 0 이하이면 방을 생성할 수 없다")
  void rejectsMissingOwner() {
    for (Long ownerId : new Long[] {null, 0L, -1L}) {
      assertThatThrownBy(() -> Room.create(ownerId, "친구방")).isInstanceOf(DomainException.class);
    }
  }
}

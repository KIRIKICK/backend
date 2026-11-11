package org.kirikick.api.room.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.common.exception.DomainException;

class RoomTest {

  @Test
  void createsRoomWithSeparatePublicIdentityAndOwner() {
    Room room = Room.create(1L, "친구방");

    assertThat(room.getId()).isNull();
    assertThat(room.getUuid()).isNotNull();
    assertThat(room.getOwnerId()).isEqualTo(1L);
    assertThat(room.getName()).isEqualTo("친구방");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t\n"})
  void rejectsMissingName(String name) {
    assertThatThrownBy(() -> Room.create(1L, name)).isInstanceOf(DomainException.class);
  }

  @Test
  void rejectsMissingOwner() {
    for (Long ownerId : new Long[] {null, 0L, -1L}) {
      assertThatThrownBy(() -> Room.create(ownerId, "친구방")).isInstanceOf(DomainException.class);
    }
  }
}

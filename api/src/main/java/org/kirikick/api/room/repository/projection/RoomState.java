package org.kirikick.api.room.repository.projection;

import java.time.Instant;
import org.kirikick.api.room.domain.RoomResultState;
import org.kirikick.api.room.domain.RoomStatus;

public interface RoomState {

  RoomStatus getStatus();

  RoomResultState getResultState();

  Instant getOpenedAt();

  Instant getClosesAt();
}

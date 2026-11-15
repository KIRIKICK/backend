package org.kirikick.api.room.repository;

import java.util.Optional;
import java.util.UUID;
import org.kirikick.api.room.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long> {

  Optional<Room> findByUuid(UUID uuid);
}

package org.kirikick.spike.n1.room.repository;

import org.kirikick.spike.n1.room.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long> {}

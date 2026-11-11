package org.kirikick.api.game.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.kirikick.api.game.domain.GameParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameParticipantRepository extends JpaRepository<GameParticipant, Long> {

  Optional<GameParticipant> findByUuid(UUID uuid);

  Optional<GameParticipant> findByGameIdAndUserId(Long gameId, Long userId);

  List<GameParticipant> findAllByGameIdOrderByIdAsc(Long gameId);
}

package org.kirikick.api.game.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.kirikick.api.game.domain.GameAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameAnswerRepository extends JpaRepository<GameAnswer, Long> {

  Optional<GameAnswer> findByUuid(UUID uuid);

  Optional<GameAnswer> findByParticipantId(Long participantId);

  List<GameAnswer> findAllByGameIdOrderByIdAsc(Long gameId);
}

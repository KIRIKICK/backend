package org.kirikick.api.game.repository;

import java.util.Optional;
import java.util.UUID;
import org.kirikick.api.game.domain.Game;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameRepository extends JpaRepository<Game, Long> {

  Optional<Game> findByUuid(UUID uuid);
}

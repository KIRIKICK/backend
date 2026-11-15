package org.kirikick.spike.n1.game.repository;

import java.util.List;
import org.kirikick.spike.n1.game.domain.Game;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameRepository extends JpaRepository<Game, Long> {

  List<Game> findByRoom_IdOrderByOrdinalAscIdAsc(Long roomId);
}

package org.kirikick.api.game.repository;

import java.util.List;
import org.kirikick.api.game.domain.Game;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameRepository extends JpaRepository<Game, Long> {

  List<Game> findAllByRoom_IdOrderByCreatedAtAscIdAsc(Long roomId);

  boolean existsByRoom_IdAndQuestionPreset_Id(Long roomId, Long questionPresetId);
}

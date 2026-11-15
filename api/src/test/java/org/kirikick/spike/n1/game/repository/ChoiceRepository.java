package org.kirikick.spike.n1.game.repository;

import java.util.List;
import org.kirikick.spike.n1.game.domain.Choice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChoiceRepository extends JpaRepository<Choice, Long> {

  List<Choice> findByGame_IdOrderByPositionAscIdAsc(Long gameId);
}

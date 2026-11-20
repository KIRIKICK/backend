package org.kirikick.api.game.repository;

import java.util.List;
import org.kirikick.api.game.domain.Choice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChoiceRepository extends JpaRepository<Choice, Long> {

  List<Choice> findAllByGame_IdOrderByPositionAsc(Long gameId);
}

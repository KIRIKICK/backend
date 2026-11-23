package org.kirikick.api.question.repository;

import java.util.Collection;
import java.util.List;
import org.kirikick.api.question.domain.QuestionPresetChoice;
import org.springframework.data.repository.Repository;

public interface QuestionPresetChoiceRepository extends Repository<QuestionPresetChoice, Long> {

  List<QuestionPresetChoice> findAllByQuestionPreset_IdAndIdInOrderByIdAsc(
      Long questionPresetId, Collection<Long> ids);

  long countByIdIn(Collection<Long> ids);
}

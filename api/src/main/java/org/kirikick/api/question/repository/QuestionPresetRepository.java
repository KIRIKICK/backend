package org.kirikick.api.question.repository;

import java.util.Optional;
import org.kirikick.api.question.domain.QuestionPreset;
import org.springframework.data.repository.Repository;

public interface QuestionPresetRepository extends Repository<QuestionPreset, Long> {

  Optional<QuestionPreset> findById(Long id);
}

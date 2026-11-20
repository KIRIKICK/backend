package org.kirikick.api.reward.repository;

import java.util.Optional;
import org.kirikick.api.reward.domain.RewardPreset;
import org.springframework.data.repository.Repository;

public interface RewardPresetRepository extends Repository<RewardPreset, Long> {

  Optional<RewardPreset> findById(Long id);
}

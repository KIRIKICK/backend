package org.kirikick.spike.n1;

import org.kirikick.api.auth.domain.SignupApplication;
import org.kirikick.api.auth.repository.SignupApplicationRepository;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.spike.n1.game.domain.Game;
import org.kirikick.spike.n1.game.repository.GameRepository;
import org.kirikick.spike.n1.reward.domain.RewardPreset;
import org.kirikick.spike.n1.reward.repository.RewardPresetRepository;
import org.kirikick.spike.n1.room.domain.Room;
import org.kirikick.spike.n1.room.repository.RoomRepository;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Explicitly opts spike-only mappings into the test contexts that exercise N1. */
@TestConfiguration(proxyBeanMethods = false)
@EntityScan(
    basePackageClasses = {
      User.class,
      SignupApplication.class,
      Room.class,
      Game.class,
      RewardPreset.class
    })
@EnableJpaRepositories(
    basePackageClasses = {
      UserRepository.class,
      SignupApplicationRepository.class,
      RoomRepository.class,
      GameRepository.class,
      RewardPresetRepository.class
    })
public class N1JpaTestConfiguration {}

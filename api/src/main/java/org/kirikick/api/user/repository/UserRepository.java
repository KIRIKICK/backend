package org.kirikick.api.user.repository;

import java.util.Optional;
import java.util.UUID;
import org.kirikick.api.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

  Optional<User> findByUuid(UUID uuid);
}

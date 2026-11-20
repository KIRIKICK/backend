package org.kirikick.api.user.repository;

import java.util.Optional;
import java.util.UUID;
import org.kirikick.api.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<User, Long> {

  Optional<User> findByUuid(UUID uuid);

  @Transactional(propagation = Propagation.MANDATORY)
  @Query(value = "select id from users where id = :id for no key update", nativeQuery = true)
  Optional<Long> lockById(@Param("id") Long id);
}

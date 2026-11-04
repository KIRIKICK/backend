package org.kirikick.api.auth.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.kirikick.api.auth.domain.SignupApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SignupApplicationRepository extends JpaRepository<SignupApplication, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from SignupApplication a where a.id = :id")
  Optional<SignupApplication> findByIdForUpdate(@Param("id") UUID id);
}

package org.kirikick.api.auth.repository;

import java.util.List;
import java.util.Optional;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OAuthIdentityRepository extends JpaRepository<OAuthIdentity, Long> {

  Optional<OAuthIdentity> findByProviderAndProviderSubject(
      OAuthProvider provider, String providerSubject);

  @Query("select identity.provider from OAuthIdentity identity where identity.userId = :userId")
  List<OAuthProvider> findProvidersByUserId(@Param("userId") Long userId);
}

package org.kirikick.api.auth.repository;

import java.util.Optional;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OAuthIdentityRepository extends JpaRepository<OAuthIdentity, Long> {

  Optional<OAuthIdentity> findByProviderAndProviderSubject(
      OAuthProvider provider, String providerSubject);
}

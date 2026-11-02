package org.kirikick.architecturefixtures.repository;

import org.kirikick.architecturefixtures.domain.DomainFixtures;
import org.springframework.data.repository.Repository;

public final class RepositoryFixtures {

  private RepositoryFixtures() {}

  public interface StoreRepository extends Repository<DomainFixtures.PrivateState, Long> {}
}

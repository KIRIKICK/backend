package org.kirikick.architecturefixtures.service;

import java.util.List;
import java.util.Optional;
import org.kirikick.architecturefixtures.domain.DomainFixtures;
import org.kirikick.architecturefixtures.repository.RepositoryFixtures.StoreRepository;

public final class ServiceFixtures {

  private ServiceFixtures() {}

  public static class Orchestrator {

    private final StoreRepository repository;

    public Orchestrator(StoreRepository repository) {
      this.repository = repository;
    }

    public DomainFixtures.PrivateState find() {
      return null;
    }
  }

  public static class RepositoryLeak {

    public StoreRepository direct() {
      return null;
    }

    protected Optional<List<StoreRepository>> nested() {
      return Optional.empty();
    }

    public StoreRepository[] array() {
      return null;
    }

    public List<? extends StoreRepository> wildcard() {
      return List.of();
    }

    public <T extends StoreRepository> T typeVariable() {
      return null;
    }
  }
}

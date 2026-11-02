package org.kirikick.architecturefixtures.domain;

import java.util.List;
import org.kirikick.architecturefixtures.service.ServiceFixtures;

public final class DomainFixtures {

  private DomainFixtures() {}

  public static class PrivateState {

    private static final String TYPE = "private-state";
    private String name;

    public String name() {
      return name;
    }

    public void rename(String name) {
      this.name = name;
    }
  }

  public static class ExposedState {

    String name;
  }

  public static class SetterState {

    private String name;

    public void setName(String name) {
      this.name = name;
    }
  }

  public static class OuterLayerDependency {

    private ServiceFixtures.Orchestrator orchestrator;
  }

  public static class EntityGraph {

    private List<PrivateState> participants;

    public List<PrivateState> participants() {
      return participants;
    }
  }
}

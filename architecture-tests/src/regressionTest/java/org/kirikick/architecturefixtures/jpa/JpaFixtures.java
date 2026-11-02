package org.kirikick.architecturefixtures.jpa;

import jakarta.persistence.Entity;

public final class JpaFixtures {

  private JpaFixtures() {}

  @Entity
  public static class EntityOutsideDomainPackage {

    String name;
  }
}

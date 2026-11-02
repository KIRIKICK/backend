package org.kirikick.testsupport.postgres;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class PostgresTestDatabase {

  private PostgresTestDatabase() {}

  public static PostgreSQLContainer<?> container() {
    return Holder.CONTAINER;
  }

  private static final class Holder {
    private static final PostgreSQLContainer<?> CONTAINER = start();

    private static PostgreSQLContainer<?> start() {
      PostgreSQLContainer<?> container =
          new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));
      container.start();
      return container;
    }
  }
}

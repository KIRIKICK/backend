package org.kirikick.testsupport.redis;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** JVM-lazy Redis Testcontainer shared by Redis-backed integration tests. */
public final class RedisTestServer {

  private RedisTestServer() {}

  public static String host() {
    return container().getHost();
  }

  public static int port() {
    return container().getMappedPort(6379);
  }

  private static GenericContainer<?> container() {
    return Holder.CONTAINER;
  }

  private static final class Holder {

    private static final GenericContainer<?> CONTAINER = start();

    private static GenericContainer<?> start() {
      GenericContainer<?> container =
          new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
      container.start();
      return container;
    }
  }
}

package org.kirikick.api.auth.support;

import java.util.Set;
import java.util.UUID;
import org.kirikick.testsupport.redis.RedisTestServer;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class RedisTestSupport implements AutoCloseable {

  private final LettuceConnectionFactory connectionFactory;
  private final StringRedisTemplate template;
  private final String namespace;

  private RedisTestSupport(
      LettuceConnectionFactory connectionFactory, StringRedisTemplate template, String namespace) {
    this.connectionFactory = connectionFactory;
    this.template = template;
    this.namespace = namespace;
  }

  public static RedisTestSupport create() {
    LettuceConnectionFactory connectionFactory =
        new LettuceConnectionFactory(RedisTestServer.host(), RedisTestServer.port());
    connectionFactory.afterPropertiesSet();
    StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
    template.afterPropertiesSet();
    return new RedisTestSupport(
        connectionFactory, template, "kirikick:test:auth:" + UUID.randomUUID());
  }

  public StringRedisTemplate template() {
    return template;
  }

  public String namespace() {
    return namespace;
  }

  public LettuceConnectionFactory connectionFactory() {
    return connectionFactory;
  }

  public Set<String> keys() {
    return template.keys(namespace + "*");
  }

  public void clearNamespace() {
    try (RedisConnection connection = connectionFactory.getConnection();
        Cursor<byte[]> cursor =
            connection.scan(ScanOptions.scanOptions().match(namespace + "*").count(100).build())) {
      while (cursor.hasNext()) {
        connection.del(cursor.next());
      }
    }
  }

  @Override
  public void close() {
    clearNamespace();
    connectionFactory.destroy();
  }
}

package org.kirikick.api.auth.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;
import org.kirikick.testsupport.redis.RedisTestServer;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Local-only infrastructure settings; no developer dotenv, provider or cloud Redis is consulted.
 */
public final class AuthTestSettings {
  private AuthTestSettings() {}

  public static void register(DynamicPropertyRegistry registry) {
    String namespace = "kirikick:test:" + UUID.randomUUID();
    registry.add("spring.config.import", () -> "");
    registry.add("spring.data.redis.host", RedisTestServer::host);
    registry.add("spring.data.redis.port", RedisTestServer::port);
    registry.add("spring.data.redis.username", () -> "");
    registry.add("spring.data.redis.password", () -> "");
    registry.add("spring.data.redis.ssl.enabled", () -> false);
    registry.add("auth.redis-namespace", () -> namespace + ":auth");
    registry.add("auth.session-redis-namespace", () -> namespace + ":spring:session");
    registry.add("spring.session.redis.namespace", () -> namespace + ":spring:session");
    registry.add("auth.fe-origin", () -> "http://localhost:5173");
    registry.add("auth.secure-cookies", () -> false);
    registry.add("auth.jwt.issuer", () -> "http://localhost:8080");
    registry.add("auth.jwt.audience", () -> "kirikick-test");
    registry.add("auth.jwt.private-key-location", () -> Keys.PRIVATE.toUri().toString());
    registry.add("auth.jwt.public-key-location", () -> Keys.PUBLIC.toUri().toString());
    registry.add("auth.kakao.client-id", () -> "fixture-client");
    registry.add("auth.kakao.client-secret", () -> "fixture-secret");
    registry.add("auth.kakao.redirect-uri", () -> "http://localhost:8080/login/oauth2/code/kakao");
    registry.add("auth.kakao.issuer-uri", () -> "http://127.0.0.1:9");
    registry.add("auth.kakao.authorization-uri", () -> "http://127.0.0.1:9/authorize");
    registry.add("auth.kakao.token-uri", () -> "http://127.0.0.1:9/token");
    registry.add("auth.kakao.jwk-set-uri", () -> "http://127.0.0.1:9/jwks");
  }

  public static KeyPair keyPair() {
    return Keys.PAIR;
  }

  private static final class Keys {
    private static final KeyPair PAIR;
    private static final Path PRIVATE;
    private static final Path PUBLIC;

    static {
      try {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        PAIR = generator.generateKeyPair();
        Path directory = Files.createTempDirectory("kirikick-auth-test-keys-");
        directory.toFile().deleteOnExit();
        PRIVATE =
            write(directory.resolve("private.pem"), "PRIVATE KEY", PAIR.getPrivate().getEncoded());
        PUBLIC =
            write(directory.resolve("public.pem"), "PUBLIC KEY", PAIR.getPublic().getEncoded());
      } catch (IOException | NoSuchAlgorithmException exception) {
        throw new ExceptionInInitializerError(exception);
      }
    }

    private static Path write(Path path, String label, byte[] encoded) throws IOException {
      String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
      Files.writeString(
          path,
          "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n",
          StandardCharsets.US_ASCII);
      path.toFile().deleteOnExit();
      return path;
    }
  }
}

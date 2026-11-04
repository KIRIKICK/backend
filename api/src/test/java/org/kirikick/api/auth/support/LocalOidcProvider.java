package org.kirikick.api.auth.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Local OIDC code-flow fixture. Test code must not log values returned by its capture records. */
public final class LocalOidcProvider implements AutoCloseable {
  public static final String CLIENT_ID = "fixture-client";
  public static final String CLIENT_SECRET = "fixture-secret";
  public static final String REDIRECT_URI = "http://localhost:8080/login/oauth2/code/kakao";

  private static final String KEY_ID = "fixture-key";
  private static final String DEFAULT_SUBJECT = "fixture-subject";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpServer server;
  private final ExecutorService executor;
  private final KeyPair signingKey;
  private final Map<String, Authorization> codes = new ConcurrentHashMap<>();
  private final AtomicReference<CapturedAuthorization> authorization = new AtomicReference<>();
  private final AtomicReference<CapturedTokenRequest> tokenRequest = new AtomicReference<>();
  private final AtomicReference<TokenFault> nextTokenFault = new AtomicReference<>(TokenFault.NONE);
  private final AtomicReference<String> nextSubject = new AtomicReference<>(DEFAULT_SUBJECT);
  private final AtomicReference<TokenResponseGate> nextTokenResponseGate = new AtomicReference<>();

  private LocalOidcProvider(HttpServer server, ExecutorService executor, KeyPair signingKey) {
    this.server = server;
    this.executor = executor;
    this.signingKey = signingKey;
  }

  public static LocalOidcProvider start() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ExecutorService executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    LocalOidcProvider provider = new LocalOidcProvider(server, executor, rsaKey());
    server.createContext("/authorize", provider::authorize);
    server.createContext("/token", provider::token);
    server.createContext("/jwks", provider::jwks);
    server.start();
    return provider;
  }

  public URI baseUri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  public URI authorizationUri() {
    return baseUri().resolve("/authorize");
  }

  public URI tokenUri() {
    return baseUri().resolve("/token");
  }

  public URI jwkSetUri() {
    return baseUri().resolve("/jwks");
  }

  public Map<String, String> authProperties() {
    return Map.of(
        "auth.kakao.client-id", CLIENT_ID,
        "auth.kakao.client-secret", CLIENT_SECRET,
        "auth.kakao.redirect-uri", REDIRECT_URI,
        "auth.kakao.issuer-uri", baseUri().toString(),
        "auth.kakao.authorization-uri", authorizationUri().toString(),
        "auth.kakao.token-uri", tokenUri().toString(),
        "auth.kakao.jwk-set-uri", jwkSetUri().toString());
  }

  public CapturedAuthorization authorization() {
    return authorization.get();
  }

  public CapturedTokenRequest tokenRequest() {
    return tokenRequest.get();
  }

  public void nextTokenFault(TokenFault fault) {
    nextTokenFault.set(Objects.requireNonNull(fault));
  }

  public void nextTokenSubject(String subject) {
    nextSubject.set(Objects.requireNonNull(subject));
  }

  public void clearCapturedRequests() {
    authorization.set(null);
    tokenRequest.set(null);
    nextTokenFault.set(TokenFault.NONE);
    nextSubject.set(DEFAULT_SUBJECT);
    TokenResponseGate gate = nextTokenResponseGate.getAndSet(null);
    if (gate != null) {
      gate.release();
    }
  }

  public TokenResponseGate blockNextTokenResponse() {
    TokenResponseGate gate = new TokenResponseGate();
    if (!nextTokenResponseGate.compareAndSet(null, gate)) {
      throw new IllegalStateException("a token response is already blocked");
    }
    return gate;
  }

  private void authorize(HttpExchange exchange) throws IOException {
    Map<String, String> query = parameters(exchange.getRequestURI().getRawQuery());
    String redirectUri = required(query, "redirect_uri");
    String code = UUID.randomUUID().toString();
    Authorization saved =
        new Authorization(
            query.get("state"),
            query.get("nonce"),
            query.get("code_challenge"),
            query.get("code_challenge_method"),
            redirectUri,
            query.get("client_id"));
    codes.put(code, saved);
    authorization.set(
        new CapturedAuthorization(
            saved.state(),
            saved.nonce(),
            saved.codeChallenge(),
            saved.codeChallengeMethod(),
            saved.redirectUri(),
            saved.clientId()));
    URI callback =
        URI.create(
            redirectUri
                + (redirectUri.contains("?") ? "&" : "?")
                + "code="
                + encode(code)
                + "&state="
                + encode(saved.state()));
    exchange.getResponseHeaders().set("Location", callback.toString());
    exchange.sendResponseHeaders(302, -1);
    exchange.close();
  }

  private void token(HttpExchange exchange) throws IOException {
    Map<String, String> form =
        parameters(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    String code = form.get("code");
    Authorization saved = codes.remove(code);
    tokenRequest.set(
        new CapturedTokenRequest(
            code,
            form.get("redirect_uri"),
            form.get("client_id"),
            form.get("code_verifier"),
            CLIENT_SECRET.equals(form.get("client_secret"))));
    if (saved == null
        || !CLIENT_ID.equals(form.get("client_id"))
        || !CLIENT_SECRET.equals(form.get("client_secret"))
        || !Objects.equals(saved.redirectUri(), form.get("redirect_uri"))
        || saved.codeChallenge() == null
        || !"S256".equals(saved.codeChallengeMethod())
        || !saved.codeChallenge().equals(pkceChallenge(form.get("code_verifier")))) {
      json(exchange, 400, Map.of("error", "invalid_grant"));
      return;
    }
    TokenFault fault = nextTokenFault.getAndSet(TokenFault.NONE);
    TokenResponseGate gate = nextTokenResponseGate.getAndSet(null);
    if (gate != null) {
      gate.awaitRelease();
    }
    json(
        exchange,
        200,
        Map.of(
            "access_token",
            "fixture-access-token",
            "token_type",
            "Bearer",
            "expires_in",
            300,
            "id_token",
            idToken(saved.nonce(), fault)));
  }

  private void jwks(HttpExchange exchange) throws IOException {
    RSAPublicKey key = (RSAPublicKey) signingKey.getPublic();
    json(
        exchange,
        200,
        Map.of(
            "keys",
            new Object[] {
              Map.of(
                  "kty",
                  "RSA",
                  "kid",
                  KEY_ID,
                  "use",
                  "sig",
                  "alg",
                  "RS256",
                  "n",
                  base64Url(unsigned(key.getModulus().toByteArray())),
                  "e",
                  base64Url(unsigned(key.getPublicExponent().toByteArray())))
            }));
  }

  private String idToken(String nonce, TokenFault fault) {
    Instant now = Instant.now();
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put(
        "iss", fault == TokenFault.WRONG_ISSUER ? "http://wrong-issuer" : baseUri().toString());
    claims.put("aud", fault == TokenFault.WRONG_AUDIENCE ? "wrong-audience" : CLIENT_ID);
    claims.put("sub", nextSubject.getAndSet(DEFAULT_SUBJECT));
    claims.put("nonce", fault == TokenFault.WRONG_NONCE ? "wrong-nonce" : nonce);
    claims.put("iat", now.getEpochSecond());
    claims.put(
        "exp",
        fault == TokenFault.EXPIRED
            ? now.minusSeconds(60).getEpochSecond()
            : now.plusSeconds(300).getEpochSecond());
    String header = base64Url(jsonBytes(Map.of("alg", "RS256", "kid", KEY_ID, "typ", "JWT")));
    String payload = base64Url(jsonBytes(claims));
    String input = header + "." + payload;
    return input
        + "."
        + base64Url(
            signature(
                input,
                fault == TokenFault.BAD_SIGNATURE
                    ? rsaKey().getPrivate()
                    : signingKey.getPrivate()));
  }

  private static byte[] signature(String input, PrivateKey key) {
    try {
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(key);
      signer.update(input.getBytes(StandardCharsets.US_ASCII));
      return signer.sign();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static KeyPair rsaKey() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String pkceChallenge(String verifier) {
    if (verifier == null) {
      return null;
    }
    try {
      return base64Url(
          MessageDigest.getInstance("SHA-256")
              .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static Map<String, String> parameters(String source) {
    Map<String, String> values = new LinkedHashMap<>();
    if (source == null || source.isEmpty()) {
      return values;
    }
    for (String part : source.split("&")) {
      String[] pair = part.split("=", 2);
      values.put(decode(pair[0]), pair.length == 2 ? decode(pair[1]) : "");
    }
    return values;
  }

  private static String required(Map<String, String> values, String name) {
    String value = values.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing " + name);
    }
    return value;
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private static void json(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = jsonBytes(body);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static byte[] jsonBytes(Object value) {
    try {
      return JSON.writeValueAsBytes(value);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static byte[] unsigned(byte[] bytes) {
    if (bytes.length > 1 && bytes[0] == 0) {
      byte[] result = new byte[bytes.length - 1];
      System.arraycopy(bytes, 1, result, 0, result.length);
      return result;
    }
    return bytes;
  }

  private static String base64Url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  public enum TokenFault {
    NONE,
    WRONG_NONCE,
    WRONG_ISSUER,
    WRONG_AUDIENCE,
    EXPIRED,
    BAD_SIGNATURE
  }

  public record CapturedAuthorization(
      String state,
      String nonce,
      String codeChallenge,
      String codeChallengeMethod,
      String redirectUri,
      String clientId) {}

  public record CapturedTokenRequest(
      String code,
      String redirectUri,
      String clientId,
      String codeVerifier,
      boolean clientSecretPost) {
    @Override
    public String toString() {
      return "CapturedTokenRequest[code=<redacted>, redirectUri="
          + redirectUri
          + ", clientId="
          + clientId
          + ", codeVerifier=<redacted>, clientSecretPost="
          + clientSecretPost
          + "]";
    }
  }

  private record Authorization(
      String state,
      String nonce,
      String codeChallenge,
      String codeChallengeMethod,
      String redirectUri,
      String clientId) {}

  public static final class TokenResponseGate implements AutoCloseable {
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    private void awaitRelease() throws IOException {
      entered.countDown();
      try {
        if (!released.await(30, TimeUnit.SECONDS)) {
          throw new IOException("timed out waiting for test token-response release");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IOException(
            "interrupted while waiting for test token-response release", exception);
      }
    }

    public boolean awaitEntered(Duration timeout) throws InterruptedException {
      return entered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void release() {
      released.countDown();
    }

    @Override
    public void close() {
      release();
    }
  }
}

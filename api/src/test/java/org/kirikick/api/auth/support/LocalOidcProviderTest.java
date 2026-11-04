package org.kirikick.api.auth.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class LocalOidcProviderTest {
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void exchangesOnlyTheMatchingPkceCodeAndPublishesAJwtVerifiableFromJwks() throws Exception {
    try (LocalOidcProvider provider = LocalOidcProvider.start()) {
      String verifier = "fixture-verifier";
      String code = authorize(provider, "nonce-value", pkce(verifier));

      HttpResponse<String> response = token(provider, code, verifier);

      assertThat(response.statusCode()).isEqualTo(200);
      String idToken = JSON.readTree(response.body()).required("id_token").textValue();
      assertThat(
              NimbusJwtDecoder.withJwkSetUri(provider.jwkSetUri().toString())
                  .build()
                  .decode(idToken)
                  .getSubject())
          .isEqualTo("fixture-subject");
      assertThat(provider.authorization().codeChallengeMethod()).isEqualTo("S256");
      assertThat(provider.tokenRequest().clientSecretPost()).isTrue();
      assertThat(provider.tokenRequest().redirectUri()).isEqualTo(LocalOidcProvider.REDIRECT_URI);
    }
  }

  @Test
  void rejectsATokenRequestWhoseVerifierDoesNotMatchTheAuthorizationChallenge() throws Exception {
    try (LocalOidcProvider provider = LocalOidcProvider.start()) {
      String code = authorize(provider, "nonce-value", pkce("expected-verifier"));

      HttpResponse<String> response = token(provider, code, "wrong-verifier");

      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(response.body()).contains("invalid_grant");
    }
  }

  @Test
  void holdsTheNextValidatedTokenResponseUntilTheTestReleasesItsGate() throws Exception {
    try (LocalOidcProvider provider = LocalOidcProvider.start();
        LocalOidcProvider.TokenResponseGate gate = provider.blockNextTokenResponse()) {
      String verifier = "fixture-verifier";
      String code = authorize(provider, "nonce-value", pkce(verifier));
      CompletableFuture<HttpResponse<String>> response =
          CompletableFuture.supplyAsync(() -> {
            try {
              return token(provider, code, verifier);
            } catch (Exception exception) {
              throw new CompletionException(exception);
            }
          });

      assertThat(gate.awaitEntered(Duration.ofSeconds(2))).isTrue();
      assertThat(response).isNotDone();
      gate.release();
      assertThat(response.get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }
  }

  private static String authorize(LocalOidcProvider provider, String nonce, String challenge)
      throws Exception {
    String query =
        form(
            Map.of(
                "response_type",
                "code",
                "client_id",
                LocalOidcProvider.CLIENT_ID,
                "redirect_uri",
                LocalOidcProvider.REDIRECT_URI,
                "state",
                "state-value",
                "nonce",
                nonce,
                "code_challenge",
                challenge,
                "code_challenge_method",
                "S256"));
    HttpResponse<Void> response =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(provider.authorizationUri() + "?" + query))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(302);
    return queryValue(response.headers().firstValue("Location").orElseThrow(), "code");
  }

  private static HttpResponse<String> token(
      LocalOidcProvider provider, String code, String verifier) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(provider.tokenUri())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    form(
                        Map.of(
                            "grant_type",
                            "authorization_code",
                            "code",
                            code,
                            "redirect_uri",
                            LocalOidcProvider.REDIRECT_URI,
                            "client_id",
                            LocalOidcProvider.CLIENT_ID,
                            "client_secret",
                            LocalOidcProvider.CLIENT_SECRET,
                            "code_verifier",
                            verifier))))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String pkce(String verifier) throws Exception {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
  }

  private static String form(Map<String, String> values) {
    return values.entrySet().stream()
        .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
        .collect(Collectors.joining("&"));
  }

  private static String queryValue(String value, String name) {
    String query = URI.create(value).getRawQuery();
    return Arrays.stream(query.split("&"))
        .map(part -> part.split("=", 2))
        .filter(pair -> pair[0].equals(name))
        .map(pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8))
        .findFirst()
        .orElseThrow();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}

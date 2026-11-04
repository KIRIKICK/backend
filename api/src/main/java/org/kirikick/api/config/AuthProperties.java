package org.kirikick.api.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
    @NotBlank String redisNamespace,
    @NotBlank String sessionRedisNamespace,
    @NotBlank String feOrigin,
    boolean secureCookies,
    @Valid @NotNull Jwt jwt,
    @Valid @NotNull Kakao kakao) {

  @AssertTrue(message = "인증과 임시 세션의 Redis namespace는 서로 겹칠 수 없습니다.")
  public boolean isNamespacesIsolated() {
    return redisNamespace != null
        && sessionRedisNamespace != null
        && !redisNamespace.isBlank()
        && !sessionRedisNamespace.isBlank()
        && !redisNamespace.equals(sessionRedisNamespace)
        && !redisNamespace.startsWith(sessionRedisNamespace + ":")
        && !sessionRedisNamespace.startsWith(redisNamespace + ":");
  }

  @AssertTrue(message = "FE 주소는 경로·인증정보·쿼리가 없는 HTTP(S) origin이어야 합니다.")
  public boolean isFeOriginValid() {
    try {
      URI uri = URI.create(feOrigin);
      return isHttp(uri)
          && uri.getHost() != null
          && uri.getRawUserInfo() == null
          && uri.getRawQuery() == null
          && uri.getRawFragment() == null
          && (uri.getRawPath() == null || uri.getRawPath().isEmpty());
    } catch (IllegalArgumentException | NullPointerException exception) {
      return false;
    }
  }

  private static boolean isHttp(URI uri) {
    return "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
  }

  public record Jwt(
      @NotBlank String issuer,
      @NotBlank String audience,
      @NotNull Resource privateKeyLocation,
      @NotNull Resource publicKeyLocation) {}

  public record Kakao(
      @NotBlank String clientId,
      @NotBlank String clientSecret,
      @NotBlank String redirectUri,
      @NotBlank String issuerUri,
      @NotBlank String authorizationUri,
      @NotBlank String tokenUri,
      @NotBlank String jwkSetUri) {

    @AssertTrue(message = "카카오 endpoint는 인증정보 없는 HTTP(S) 주소여야 합니다.")
    public boolean isEndpointsValid() {
      try {
        for (String endpoint :
            new String[] {redirectUri, issuerUri, authorizationUri, tokenUri, jwkSetUri}) {
          URI uri = URI.create(endpoint);
          if (!isHttp(uri)
              || uri.getHost() == null
              || uri.getRawUserInfo() != null
              || uri.getRawFragment() != null) {
            return false;
          }
        }
        return true;
      } catch (IllegalArgumentException | NullPointerException exception) {
        return false;
      }
    }

    @Override
    public String toString() {
      return "Kakao[clientId=" + clientId + ", clientSecret=<redacted>]";
    }
  }
}

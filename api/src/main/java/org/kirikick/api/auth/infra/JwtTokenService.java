package org.kirikick.api.auth.infra;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.kirikick.api.auth.domain.LoginSession;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

public final class JwtTokenService {
  public static final long ACCESS_TOKEN_SECONDS = 900;
  private final JwtEncoder encoder;
  private final JwtDecoder decoder;
  private final Clock clock;
  private final String issuer;
  private final String audience;

  public JwtTokenService(
      RSAPrivateKey privateKey,
      RSAPublicKey publicKey,
      Clock clock,
      String issuer,
      String audience) {
    if (publicKey.getModulus().bitLength() < 2048
        || !privateKey.getModulus().equals(publicKey.getModulus())) {
      throw new IllegalArgumentException("JWT에는 일치하는 2048비트 이상의 RSA 키가 필요합니다.");
    }
    this.clock = clock;
    this.issuer = issuer;
    this.audience = audience;
    encoder =
        new NimbusJwtEncoder(
            new ImmutableJWKSet<>(
                new JWKSet(new RSAKey.Builder(publicKey).privateKey(privateKey).build())));
    NimbusJwtDecoder nimbus =
        NimbusJwtDecoder.withPublicKey(publicKey)
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();
    JwtTimestampValidator timestamp = new JwtTimestampValidator(Duration.ZERO);
    timestamp.setClock(clock);
    nimbus.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            timestamp, new JwtIssuerValidator(issuer), this::validateClaims));
    decoder = nimbus;
  }

  public String issue(LoginSession session) {
    Instant now = clock.instant();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .audience(List.of(audience))
            .subject(session.userUuid().toString())
            .claim("sid", session.id())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(ACCESS_TOKEN_SECONDS))
            .build();
    return encoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build(), claims))
        .getTokenValue();
  }

  public Jwt decode(String token) {
    return decoder.decode(token);
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "The decoder is deliberately shared with Spring Security.")
  public JwtDecoder decoder() {
    return decoder;
  }

  private OAuth2TokenValidatorResult validateClaims(Jwt token) {
    try {
      UUID.fromString(token.getSubject());
      String sid = token.getClaimAsString("sid");
      Instant now = clock.instant();
      if (sid != null
          && sid.matches("[A-Za-z0-9_-]{1,128}")
          && token.getAudience().contains(audience)
          && token.getIssuedAt() != null
          && token.getExpiresAt() != null
          && !token.getIssuedAt().isAfter(now)
          && token.getExpiresAt().isAfter(token.getIssuedAt())
          && token.getExpiresAt().isAfter(now)) {
        return OAuth2TokenValidatorResult.success();
      }
    } catch (IllegalArgumentException | NullPointerException exception) {
      // Invalid claims are authentication failures, not parser details for the caller.
    }
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error("invalid_token", "인증 정보가 올바르지 않습니다.", null));
  }
}

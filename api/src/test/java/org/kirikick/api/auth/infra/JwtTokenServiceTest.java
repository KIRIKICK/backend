package org.kirikick.api.auth.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.LoginSession;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtTokenServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
  private static KeyPair keys;

  @BeforeAll
  static void keys() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keys = generator.generateKeyPair();
  }

  @Test
  void issuesRs256WithUuidSubjectSessionIssuerAudienceAndFifteenMinuteExpiry() {
    var service = service(NOW, "https://api.example", "kirikick");
    var session = session();
    var token = service.decode(service.issue(session));
    assertThat(token.getHeaders().get("alg")).isEqualTo("RS256");
    assertThat(token.getSubject()).isEqualTo(session.userUuid().toString());
    assertThat(token.getClaimAsString("sid")).isEqualTo(session.id());
    assertThat(token.getIssuer().toString()).isEqualTo("https://api.example");
    assertThat(token.getAudience()).containsExactly("kirikick");
    assertThat(token.getIssuedAt()).isEqualTo(NOW);
    assertThat(token.getExpiresAt()).isEqualTo(NOW.plusSeconds(900));
  }

  @Test
  void rejectsExpiredTokensAndWrongIssuerOrAudience() {
    String valid = service(NOW, "https://api.example", "kirikick").issue(session());
    assertThatThrownBy(
            () -> service(NOW.plusSeconds(901), "https://api.example", "kirikick").decode(valid))
        .isInstanceOf(JwtException.class);
    assertThatThrownBy(() -> service(NOW, "https://other.example", "kirikick").decode(valid))
        .isInstanceOf(JwtException.class);
    assertThatThrownBy(() -> service(NOW, "https://api.example", "another-audience").decode(valid))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void rejectsTokensSignedByAnotherKeyAndMismatchedConfiguredKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair another = generator.generateKeyPair();
    var foreign =
        new JwtTokenService(
            (RSAPrivateKey) another.getPrivate(),
            (RSAPublicKey) another.getPublic(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            "https://api.example",
            "kirikick");
    String token = foreign.issue(session());
    assertThatThrownBy(() -> service(NOW, "https://api.example", "kirikick").decode(token))
        .isInstanceOf(JwtException.class);
    assertThatThrownBy(() ->
            new JwtTokenService(
                (RSAPrivateKey) keys.getPrivate(),
                (RSAPublicKey) another.getPublic(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "https://api.example",
                "kirikick"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private JwtTokenService service(Instant now, String issuer, String audience) {
    return new JwtTokenService(
        (RSAPrivateKey) keys.getPrivate(),
        (RSAPublicKey) keys.getPublic(),
        Clock.fixed(now, ZoneOffset.UTC),
        issuer,
        audience);
  }

  private LoginSession session() {
    return new LoginSession(
        UUID.randomUUID().toString(),
        1L,
        UUID.randomUUID(),
        NOW,
        NOW.plusSeconds(14 * 86400),
        NOW.plusSeconds(7 * 86400));
  }
}

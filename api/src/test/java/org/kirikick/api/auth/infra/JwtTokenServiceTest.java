package org.kirikick.api.auth.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.auth.domain.LoginSession;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

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

  @ParameterizedTest(name = "exposedDecoder={0}, offsetNanos={1}, valid={2}")
  @CsvSource({
    "false, -1, true",
    "false, 0, false",
    "false, 1, false",
    "true, -1, true",
    "true, 0, false",
    "true, 1, false"
  })
  void enforcesSerializedExpiryBoundaryForBothDecoderEntrypoints(
      boolean exposedDecoder, long offsetNanos, boolean expectedValid) {
    Instant issuedAt = NOW.plusNanos(123_456_789);
    var issuer = service(issuedAt, "https://api.example", "kirikick");
    String raw = issuer.issue(session());
    var token = issuer.decode(raw);
    Instant expiresAt = token.getExpiresAt();
    assertThat(expiresAt.getNano()).isZero();
    assertThat(expiresAt).isNotEqualTo(issuedAt.plusSeconds(900));
    assertThat(Duration.between(token.getIssuedAt(), expiresAt)).isEqualTo(Duration.ofSeconds(900));

    var verifier = service(expiresAt.plusNanos(offsetNanos), "https://api.example", "kirikick");
    JwtDecoder decoder = exposedDecoder ? verifier.decoder() : verifier::decode;
    if (expectedValid) {
      assertThat(decoder.decode(raw).getExpiresAt()).isEqualTo(expiresAt);
    } else {
      assertThatThrownBy(() -> decoder.decode(raw)).isInstanceOf(JwtException.class);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"iat", "exp"})
  void rejectsMissingRequiredTimeClaim(String missingClaim) throws Exception {
    String raw =
        signedTimeClaims(
            "iat".equals(missingClaim) ? null : NOW,
            "exp".equals(missingClaim) ? null : NOW.plusSeconds(900),
            null);
    assertThat(SignedJWT.parse(raw).getJWTClaimsSet().getClaims().containsKey(missingClaim))
        .isFalse();

    JwtDecoder decoder = service(NOW, "https://api.example", "kirikick").decoder();
    assertThatThrownBy(() -> decoder.decode(raw)).isInstanceOf(JwtException.class);
  }

  @ParameterizedTest(name = "claim={0}, value={1}")
  @MethodSource("invalidRequiredClaims")
  void rejectsInvalidRequiredClaims(String claimName, Object claimValue) throws Exception {
    String raw = signedClaim(claimName, claimValue);
    var service = service(NOW, "https://api.example", "kirikick");

    assertThatThrownBy(() -> service.decode(raw)).isInstanceOf(JwtException.class);
    assertThatThrownBy(() -> service.decoder().decode(raw)).isInstanceOf(JwtException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"sub", "sid", "aud"})
  void reportsMissingIdentityClaimsAsInvalidToken(String missingClaim) throws Exception {
    String raw = signedClaim(missingClaim, null);
    var service = service(NOW, "https://api.example", "kirikick");

    for (JwtDecoder decoder : List.<JwtDecoder>of(service::decode, service.decoder())) {
      assertThatThrownBy(() -> decoder.decode(raw))
          .isInstanceOfSatisfying(
              JwtValidationException.class,
              exception ->
                  assertThat(exception.getErrors())
                      .anyMatch(error -> "invalid_token".equals(error.getErrorCode())));
    }
  }

  private String signedClaim(String claimName, Object claimValue) throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .issuer("https://api.example")
            .audience("kirikick")
            .subject(session().userUuid().toString())
            .claim("sid", session().id())
            .issueTime(Date.from(NOW))
            .expirationTime(Date.from(NOW.plusSeconds(900)))
            .claim(claimName, claimValue)
            .build();
    var token =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), claims);
    token.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
    return token.serialize();
  }

  private static Stream<Arguments> invalidRequiredClaims() {
    return Stream.of(
        Arguments.of("sub", null),
        Arguments.of("sub", "not-a-uuid"),
        Arguments.of("sub", List.of("not-a-subject")),
        Arguments.of("sid", null),
        Arguments.of("sid", ""),
        Arguments.of("sid", "invalid sid"),
        Arguments.of("sid", List.of("invalid-sid")),
        Arguments.of("aud", null),
        Arguments.of("aud", List.of("other-audience")),
        Arguments.of("aud", 123),
        Arguments.of("iat", null),
        Arguments.of("iat", "not-a-timestamp"),
        Arguments.of("exp", null),
        Arguments.of("exp", "not-a-timestamp"));
  }

  @ParameterizedTest(name = "iatOffsetSeconds={0}, expOffsetSeconds={1}")
  @CsvSource({"1, 900", "0, 0", "0, -1"})
  void rejectsInvalidIssuedAndExpiryClaims(long issuedOffset, long expiryOffset) throws Exception {
    Instant issuedAt = NOW.plusSeconds(issuedOffset);
    Instant expiresAt = NOW.plusSeconds(expiryOffset);
    String raw = signedTimeClaims(issuedAt, expiresAt, null);
    var claims = SignedJWT.parse(raw).getJWTClaimsSet();
    assertThat(claims.getIssueTime().toInstant()).isEqualTo(issuedAt);
    assertThat(claims.getExpirationTime().toInstant()).isEqualTo(expiresAt);

    JwtDecoder decoder = service(NOW, "https://api.example", "kirikick").decoder();
    assertThatThrownBy(() -> decoder.decode(raw)).isInstanceOf(JwtException.class);
  }

  @ParameterizedTest(name = "nbfOffsetSeconds={0}, valid={1}")
  @CsvSource({"0, true", "1, false"})
  void preservesOptionalNotBeforeValidation(long offsetSeconds, boolean expectedValid)
      throws Exception {
    Instant notBefore = NOW.plusSeconds(offsetSeconds);
    String raw = signedTimeClaims(NOW, NOW.plusSeconds(900), notBefore);
    assertThat(SignedJWT.parse(raw).getJWTClaimsSet().getNotBeforeTime().toInstant())
        .isEqualTo(notBefore);

    JwtDecoder decoder = service(NOW, "https://api.example", "kirikick").decoder();
    if (expectedValid) {
      assertThat(decoder.decode(raw).getNotBefore()).isEqualTo(notBefore);
    } else {
      assertThatThrownBy(() -> decoder.decode(raw)).isInstanceOf(JwtException.class);
    }
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

  private String signedTimeClaims(Instant issuedAt, Instant expiresAt, Instant notBefore)
      throws Exception {
    var session = session();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer("https://api.example")
            .audience("kirikick")
            .subject(session.userUuid().toString())
            .claim("sid", session.id())
            .issueTime(issuedAt == null ? null : Date.from(issuedAt))
            .expirationTime(expiresAt == null ? null : Date.from(expiresAt))
            .notBeforeTime(notBefore == null ? null : Date.from(notBefore))
            .build();
    var token =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), claims);
    token.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
    return token.serialize();
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

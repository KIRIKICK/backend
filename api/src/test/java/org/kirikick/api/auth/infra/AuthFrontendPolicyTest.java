package org.kirikick.api.auth.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AuthFrontendPolicyTest {
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "null",
        "https://app.example/evil",
        "https://app.example?x=1",
        "https://user@app.example",
        "https://app.example:",
        "https://app.example:65536",
        "https://[invalid",
        "ftp://app.example"
      })
  void returnsNullForMissingOrInvalidOrigins(String origin) {
    assertThat(AuthFrontendPolicy.canonicalOriginOrNull(origin)).isNull();
    assertThat(new AuthFrontendPolicy("https://app.example", List.of()).isAllowed(origin))
        .isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "https://APP.example:443, https://app.example",
    "http://APP.example:80, http://app.example",
    "https://APP.example:8443, https://app.example:8443",
    "http://localhost:5173, http://localhost:5173",
    "https://app.example:0, https://app.example:0",
    "https://app.example:65535, https://app.example:65535"
  })
  void canonicalizesValidOrigins(String origin, String expected) {
    assertThat(AuthFrontendPolicy.canonicalOriginOrNull(origin)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://app.example:", "https://app.example:65536"})
  void rejectsInvalidDefaultAndAdditionalOrigins(String origin) {
    assertThatThrownBy(() -> new AuthFrontendPolicy(origin, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not an origin");
    assertThatThrownBy(() -> new AuthFrontendPolicy("https://app.example", List.of(origin)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not an origin");
  }

  @Test
  void canonicalizesOnlyAnAllowedExplicitSelectorAndPreservesTheConfiguredDefaultCallback() {
    AuthFrontendPolicy policy =
        new AuthFrontendPolicy("https://App.example:443", List.of("http://localhost:5173"));

    assertThat(policy.select(null)).isEqualTo("https://App.example:443");
    assertThat(policy.callback(null)).isEqualTo("https://App.example:443/auth/kakao/callback");
    assertThat(policy.select(new String[] {"HTTP://LOCALHOST:5173"}))
        .isEqualTo("http://localhost:5173");
    assertThat(policy.isAllowed("https://app.example")).isTrue();
    assertThat(policy.isAllowed("https://evil.app.example")).isFalse();
  }

  @Test
  void rejectsBlankMultipleMalformedAndUnlistedSelectors() {
    AuthFrontendPolicy policy = new AuthFrontendPolicy("https://app.example", List.of());

    for (String[] selector :
        List.of(
            new String[] {""},
            new String[] {"https://app.example", "https://app.example"},
            new String[] {"https://app.example/auth/kakao/callback"},
            new String[] {"https://evil.example"})) {
      assertThatThrownBy(() -> policy.select(selector))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}

package org.kirikick.api.auth.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AuthFrontendPolicyTest {
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

package org.kirikick.api.auth.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OriginValidationFilterTest {
  @Test
  void acceptsOnlyTheExactNormalizedOriginForMutations() throws Exception {
    OriginValidationFilter filter =
        new OriginValidationFilter("https://app.example", new ObjectMapper());
    MockHttpServletRequest accepted = new MockHttpServletRequest("POST", "/auth/refresh");
    accepted.addHeader("Origin", "https://APP.example:443");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean passed = new AtomicBoolean();
    filter.doFilter(accepted, response, (request, servletResponse) -> passed.set(true));
    assertThat(passed).isTrue();

    for (String origin :
        new String[] {
          null, "https://app.example/evil", "https://evil.app.example", "https://app.example?x=1"
        }) {
      MockHttpServletRequest denied = new MockHttpServletRequest("POST", "/auth/refresh");
      if (origin != null) {
        denied.addHeader("Origin", origin);
      }
      MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
      filter.doFilter(
          denied,
          deniedResponse,
          (request, servletResponse) -> {
            throw new AssertionError("denied origin must not continue");
          });
      assertThat(deniedResponse.getStatus()).isEqualTo(403);
      assertThat(deniedResponse.getContentAsString()).contains("AUTHENTICATION_REQUIRED");
      String body = new String(deniedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
      assertThat(new ObjectMapper().readTree(body).get("message").asText())
          .isEqualTo("허용되지 않은 출처입니다.");
    }
  }

  @Test
  void doesNotRequireOriginForSafeReads() throws Exception {
    OriginValidationFilter filter =
        new OriginValidationFilter("https://app.example", new ObjectMapper());
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean passed = new AtomicBoolean();
    filter.doFilter(
        new MockHttpServletRequest("GET", "/auth/csrf"),
        response,
        (request, servletResponse) -> passed.set(true));
    assertThat(passed).isTrue();
  }
}

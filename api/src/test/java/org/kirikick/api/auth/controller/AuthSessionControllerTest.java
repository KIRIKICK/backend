package org.kirikick.api.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.LoginGrant;
import org.kirikick.api.auth.domain.LoginSession;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.common.exception.DomainException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthSessionControllerTest {
  @Test
  void refreshDocumentationIncludesOriginCsrfAndInactiveAccountExamples() throws Exception {
    ApiResponse forbidden =
        Arrays.stream(
                AuthSessionController.class
                    .getDeclaredMethod(
                        "refresh", HttpServletRequest.class, HttpServletResponse.class)
                    .getAnnotation(ApiResponses.class)
                    .value())
            .filter(response -> response.responseCode().equals("403"))
            .findFirst()
            .orElseThrow();

    ExampleObject[] examples = forbidden.content()[0].examples();
    assertThat(examples)
        .anySatisfy(example -> {
          assertThat(example.name()).isEqualTo("Origin 또는 CSRF 검증 실패");
          assertThat(example.value()).contains("AUTHENTICATION_REQUIRED");
        });
    assertThat(examples)
        .anySatisfy(example -> {
          assertThat(example.name()).isEqualTo("비활성 회원");
          assertThat(example.value()).contains("ACCOUNT_INACTIVE");
        });
  }

  @Test
  void returnsMaskedCsrfRequestAttributeWithoutCreatingAServiceSession() throws Exception {
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new AuthSessionController(
                    mock(LoginSessionService.class), new AuthHttpSupport(true)))
            .setControllerAdvice(new AuthExceptionHandler())
            .build();

    mvc.perform(
            get("/auth/csrf")
                .requestAttr(
                    CsrfToken.class.getName(),
                    new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "masked")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token", is("masked")))
        .andExpect(cookie().doesNotExist("JSESSIONID"));
  }

  @Test
  void rotatesRefreshCookieAndReturnsOnlyAccessToken() throws Exception {
    LoginSessionService sessions = mock(LoginSessionService.class);
    LoginGrant grant =
        new LoginGrant(
            new LoginSession("sid", 1L, UUID.randomUUID(), Instant.EPOCH, Instant.MAX, Instant.MAX),
            "access",
            "refresh");
    when(sessions.refresh("old-refresh")).thenReturn(grant);
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new AuthSessionController(sessions, new AuthHttpSupport(true)))
            .setControllerAdvice(new AuthExceptionHandler())
            .build();

    MvcResult result =
        mvc.perform(post("/auth/refresh").cookie(new Cookie("__Host-refresh", "old-refresh")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken", is("access")))
            .andExpect(jsonPath("$.tokenType", is("Bearer")))
            .andExpect(jsonPath("$.expiresIn", is(900)))
            .andExpect(cookie().value("__Host-refresh", "refresh"))
            .andExpect(cookie().httpOnly("__Host-refresh", true))
            .andExpect(cookie().secure("__Host-refresh", true))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn();
    assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
        .contains("SameSite=Strict")
        .contains("Path=/")
        .doesNotContain("Domain=");
  }

  @Test
  void doesNotMutateRefreshCookieWhenRefreshRequiresAuthentication() throws Exception {
    assertRefreshFailure(AuthErrorCode.AUTHENTICATION_REQUIRED, status().isUnauthorized());
  }

  @Test
  void doesNotMutateRefreshCookieWhenAccountIsInactive() throws Exception {
    assertRefreshFailure(AuthErrorCode.ACCOUNT_INACTIVE, status().isForbidden());
  }

  @Test
  void doesNotMutateRefreshCookieWhenAuthenticationStateIsUnavailable() throws Exception {
    assertRefreshFailure(AuthErrorCode.AUTH_STATE_UNAVAILABLE, status().isServiceUnavailable());
  }

  private static void assertRefreshFailure(AuthErrorCode errorCode, ResultMatcher expectedStatus)
      throws Exception {
    LoginSessionService sessions = mock(LoginSessionService.class);
    when(sessions.refresh("old-refresh")).thenThrow(new DomainException(errorCode));
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new AuthSessionController(sessions, new AuthHttpSupport(true)))
            .setControllerAdvice(new AuthExceptionHandler())
            .build();

    MvcResult result =
        mvc.perform(post("/auth/refresh").cookie(new Cookie("__Host-refresh", "old-refresh")))
            .andExpect(expectedStatus)
            .andExpect(jsonPath("$.code", is(errorCode.toString())))
            .andExpect(jsonPath("$.message", is(errorCode.getMessage())))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn();

    assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
        .noneMatch(value -> value.startsWith("__Host-refresh="));
  }
}

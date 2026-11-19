package org.kirikick.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.auth.controller.dto.CsrfResponse;
import org.kirikick.api.auth.controller.dto.TokenResponse;
import org.kirikick.api.auth.domain.LoginGrant;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
class DevCrossSiteCookieIntegrationTest {
  private static final String FE_ORIGIN = "https://frontend.example";
  private static final String ADDITIONAL_FE_ORIGIN = "http://localhost:5173";

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private LoginSessionService logins;
  @Autowired private UserRepository users;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    String namespace = "kirikick:dev:cross-site:" + UUID.randomUUID();
    registry.add("auth.redis-namespace", () -> namespace + ":auth");
    registry.add("auth.session-redis-namespace", () -> namespace + ":spring:session");
    registry.add("spring.session.redis.namespace", () -> namespace + ":spring:session");
    registry.add("auth.secure-cookies", () -> true);
    registry.add("auth.cross-site-cookies", () -> true);
    registry.add("auth.fe-origin", () -> FE_ORIGIN);
    registry.add("auth.dev-additional-fe-origins", () -> ADDITIONAL_FE_ORIGIN);
    String database = "jdbc:h2:mem:cross-site-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    registry.add("spring.datasource.url", () -> database);
    registry.add("spring.datasource.username", () -> "sa");
    registry.add("spring.datasource.password", () -> "");
  }

  @Test
  @DisplayName("CSRF 쿠키는 SameSite=None과 Secure를 쓰고 온보딩 세션을 생성하지 않는다")
  void csrfCookieUsesNoneAndSecureWithoutCreatingAnOnboardingSession() throws Exception {
    MvcResult result =
        mvc.perform(get("/auth/csrf").secure(true)).andExpect(status().isOk()).andReturn();
    assertCrossSiteCookie(result.getResponse().getCookie("__Host-csrf"));
    assertThat(result.getRequest().getSession(false)).isNull();
    assertThat(result.getResponse().getCookie("__Host-onboarding")).isNull();
  }

  @Test
  @DisplayName("추가 DEV Origin에서 CSRF 엔드포인트를 읽을 수 있다")
  void additionalDevOriginCanReadTheCsrfEndpoint() throws Exception {
    mvc.perform(get("/auth/csrf").secure(true).header(HttpHeaders.ORIGIN, ADDITIONAL_FE_ORIGIN))
        .andExpect(status().isOk())
        .andExpect(result ->
            assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo(ADDITIONAL_FE_ORIGIN));
  }

  @ParameterizedTest
  @DisplayName("허용된 DEV Origin은 CORS 사전 요청을 통과한다")
  @ValueSource(strings = {FE_ORIGIN, ADDITIONAL_FE_ORIGIN})
  void allowedDevOriginsPassCorsPreflight(String origin) throws Exception {
    mvc.perform(
            options("/auth/refresh")
                .secure(true)
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type, X-XSRF-TOKEN"))
        .andExpect(status().isOk())
        .andExpect(result ->
            assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo(origin));
  }

  @Test
  @DisplayName("별칭 Origin은 CORS 사전 요청에서 거절된다")
  void aliasOriginIsRejectedByCorsPreflight() throws Exception {
    mvc.perform(
            options("/auth/refresh")
                .secure(true)
                .header(HttpHeaders.ORIGIN, FE_ORIGIN + ".attacker.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
        .andExpect(status().isForbidden())
        .andExpect(result ->
            assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isNull());
  }

  @Test
  @DisplayName("온보딩 쿠키는 SameSite=None과 Secure를 쓰고 PKCE와 state 및 nonce를 유지한다")
  void onboardingCookieUsesNoneAndSecureWhileKeepingPkceStateAndNonce() throws Exception {
    MvcResult result =
        mvc.perform(get("/oauth2/authorization/kakao").secure(true))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    assertCrossSiteCookie(result.getResponse().getCookie("__Host-onboarding"));
    assertThat(result.getResponse().getRedirectedUrl())
        .contains("code_challenge_method=S256", "code_challenge=", "state=", "nonce=");
  }

  @ParameterizedTest
  @DisplayName("허용된 DEV Origin에서도 CSRF 검사와 Refresh 재사용 탐지를 적용한다")
  @ValueSource(strings = {FE_ORIGIN, ADDITIONAL_FE_ORIGIN})
  void allowedDevOriginsPreserveCsrfAndRefreshReuseChecks(String origin) throws Exception {
    LoginGrant initial = logins.login(users.saveAndFlush(User.create("쿠키검증", null)));
    MvcResult csrf =
        mvc.perform(get("/auth/csrf").secure(true)).andExpect(status().isOk()).andReturn();
    Cookie csrfCookie = csrf.getResponse().getCookie("__Host-csrf");
    String token =
        json.readValue(csrf.getResponse().getContentAsString(), CsrfResponse.class).token();

    mvc.perform(
            post("/auth/refresh")
                .secure(true)
                .cookie(new Cookie("__Host-refresh", initial.refreshToken()))
                .header(HttpHeaders.ORIGIN, origin))
        .andExpect(status().isForbidden());
    refresh(initial.refreshToken(), csrfCookie, "invalid", origin)
        .andExpect(status().isForbidden());
    refresh(initial.refreshToken(), csrfCookie, token, null).andExpect(status().isForbidden());
    refresh(initial.refreshToken(), csrfCookie, token, FE_ORIGIN + ".attacker.example")
        .andExpect(status().isForbidden());

    MvcResult refreshed =
        refresh(initial.refreshToken(), csrfCookie, token, origin)
            .andExpect(status().isOk())
            .andReturn();
    Cookie successor = refreshed.getResponse().getCookie("__Host-refresh");
    assertCrossSiteCookie(successor);
    TokenResponse response =
        json.readValue(refreshed.getResponse().getContentAsString(), TokenResponse.class);
    mvc.perform(
            get("/users/me/profile")
                .secure(true)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + response.accessToken()))
        .andExpect(status().isOk());

    refresh(initial.refreshToken(), csrfCookie, token, origin).andExpect(status().isUnauthorized());
    refresh(successor.getValue(), csrfCookie, token, origin).andExpect(status().isUnauthorized());
    mvc.perform(
            get("/users/me/profile")
                .secure(true)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + response.accessToken()))
        .andExpect(status().isUnauthorized());
  }

  private ResultActions refresh(String refresh, Cookie csrf, String token, String origin)
      throws Exception {
    MockHttpServletRequestBuilder request =
        post("/auth/refresh")
            .secure(true)
            .cookie(new Cookie("__Host-refresh", refresh), csrf)
            .header("X-XSRF-TOKEN", token);
    if (origin != null) {
      request.header(HttpHeaders.ORIGIN, origin);
    }
    return mvc.perform(request);
  }

  private static void assertCrossSiteCookie(Cookie cookie) {
    assertThat(cookie).isNotNull();
    assertThat(cookie.getAttribute("SameSite")).isEqualTo("None");
    assertThat(cookie.getSecure()).isTrue();
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.getPath()).isEqualTo("/");
    assertThat(cookie.getDomain()).isNull();
  }
}

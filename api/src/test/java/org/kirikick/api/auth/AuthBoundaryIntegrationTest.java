package org.kirikick.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.controller.dto.ProbeResponse;
import org.kirikick.api.auth.domain.LoginGrant;
import org.kirikick.api.auth.domain.LoginSession;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.infra.JwtTokenService;
import org.kirikick.api.auth.infra.RedisRefreshSessionStore;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.domain.UserStatus;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthBoundaryIntegrationTest.ProbeController.class)
class AuthBoundaryIntegrationTest {
  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private LoginSessionService logins;
  @Autowired private JwtTokenService tokens;
  @Autowired private UserRepository users;
  @MockitoSpyBean private RedisRefreshSessionStore sessions;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    String db = "jdbc:h2:mem:auth-boundary-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    registry.add("spring.datasource.url", () -> db);
  }

  @Test
  void csrfEndpointReturnsMaskedTokenWithoutCreatingAnOnboardingSession() throws Exception {
    MvcResult result =
        mvc.perform(get("/auth/csrf"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andReturn();
    assertThat(result.getRequest().getSession(false)).isNull();
    String token = json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    Cookie cookie = result.getResponse().getCookie("dev-csrf");
    assertThat(cookie).isNotNull();
    assertThat(token).isNotEqualTo(cookie.getValue());
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.getSecure()).isFalse();
    assertThat(cookie.getPath()).isEqualTo("/");
    assertThat(cookie.getDomain()).isNull();
    assertThat(cookie.getAttribute("SameSite")).isEqualTo("Strict");
    assertThat(result.getResponse().getHeaders("Set-Cookie"))
        .allMatch(value -> !value.contains("onboarding") && !value.contains("JSESSIONID"));
  }

  @Test
  void protectedRequestRequiresSignatureRedisOwnerAndActiveDatabaseMember() throws Exception {
    User user = users.saveAndFlush(User.create("테스터", null));
    LoginGrant grant = logins.login(user);
    mvc.perform(get("/test/protected")).andExpect(status().isUnauthorized());
    mvc.perform(get("/test/protected").header("Authorization", "Bearer " + grant.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(true));
    LoginSession original = grant.session();
    String wrongOwner =
        tokens.issue(
            new LoginSession(
                original.id(),
                original.userId(),
                UUID.randomUUID(),
                original.createdAt(),
                original.absoluteExpiresAt(),
                original.expiresAt()));
    mvc.perform(get("/test/protected").header("Authorization", "Bearer " + wrongOwner))
        .andExpect(status().isUnauthorized());
    ReflectionTestUtils.setField(user, "status", UserStatus.SUSPENDED);
    users.saveAndFlush(user);
    mvc.perform(get("/test/protected").header("Authorization", "Bearer " + grant.accessToken()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"));
  }

  @Test
  void exactRefreshReplayInvalidatesWinnerRefreshAndAlreadyIssuedAccessToken() throws Exception {
    LoginGrant initial = logins.login(users.saveAndFlush(User.create("회전검증", null)));
    MvcResult csrf = mvc.perform(get("/auth/csrf")).andReturn();
    Cookie csrfCookie = csrf.getResponse().getCookie("dev-csrf");
    String masked = json.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
    MvcResult rotated =
        refresh(initial.refreshToken(), csrfCookie, masked, "http://localhost:5173")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900))
            .andReturn();
    String access =
        json.readTree(rotated.getResponse().getContentAsString()).get("accessToken").asText();
    Cookie successor = rotated.getResponse().getCookie("dev-refresh");
    assertThat(successor).isNotNull();
    mvc.perform(get("/test/protected").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk());
    refresh(initial.refreshToken(), csrfCookie, masked, "http://localhost:5173")
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
    refresh(successor.getValue(), csrfCookie, masked, "http://localhost:5173")
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/test/protected").header("Authorization", "Bearer " + access))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void originAndCsrfFailuresDoNotRotateTheSession() throws Exception {
    LoginGrant grant = logins.login(users.saveAndFlush(User.create("요청검증", null)));
    MvcResult csrf = mvc.perform(get("/auth/csrf")).andReturn();
    Cookie cookie = csrf.getResponse().getCookie("dev-csrf");
    String masked = json.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
    mvc.perform(
            post("/auth/refresh")
                .cookie(new Cookie("dev-refresh", grant.refreshToken()))
                .header("Origin", "http://localhost:5173"))
        .andExpect(status().isForbidden());
    refresh(grant.refreshToken(), cookie, masked, "http://localhost:5173.attacker.example")
        .andExpect(status().isForbidden());
    refresh(grant.refreshToken(), cookie, masked, "http://localhost:5173")
        .andExpect(status().isOk());
  }

  @Test
  void successfulRefreshAndProtectedBearerDoNotCreateOnboardingSessions() throws Exception {
    LoginGrant initial = logins.login(users.saveAndFlush(User.create("쿠키검증", null)));
    MvcResult csrf = mvc.perform(get("/auth/csrf")).andReturn();
    Cookie csrfCookie = csrf.getResponse().getCookie("dev-csrf");
    String masked = json.readTree(csrf.getResponse().getContentAsString()).get("token").asText();

    MvcResult refresh =
        refresh(initial.refreshToken(), csrfCookie, masked, "http://localhost:5173")
            .andExpect(status().isOk())
            .andReturn();
    String refreshCookie = refresh.getResponse().getHeader(HttpHeaders.SET_COOKIE);
    assertThat(refresh.getRequest().getSession(false)).isNull();
    assertThat(refreshCookie)
        .contains("dev-refresh=")
        .contains("HttpOnly")
        .contains("SameSite=Strict")
        .contains("Path=/")
        .doesNotContain("Domain=")
        .doesNotContain("Secure")
        .doesNotContain("JSESSIONID");

    String access =
        json.readTree(refresh.getResponse().getContentAsString()).get("accessToken").asText();
    MvcResult protectedResult =
        mvc.perform(get("/test/protected").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(protectedResult.getRequest().getSession(false)).isNull();
    assertThat(protectedResult.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
        .allMatch(value -> !value.contains("onboarding") && !value.contains("JSESSIONID"));
  }

  @Test
  void corsPreflightCanonicalizesOriginWithoutWildcardCredentials() throws Exception {
    MvcResult accepted =
        mvc.perform(
                options("/auth/refresh")
                    .header(HttpHeaders.ORIGIN, "http://LOCALHOST:5173")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .header(
                        HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type, X-XSRF-TOKEN"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(accepted.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .isEqualTo("http://LOCALHOST:5173")
        .isNotEqualTo("*");
    assertThat(accepted.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
        .isEqualTo("true");

    for (String origin :
        new String[] {
          "http://localhost:5173.attacker.example",
          "http://localhost:5173/path",
          "http://localhost:5174"
        }) {
      mvc.perform(
              options("/auth/refresh")
                  .header(HttpHeaders.ORIGIN, origin)
                  .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void redisUnavailableIs503RatherThanAcceptingAnOtherwiseValidJwt() throws Exception {
    LoginGrant grant = logins.login(users.saveAndFlush(User.create("장애검증", null)));
    doThrow(new DomainException(AuthErrorCode.AUTH_STATE_UNAVAILABLE))
        .when(sessions)
        .findActive(grant.session().id(), grant.session().userUuid());
    mvc.perform(get("/test/protected").header("Authorization", "Bearer " + grant.accessToken()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AUTH_STATE_UNAVAILABLE"));
  }

  private ResultActions refresh(String raw, Cookie csrf, String masked, String origin)
      throws Exception {
    return mvc.perform(
        post("/auth/refresh")
            .cookie(csrf, new Cookie("dev-refresh", raw))
            .header("Origin", origin)
            .header("X-XSRF-TOKEN", masked));
  }

  @TestComponent
  @RestController
  static class ProbeController {
    @GetMapping("/test/protected")
    ProbeResponse probe() {
      return new ProbeResponse(true);
    }
  }
}

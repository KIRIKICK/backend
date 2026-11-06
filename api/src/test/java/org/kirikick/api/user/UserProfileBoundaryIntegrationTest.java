package org.kirikick.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.LoginGrant;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.infra.JwtTokenService;
import org.kirikick.api.auth.infra.RedisRefreshSessionStore;
import org.kirikick.api.auth.repository.OAuthIdentityRepository;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.domain.UserStatus;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserProfileBoundaryIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private LoginSessionService logins;
  @MockitoSpyBean private UserRepository users;
  @MockitoSpyBean private OAuthIdentityRepository identities;
  @MockitoSpyBean private RedisRefreshSessionStore sessions;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    String db = "jdbc:h2:mem:user-profile-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    registry.add("spring.datasource.url", () -> db);
  }

  @Test
  void returnsOnlyTheAuthenticatedUsersProfileWithNoStore() throws Exception {
    User user = users.saveAndFlush(User.create("프로필회원", "https://image.example/profile.png"));
    identities.saveAndFlush(
        OAuthIdentity.create(user.getId(), OAuthProvider.KAKAO, "profile-kakao"));
    identities.saveAndFlush(
        OAuthIdentity.create(user.getId(), OAuthProvider.APPLE, "profile-apple"));
    LoginGrant grant = logins.login(user);

    MvcResult result =
        mvc.perform(
                get("/users/me/profile")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + grant.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.uuid").value(user.getUuid().toString()))
            .andExpect(jsonPath("$.displayName").value("프로필회원"))
            .andExpect(jsonPath("$.profileImageUrl").value("https://image.example/profile.png"))
            .andExpect(jsonPath("$.linkedProviders").isArray())
            .andReturn();

    JsonNode body = json.readTree(result.getResponse().getContentAsByteArray());
    assertThat(body.properties())
        .extracting(entry -> entry.getKey())
        .containsExactlyInAnyOrder("uuid", "displayName", "profileImageUrl", "linkedProviders");
    assertThat(body.path("linkedProviders"))
        .extracting(JsonNode::asText)
        .containsExactlyInAnyOrder("kakao", "apple");
    assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    assertThat(result.getRequest().getSession(false)).isNull();
    assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
  }

  @Test
  void keepsUsersAndNullableProfileImagesIsolated() throws Exception {
    User first = users.saveAndFlush(User.create("첫회원", null));
    User second = users.saveAndFlush(User.create("둘회원", "https://image.example/second.png"));
    identities.saveAndFlush(
        OAuthIdentity.create(first.getId(), OAuthProvider.KAKAO, "first-kakao"));
    identities.saveAndFlush(
        OAuthIdentity.create(second.getId(), OAuthProvider.APPLE, "second-apple"));
    LoginGrant grant = logins.login(first);

    mvc.perform(
            get("/users/me/profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + grant.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.uuid").value(first.getUuid().toString()))
        .andExpect(jsonPath("$.displayName").value("첫회원"))
        .andExpect(jsonPath("$.profileImageUrl").value(nullValue()))
        .andExpect(jsonPath("$.linkedProviders[0]").value("kakao"));
  }

  @Test
  void rejectsMissingAuthenticationAndInactiveAccounts() throws Exception {
    mvc.perform(get("/users/me/profile"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

    for (UserStatus inactive :
        List.of(UserStatus.WITHDRAWN, UserStatus.SUSPENDED, UserStatus.BANNED)) {
      User user = users.saveAndFlush(User.create("비활성회원", null));
      LoginGrant grant = logins.login(user);
      ReflectionTestUtils.setField(user, "status", inactive);
      users.saveAndFlush(user);

      mvc.perform(
              get("/users/me/profile")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + grant.accessToken()))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"))
          .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }
  }

  @Test
  void allowsAuthorizationPreflightOnlyFromTheConfiguredOrigin() throws Exception {
    mvc.perform(
            options("/users/me/profile")
                .header(HttpHeaders.ORIGIN, "http://LOCALHOST:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.AUTHORIZATION))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://LOCALHOST:5173"))
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
        .andExpect(
            header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, HttpHeaders.AUTHORIZATION));

    mvc.perform(
            options("/users/me/profile")
                .header(HttpHeaders.ORIGIN, "https://untrusted.invalid")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
        .andExpect(status().isForbidden());
  }

  @Test
  void preservesANullImageFieldAndAnEmptyProviderArray() throws Exception {
    User user = users.saveAndFlush(User.create("미연결회원", null));
    LoginGrant grant = logins.login(user);

    MvcResult result =
        mvc.perform(
                get("/users/me/profile")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + grant.accessToken()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
            .andExpect(jsonPath("$.linkedProviders").isEmpty())
            .andReturn();

    JsonNode body = json.readTree(result.getResponse().getContentAsByteArray());
    assertThat(body.has("profileImageUrl")).isTrue();
    assertThat(body.get("profileImageUrl").isNull()).isTrue();
  }

  @Test
  void rejectsInvalidExpiredAndRevokedBearerTokens() throws Exception {
    User user = users.saveAndFlush(User.create("인증검증회원", null));
    LoginGrant grant = logins.login(user);
    JwtTokenService expiredTokens =
        new JwtTokenService(
            (RSAPrivateKey) AuthTestSettings.keyPair().getPrivate(),
            (RSAPublicKey) AuthTestSettings.keyPair().getPublic(),
            Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC),
            "http://localhost:8080",
            "kirikick-test");
    String expired = expiredTokens.issue(grant.session());
    for (String token : List.of("not-a-token", expired)) {
      mvc.perform(get("/users/me/profile").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
          .andExpect(status().isUnauthorized())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
          .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
          .andExpect(jsonPath("$.message").isNotEmpty());
    }

    logins.revoke(grant);
    mvc.perform(
            get("/users/me/profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + grant.accessToken()))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void doesNotAuthenticateUsingOnlyCookiesOrAnHttpSession() throws Exception {
    User user = users.saveAndFlush(User.create("쿠키회원", null));
    LoginGrant grant = logins.login(user);

    mvc.perform(
            get("/users/me/profile")
                .cookie(new Cookie("dev-refresh", grant.refreshToken()))
                .session(new MockHttpSession()))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void mapsAMissingUserAfterAuthorizationToAccountNotFound() throws Exception {
    User user = users.saveAndFlush(User.create("삭제경합회원", null));
    LoginGrant grant = logins.login(user);
    reset(users);
    doReturn(Optional.of(user)).doReturn(Optional.empty()).when(users).findByUuid(user.getUuid());

    mvc.perform(
            get("/users/me/profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + grant.accessToken()))
        .andExpect(status().isNotFound())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

    verify(users, times(2)).findByUuid(user.getUuid());
  }

  @Test
  void mapsFilterAndBusinessStateOutagesToServiceUnavailable() throws Exception {
    User user = users.saveAndFlush(User.create("장애회원", null));
    identities.saveAndFlush(
        OAuthIdentity.create(user.getId(), OAuthProvider.KAKAO, "outage-kakao"));
    LoginGrant grant = logins.login(user);
    String authorization = "Bearer " + grant.accessToken();

    doThrow(new DomainException(AuthErrorCode.AUTH_STATE_UNAVAILABLE))
        .when(sessions)
        .findActive(grant.session().id(), grant.session().userUuid());
    mvc.perform(get("/users/me/profile").header(HttpHeaders.AUTHORIZATION, authorization))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AUTH_STATE_UNAVAILABLE"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    reset(sessions);
    reset(users);
    doThrow(new QueryTimeoutException("authorization user lookup"))
        .when(users)
        .findByUuid(user.getUuid());
    mvc.perform(get("/users/me/profile").header(HttpHeaders.AUTHORIZATION, authorization))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AUTH_STATE_UNAVAILABLE"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    verify(users).findByUuid(user.getUuid());
    reset(users);

    doReturn(Optional.of(user))
        .doThrow(new QueryTimeoutException("business user lookup"))
        .when(users)
        .findByUuid(user.getUuid());
    mvc.perform(get("/users/me/profile").header(HttpHeaders.AUTHORIZATION, authorization))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("PROFILE_UNAVAILABLE"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    verify(users, times(2)).findByUuid(user.getUuid());
    reset(users);

    doThrow(new QueryTimeoutException("provider lookup"))
        .when(identities)
        .findProvidersByUserId(user.getId());
    mvc.perform(get("/users/me/profile").header(HttpHeaders.AUTHORIZATION, authorization))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("PROFILE_UNAVAILABLE"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    verify(users, times(2)).findByUuid(user.getUuid());
    verify(identities).findProvidersByUserId(user.getId());
  }
}

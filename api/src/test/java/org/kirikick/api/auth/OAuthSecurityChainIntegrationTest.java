package org.kirikick.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.OAuthIdentity;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.repository.OAuthIdentityRepository;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.auth.support.LocalOidcProvider;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthSecurityChainIntegrationTest {
  private static final LocalOidcProvider OIDC = startProvider();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Autowired private MockMvc mvc;
  @Autowired private UserRepository users;
  @Autowired private OAuthIdentityRepository identities;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    OIDC.authProperties().forEach((name, value) -> registry.add(name, () -> value));
    registry.add("spring.datasource.url", () -> PostgresTestDatabase.container().getJdbcUrl());
    registry.add(
        "spring.datasource.username", () -> PostgresTestDatabase.container().getUsername());
    registry.add(
        "spring.datasource.password", () -> PostgresTestDatabase.container().getPassword());
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @BeforeEach
  void resetProvider() {
    OIDC.clearCapturedRequests();
  }

  @AfterAll
  static void closeProvider() {
    OIDC.close();
  }

  @Test
  void existingUserCompletesOidcLoginWithPkce() throws Exception {
    String subject = "active-" + UUID.randomUUID();
    User user = users.saveAndFlush(User.create("활성회원", null));
    identities.saveAndFlush(OAuthIdentity.create(user.getId(), OAuthProvider.KAKAO, subject));
    OIDC.nextTokenSubject(subject);

    StartedFlow flow = startFlow();
    MvcResult result =
        mvc.perform(get(flow.callback()).cookie(flow.cookie()))
            .andExpect(status().isSeeOther())
            .andReturn();

    assertThat(result.getResponse().getRedirectedUrl())
        .isEqualTo("http://localhost:5173/auth/kakao/callback?result=AUTHENTICATED");
    assertThat(result.getResponse().getCookie("dev-refresh")).isNotNull();
    assertThat(OIDC.authorization().codeChallengeMethod()).isEqualTo("S256");
    assertThat(OIDC.tokenRequest().codeVerifier()).isNotBlank();
    assertThat(OIDC.tokenRequest().clientSecretPost()).isTrue();
  }

  @Test
  void wrongStateDoesNotExchangeTheAuthorizationCode() throws Exception {
    StartedFlow flow = startFlow();
    URI wrongState =
        UriComponentsBuilder.fromUri(flow.callback())
            .replaceQueryParam("state", "wrong-state")
            .build(true)
            .toUri();
    assertFailure(
        mvc.perform(get(wrongState).cookie(flow.cookie()))
            .andExpect(status().isSeeOther())
            .andReturn());
    assertThat(OIDC.tokenRequest()).isNull();
  }

  @Test
  void wrongNonceDoesNotIssueARefreshCookie() throws Exception {
    OIDC.nextTokenFault(LocalOidcProvider.TokenFault.WRONG_NONCE);
    StartedFlow flow = startFlow();
    assertFailure(
        mvc.perform(get(flow.callback()).cookie(flow.cookie()))
            .andExpect(status().isSeeOther())
            .andReturn());
    assertThat(OIDC.tokenRequest()).isNotNull();
  }

  private StartedFlow startFlow() throws Exception {
    MvcResult start =
        mvc.perform(get("/oauth2/authorization/kakao")).andExpect(status().isFound()).andReturn();
    Cookie cookie = start.getResponse().getCookie("dev-onboarding");
    assertThat(cookie).isNotNull();
    HttpResponse<Void> authorization =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(start.getResponse().getRedirectedUrl()))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(authorization.statusCode()).isEqualTo(302);
    return new StartedFlow(
        cookie, URI.create(authorization.headers().firstValue("Location").orElseThrow()));
  }

  private void assertFailure(MvcResult result) {
    assertThat(result.getResponse().getRedirectedUrl())
        .isEqualTo("http://localhost:5173/auth/kakao/callback?result=ERROR&errorCode=OAUTH_FAILED");
    assertThat(result.getResponse().getCookie("dev-refresh")).isNull();
  }

  private static LocalOidcProvider startProvider() {
    try {
      return LocalOidcProvider.start();
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private record StartedFlow(Cookie cookie, URI callback) {}
}

package org.kirikick.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

class AuthInfrastructureConfigurationTest {

  @Test
  void registersKakaoAndCreatesPkceAuthorizationRequest() throws Exception {
    AuthProperties.Kakao kakao =
        new AuthProperties.Kakao(
            "client-id",
            "client-secret",
            "https://api.example/login/oauth2/code/kakao",
            "https://kauth.kakao.com",
            "https://kauth.kakao.com/oauth/authorize",
            "https://kauth.kakao.com/oauth/token",
            "https://kauth.kakao.com/.well-known/jwks.json");
    AuthProperties properties =
        new AuthProperties(null, null, null, List.of(), false, false, null, kakao);
    AuthInfrastructureConfiguration configuration = new AuthInfrastructureConfiguration();
    ClientRegistrationRepository registrations =
        configuration.clientRegistrationRepository(properties);
    ClientRegistration registration = registrations.findByRegistrationId("kakao");

    assertThat(registration.getClientId()).isEqualTo("client-id");
    assertThat(registration.getClientAuthenticationMethod())
        .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
    assertThat(registration.getScopes()).contains("openid");

    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/oauth2/authorization/kakao");
    request.setServletPath("/oauth2/authorization/kakao");
    OAuth2AuthorizationRequest authorization =
        configuration.oauthAuthorizationRequestResolver(registrations).resolve(request);

    assertThat(authorization).isNotNull();
    String verifier = (String) authorization.getAttributes().get("code_verifier");
    assertThat(verifier).isNotBlank();
    String challenge =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    assertThat(authorization.getAdditionalParameters())
        .containsEntry("code_challenge_method", "S256")
        .containsEntry("code_challenge", challenge);
  }
}

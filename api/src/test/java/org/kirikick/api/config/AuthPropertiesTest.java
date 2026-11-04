package org.kirikick.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;

class AuthPropertiesTest {

  @Test
  void rejectsMissingSigningAndProviderConfiguration() {
    new ApplicationContextRunner()
        .withUserConfiguration(Binding.class)
        .run(context -> assertThat(context.getStartupFailure()).isNotNull());
  }

  @Test
  void acceptsSeparateNamespacesAndAnExactOrigin() {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(
              factory
                  .getValidator()
                  .validate(properties("test:auth", "test:spring:session", "https://app.example")))
          .isEmpty();
    }
  }

  @Test
  void rejectsOverlappingNamespacesAndOriginsWithPathsOrCredentials() {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      assertThat(
              validator.validate(
                  properties("test:auth", "test:auth:session", "https://app.example")))
          .isNotEmpty();
      for (String origin :
          new String[] {
            "https://app.example/evil",
            "https://user@app.example",
            "https://app.example?x=1",
            "file:///app"
          }) {
        assertThat(validator.validate(properties("test:auth", "test:session", origin)))
            .as(origin)
            .isNotEmpty();
      }
    }
  }

  @Test
  void providerSettingsNeverPrintTheClientSecret() {
    assertThat(properties("test:auth", "test:session", "http://localhost:5173").toString())
        .doesNotContain("test-client-secret");
  }

  private AuthProperties properties(String authNamespace, String sessionNamespace, String origin) {
    var key = new ByteArrayResource(new byte[0]);
    return new AuthProperties(
        authNamespace,
        sessionNamespace,
        origin,
        false,
        new AuthProperties.Jwt("https://api.example", "kirikick", key, key),
        new AuthProperties.Kakao(
            "test-client",
            "test-client-secret",
            "http://localhost/login/oauth2/code/kakao",
            "https://kauth.kakao.com",
            "https://kauth.kakao.com/oauth/authorize",
            "https://kauth.kakao.com/oauth/token",
            "https://kauth.kakao.com/.well-known/jwks.json"));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AuthProperties.class)
  static class Binding {}
}

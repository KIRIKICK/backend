package org.kirikick.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ByteArrayResource;

class AuthPropertiesTest {

  @Test
  void rejectsMissingSigningAndProviderConfiguration() {
    new ApplicationContextRunner()
        .withUserConfiguration(Binding.class)
        .run(context -> assertThat(context.getStartupFailure()).isNotNull());
  }

  @Test
  void bindsAnEmptyAdditionalOriginListWhenTheEnvironmentVariableIsAbsent() {
    environmentBinding(Map.of())
        .run(context -> {
          assertThat(context).hasNotFailed();
          AuthProperties properties = context.getBean(AuthProperties.class);
          assertThat(properties.devAdditionalFeOrigins()).isEmpty();
          assertThat(properties.feOrigin()).isEqualTo("https://app.example");
        });
  }

  @Test
  void bindsCommaSeparatedAdditionalOriginsWithoutChangingTheDefaultOrigin() {
    environmentBinding(
            Map.of(
                "AUTH_DEV_ADDITIONAL_FE_ORIGINS", "http://localhost:5173,https://preview.example"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          AuthProperties properties = context.getBean(AuthProperties.class);
          assertThat(properties.devAdditionalFeOrigins())
              .containsExactly("http://localhost:5173", "https://preview.example");
          assertThat(properties.feOrigin()).isEqualTo("https://app.example");
          // Direct environment binding must not hide a missing YAML placeholder.
          assertThat(context.getEnvironment().getPropertySources())
              .filteredOn(source -> source.getName().contains("application.yaml"))
              .extracting(source -> source.getProperty("auth.dev-additional-fe-origins"))
              .contains("${AUTH_DEV_ADDITIONAL_FE_ORIGINS:}");
        });
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

  private ApplicationContextRunner environmentBinding(Map<String, Object> overrides) {
    Map<String, Object> variables =
        new HashMap<>(
            Map.of(
                "REDIS_NAMESPACE", "kirikick:dev",
                "AUTH_FE_ORIGIN", "https://app.example",
                "AUTH_JWT_ISSUER", "https://api.example",
                "AUTH_JWT_AUDIENCE", "kirikick",
                "AUTH_JWT_PRIVATE_KEY_LOCATION", "classpath:unused-private.pem",
                "AUTH_JWT_PUBLIC_KEY_LOCATION", "classpath:unused-public.pem",
                "KAKAO_CLIENT_ID", "test-client",
                "KAKAO_CLIENT_SECRET", "test-client-secret",
                "KAKAO_REDIRECT_URI", "https://api.example/login/oauth2/code/kakao"));
    variables.putAll(overrides);
    return new ApplicationContextRunner()
        .withPropertyValues("spring.profiles.active=dev", "spring.config.import=")
        .withInitializer(context -> {
          context
              .getEnvironment()
              .getPropertySources()
              .replace(
                  SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                  new SystemEnvironmentPropertySource(
                      SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
          new ConfigDataApplicationContextInitializer().initialize(context);
        })
        .withUserConfiguration(Binding.class);
  }

  private AuthProperties properties(String authNamespace, String sessionNamespace, String origin) {
    var key = new ByteArrayResource(new byte[0]);
    return new AuthProperties(
        authNamespace,
        sessionNamespace,
        origin,
        List.of(),
        false,
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

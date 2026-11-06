package org.kirikick.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisNamespaceConfigurationTest {

  @ParameterizedTest
  @ValueSource(strings = {"local", "dev", "prod"})
  void separatesNamespacesWithAnOperatingSystemEnvironmentVariable(String profile) {
    configuration(profile, Map.of("REDIS_NAMESPACE", "kirikick:" + profile))
        .run(context -> {
          assertThat(context).hasNotFailed();
          AuthProperties properties = context.getBean(AuthProperties.class);
          assertThat(properties.redisNamespace()).isEqualTo("kirikick:" + profile + ":auth");
          assertThat(properties.sessionRedisNamespace())
              .isEqualTo("kirikick:" + profile + ":spring:session");
          assertThat(properties.isNamespacesIsolated()).isTrue();
          assertThat(context.getEnvironment().getProperty("spring.session.redis.namespace"))
              .isEqualTo(properties.sessionRedisNamespace());
        });
  }

  @Test
  void keepsLocalDefaultNamespacesWithoutAnEnvironmentOverride() {
    configuration("local", Map.of())
        .run(context -> {
          assertThat(context).hasNotFailed();
          AuthProperties properties = context.getBean(AuthProperties.class);
          assertThat(properties.redisNamespace()).isEqualTo("kirikick:local:auth");
          assertThat(properties.sessionRedisNamespace()).isEqualTo("kirikick:local:spring:session");
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "prod"})
  void rejectsARefreshStoreWithoutAnExplicitDeploymentNamespace(String profile) {
    configuration(profile, Map.of())
        .run(context -> {
          assertThat(context).hasNotFailed();
          AuthProperties properties = context.getBean(AuthProperties.class);
          assertThatThrownBy(() ->
                  new AuthInfrastructureConfiguration()
                      .redisRefreshSessionStore(
                          mock(StringRedisTemplate.class),
                          properties,
                          Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                          context.getEnvironment()))
              .isInstanceOf(IllegalStateException.class)
              .hasMessage("배포 프로필에는 Secure 쿠키와 환경별 Redis namespace가 필요합니다.");
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "prod"})
  void rejectsAnOverlappingLegacyEnvironmentOverride(String profile) {
    configuration(
            profile,
            Map.of(
                "REDIS_NAMESPACE", "kirikick:" + profile,
                "AUTH_REDIS_NAMESPACE", "kirikick:" + profile))
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasStackTraceContaining("인증과 임시 세션의 Redis namespace는 서로 겹칠 수 없습니다.");
        });
  }

  private ApplicationContextRunner configuration(
      String profile, Map<String, Object> namespaceVariables) {
    Map<String, Object> variables =
        new HashMap<>(
            Map.of(
                "AUTH_FE_ORIGIN", "https://app.example",
                "AUTH_JWT_ISSUER", "https://api.example",
                "AUTH_JWT_AUDIENCE", "kirikick",
                "AUTH_JWT_PRIVATE_KEY_LOCATION", "classpath:unused-private.pem",
                "AUTH_JWT_PUBLIC_KEY_LOCATION", "classpath:unused-public.pem",
                "KAKAO_CLIENT_ID", "test-client",
                "KAKAO_CLIENT_SECRET", "test-client-secret",
                "KAKAO_REDIRECT_URI", "https://api.example/login/oauth2/code/kakao"));
    variables.putAll(namespaceVariables);
    return new ApplicationContextRunner()
        .withPropertyValues("spring.profiles.active=" + profile, "spring.config.import=")
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

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AuthProperties.class)
  static class Binding {}
}

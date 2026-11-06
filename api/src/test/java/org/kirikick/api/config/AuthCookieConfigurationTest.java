package org.kirikick.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;

import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.auth.infra.RedisRefreshSessionStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer.CookieValue;

class AuthCookieConfigurationTest {

  @ParameterizedTest
  @ValueSource(strings = {"local", "dev", "prod"})
  void defaultsToExistingCookiePolicies(String profile) {
    configuration(profile, Map.of())
        .run(context -> {
          assertThat(context).hasNotFailed();
          AuthProperties properties = context.getBean(AuthProperties.class);
          assertThat(properties.crossSiteCookies()).isFalse();
          assertThat(properties.secureCookies()).isEqualTo(!profile.equals("local"));
          MockHttpServletResponse response = new MockHttpServletResponse();
          new AuthSecurityConfiguration().authHttpSupport(properties).clearRefresh(response);
          String name = properties.secureCookies() ? "__Host-refresh" : "dev-refresh";
          assertCookie(response.getCookie(name), properties.secureCookies(), "Strict");
          assertCookie(onboardingCookie(properties, -1), properties.secureCookies(), "Lax");
        });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void bindsExplicitOptInFromAnOperatingSystemEnvironmentVariable(boolean enabled) {
    configuration("dev", Map.of("AUTH_CROSS_SITE_COOKIES", Boolean.toString(enabled)))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(AuthProperties.class).crossSiteCookies()).isEqualTo(enabled);
        });
  }

  @Test
  void optInAlsoUsesNoneAndSecureForCookieDeletion() {
    configuration("dev", Map.of("AUTH_CROSS_SITE_COOKIES", "true"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          AuthProperties properties = context.getBean(AuthProperties.class);
          MockHttpServletResponse response = new MockHttpServletResponse();
          new AuthSecurityConfiguration().authHttpSupport(properties).clearRefresh(response);
          Cookie refresh = response.getCookie("__Host-refresh");
          assertCookie(refresh, true, "None");
          assertThat(refresh.getMaxAge()).isZero();
          Cookie onboarding = onboardingCookie(properties, 0);
          assertCookie(onboarding, true, "None");
          assertThat(onboarding.getMaxAge()).isZero();
        });
  }

  @Test
  void rejectsCrossSiteCookiesWithoutSecure() {
    configuration("dev", Map.of("AUTH_CROSS_SITE_COOKIES", "true", "AUTH_SECURE_COOKIES", "false"))
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasStackTraceContaining("cross-site 쿠키에는 Secure 설정이 필요합니다.");
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"local", "prod", "test", "dev,prod", "dev,local"})
  void rejectsOptInOutsideAnExclusivelyDevDeployment(String profiles) {
    configuration(
            profiles, Map.of("AUTH_CROSS_SITE_COOKIES", "true", "AUTH_SECURE_COOKIES", "true"))
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasStackTraceContaining("cross-site 쿠키는 dev 프로필에서만 사용할 수 있습니다.");
        });
  }

  private static Cookie onboardingCookie(AuthProperties properties, int maxAge) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSecure(properties.secureCookies());
    MockHttpServletResponse response = new MockHttpServletResponse();
    CookieValue value = new CookieValue(request, response, "test-session");
    value.setCookieMaxAge(maxAge);
    new AuthInfrastructureConfiguration()
        .onboardingCookieSerializer(properties)
        .writeCookieValue(value);
    return response.getCookie(properties.secureCookies() ? "__Host-onboarding" : "dev-onboarding");
  }

  private static void assertCookie(Cookie cookie, boolean secure, String sameSite) {
    assertThat(cookie).isNotNull();
    assertThat(cookie.getSecure()).isEqualTo(secure);
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.getPath()).isEqualTo("/");
    assertThat(cookie.getDomain()).isNull();
    assertThat(cookie.getAttribute("SameSite")).isEqualTo(sameSite);
  }

  private ApplicationContextRunner configuration(String profiles, Map<String, Object> options) {
    Map<String, Object> variables =
        new HashMap<>(
            Map.of(
                "REDIS_NAMESPACE", "kirikick:" + profiles.split(",")[0],
                "AUTH_FE_ORIGIN", "https://app.example",
                "AUTH_JWT_ISSUER", "https://api.example",
                "AUTH_JWT_AUDIENCE", "kirikick",
                "AUTH_JWT_PRIVATE_KEY_LOCATION", "classpath:unused-private.pem",
                "AUTH_JWT_PUBLIC_KEY_LOCATION", "classpath:unused-public.pem",
                "KAKAO_CLIENT_ID", "test-client",
                "KAKAO_CLIENT_SECRET", "test-client-secret",
                "KAKAO_REDIRECT_URI", "https://api.example/login/oauth2/code/kakao"));
    variables.putAll(options);
    return new ApplicationContextRunner()
        .withPropertyValues("spring.profiles.active=" + profiles, "spring.config.import=")
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
  static class Binding {
    @Bean
    RedisRefreshSessionStore refreshStore(AuthProperties properties, Environment environment) {
      return new AuthInfrastructureConfiguration()
          .redisRefreshSessionStore(
              mock(StringRedisTemplate.class),
              properties,
              Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
              environment);
    }
  }
}

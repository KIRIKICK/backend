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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.auth.infra.AuthFrontendPolicy;
import org.kirikick.api.auth.infra.RedisRefreshSessionStore;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
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
  void defaultsToOnlyTheRequiredOriginWhenAdditionalOriginsAreUnset() {
    lazyConfiguration("dev,test", Map.of())
        .run(context -> {
          assertThat(context).hasNotFailed();
          AuthFrontendPolicy policy = context.getBean(AuthFrontendPolicy.class);
          assertThat(policy.allowedOrigins()).containsExactly("https://app.example");
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"local", "dev", "prod"})
  void keepsTheDefaultOnlyPolicyForExistingDeploymentProfiles(String profile) {
    lazyConfiguration(profile, Map.of())
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(AuthFrontendPolicy.class).allowedOrigins())
              .containsExactly("https://app.example");
        });
  }

  @Test
  void treatsAnEmptyAdditionalOriginEnvironmentVariableAsNoAdditionalOrigins() {
    lazyConfiguration("dev,test", Map.of("AUTH_DEV_ADDITIONAL_FE_ORIGINS", ""))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(AuthFrontendPolicy.class).allowedOrigins())
              .containsExactly("https://app.example");
        });
  }

  @Test
  void bindsAndCanonicalizesAdditionalOriginsFromAnOperatingSystemEnvironmentVariable() {
    lazyConfiguration(
            "dev,test",
            Map.of(
                "AUTH_DEV_ADDITIONAL_FE_ORIGINS",
                "https://app.example:443,https://LOCALHOST:443,http://localhost:5173"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(AuthFrontendPolicy.class).allowedOrigins())
              .containsExactlyInAnyOrder(
                  "https://app.example", "https://localhost", "http://localhost:5173");
        });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://app.example/",
        "https://user@app.example",
        "https://app.example?query=true",
        "https://app.example#fragment",
        "https://app.example/path",
        "https://app.example:",
        "https://app.example:65536",
        "https://app.example\r\ninvalid",
        "https://*.example",
        "file:///app"
      })
  void rejectsAnInvalidDevAdditionalOriginAtStartup(String origin) {
    lazyConfiguration("dev", Map.of("AUTH_DEV_ADDITIONAL_FE_ORIGINS", origin))
        .run(context -> assertThat(context).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://app.example:", "https://app.example:65536"})
  void rejectsInvalidDefaultOriginDuringBindingInEagerAndLazyContexts(String origin) {
    for (ApplicationContextRunner runner :
        new ApplicationContextRunner[] {
          configuration("dev", Map.of("AUTH_FE_ORIGIN", origin)),
          lazyConfiguration("dev", Map.of("AUTH_FE_ORIGIN", origin))
        }) {
      runner.run(context -> {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure())
            .hasStackTraceContaining("FE 주소는 경로·인증정보·쿼리가 없는 HTTP(S) origin이어야 합니다.")
            .hasStackTraceContaining("feOriginValid");
      });
    }
  }

  @Test
  void eagerlyCreatesPolicyWithoutNormalizingTheConfiguredDefaultOrigin() {
    assertPolicyEagerlyCreated(
        configuration("dev", Map.of("AUTH_FE_ORIGIN", "https://App.example:443")));
  }

  @Test
  void eagerlyCreatesPolicyWithLazyInitializationWithoutNormalizingTheConfiguredDefaultOrigin() {
    assertPolicyEagerlyCreated(
        lazyConfiguration("dev", Map.of("AUTH_FE_ORIGIN", "https://App.example:443")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://app.example:", "https://app.example:65536"})
  void rejectsMalformedAdditionalOriginAtPolicyCreation(String origin) {
    lazyConfiguration("dev", Map.of("AUTH_DEV_ADDITIONAL_FE_ORIGINS", origin))
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).hasStackTraceContaining("not an origin");
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"local", "prod", "test", "dev,prod", "dev,local"})
  void rejectsAdditionalOriginsOutsideAnExclusivelyDevDeployment(String profiles) {
    lazyConfiguration(profiles, Map.of("AUTH_DEV_ADDITIONAL_FE_ORIGINS", "http://localhost:5173"))
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasStackTraceContaining("추가 FE Origin은 dev 프로필에서만 사용할 수 있습니다.");
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

  @ParameterizedTest
  @ValueSource(strings = {"local", "prod", "test", "dev,prod", "dev,local"})
  void rejectsOptInOutsideDevEvenWithLazyInitialization(String profiles) {
    lazyConfiguration(
            profiles, Map.of("AUTH_CROSS_SITE_COOKIES", "true", "AUTH_SECURE_COOKIES", "true"))
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasStackTraceContaining("cross-site 쿠키는 dev 프로필에서만 사용할 수 있습니다.");
        });
  }

  @Test
  void rejectsInsecureOptInEvenWithLazyInitialization() {
    lazyConfiguration(
            "dev", Map.of("AUTH_CROSS_SITE_COOKIES", "true", "AUTH_SECURE_COOKIES", "false"))
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasStackTraceContaining("cross-site 쿠키에는 Secure 설정이 필요합니다.");
        });
  }

  @ParameterizedTest
  @CsvSource({"local,false", "dev,false", "dev,true", "prod,false"})
  void validatesCookiePoliciesAtStartupWithLazyInitialization(
      String profiles, boolean crossSiteCookies) {
    lazyConfiguration(
            profiles, Map.of("AUTH_CROSS_SITE_COOKIES", Boolean.toString(crossSiteCookies)))
        .run(context -> {
          assertThat(context).hasNotFailed();
          ConfigurableListableBeanFactory beans =
              context.getSourceApplicationContext().getBeanFactory();
          assertThat(beans.containsSingleton("redisRefreshSessionStore")).isTrue();
          assertThat(beans.containsSingleton("jwtTokenService")).isFalse();
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

  private static void assertPolicyEagerlyCreated(ApplicationContextRunner runner) {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      ConfigurableListableBeanFactory beans =
          context.getSourceApplicationContext().getBeanFactory();
      assertThat(beans.containsSingleton("authFrontendPolicy")).isTrue();
      assertThat(context.getBean(AuthProperties.class).feOrigin())
          .isEqualTo("https://App.example:443");
      AuthFrontendPolicy policy = context.getBean(AuthFrontendPolicy.class);
      assertThat(policy.defaultOrigin()).isEqualTo("https://App.example:443");
      assertThat(policy.allowedOrigins()).containsExactly("https://app.example");
    });
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
    return configuration(profiles, options, Binding.class);
  }

  private ApplicationContextRunner lazyConfiguration(String profiles, Map<String, Object> options) {
    return configuration(profiles, options, AuthInfrastructureConfiguration.class)
        .withPropertyValues("spring.main.lazy-initialization=true")
        // ApplicationContextRunner는 SpringApplication의 지연 초기화 후처리기를 자동 등록하지 않는다.
        .withInitializer(context ->
            context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
        .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));
  }

  private ApplicationContextRunner configuration(
      String profiles, Map<String, Object> options, Class<?> configuration) {
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
        .withUserConfiguration(configuration);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AuthProperties.class)
  static class Binding {
    @Bean
    AuthFrontendPolicy authFrontendPolicy(AuthProperties properties, Environment environment) {
      return new AuthInfrastructureConfiguration().authFrontendPolicy(properties, environment);
    }

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

package org.kirikick.api.config;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import org.kirikick.api.auth.infra.JwtTokenService;
import org.kirikick.api.auth.infra.RedisFlowClaimGuard;
import org.kirikick.api.auth.infra.RedisRefreshSessionStore;
import org.kirikick.api.auth.oauth.OAuthFlowAuthorizationRequestRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class AuthInfrastructureConfiguration {

  @Bean
  ClientRegistrationRepository clientRegistrationRepository(AuthProperties properties) {
    AuthProperties.Kakao kakao = properties.kakao();
    ClientRegistration registration =
        ClientRegistration.withRegistrationId("kakao")
            .clientId(kakao.clientId())
            .clientSecret(kakao.clientSecret())
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(kakao.redirectUri())
            .scope("openid")
            .authorizationUri(kakao.authorizationUri())
            .tokenUri(kakao.tokenUri())
            .jwkSetUri(kakao.jwkSetUri())
            .issuerUri(kakao.issuerUri())
            .userNameAttributeName("sub")
            .clientName("Kakao")
            .build();

    return new InMemoryClientRegistrationRepository(registration);
  }

  @Bean
  AuthorizationRequestRepository<OAuth2AuthorizationRequest> oauthAuthorizationRequestRepository(
      Clock clock, RedisFlowClaimGuard claims) {
    return new OAuthFlowAuthorizationRequestRepository(clock, claims);
  }

  @Bean
  OAuth2AuthorizationRequestResolver oauthAuthorizationRequestResolver(
      ClientRegistrationRepository clientRegistrations) {
    DefaultOAuth2AuthorizationRequestResolver resolver =
        new DefaultOAuth2AuthorizationRequestResolver(clientRegistrations, "/oauth2/authorization");
    resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
    return resolver;
  }

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock authClock() {
    return Clock.systemUTC();
  }

  /** 쿠키·배포 설정 오류가 첫 요청까지 숨지 않도록 기동 시 검증한다. */
  @Bean
  @Lazy(false)
  RedisRefreshSessionStore redisRefreshSessionStore(
      StringRedisTemplate redis, AuthProperties properties, Clock clock, Environment environment) {
    if (properties.crossSiteCookies()
        && (!environment.acceptsProfiles(Profiles.of("dev"))
            || environment.acceptsProfiles(Profiles.of("local", "prod")))) {
      throw new IllegalStateException("cross-site 쿠키는 dev 프로필에서만 사용할 수 있습니다.");
    }
    for (String profile : new String[] {"dev", "prod"}) {
      if (environment.acceptsProfiles(Profiles.of(profile))
          && (!properties.secureCookies()
              || !properties.redisNamespace().contains(":" + profile + ":")
              || !properties.sessionRedisNamespace().contains(":" + profile + ":"))) {
        throw new IllegalStateException("배포 프로필에는 Secure 쿠키와 환경별 Redis namespace가 필요합니다.");
      }
    }
    return new RedisRefreshSessionStore(redis, properties.redisNamespace(), clock);
  }

  @Bean
  JwtTokenService jwtTokenService(AuthProperties properties, Clock clock) throws IOException {
    try (InputStream privatePem = properties.jwt().privateKeyLocation().getInputStream();
        InputStream publicPem = properties.jwt().publicKeyLocation().getInputStream()) {
      RSAPrivateKey privateKey = RsaKeyConverters.pkcs8().convert(privatePem);
      RSAPublicKey publicKey = RsaKeyConverters.x509().convert(publicPem);
      return new JwtTokenService(
          privateKey, publicKey, clock, properties.jwt().issuer(), properties.jwt().audience());
    }
  }

  @Bean
  JwtDecoder jwtDecoder(JwtTokenService tokens) {
    return tokens.decoder();
  }

  @Bean
  RedisFlowClaimGuard redisFlowClaimGuard(StringRedisTemplate redis, AuthProperties properties) {
    return new RedisFlowClaimGuard(redis, properties.redisNamespace());
  }

  @Bean
  CookieSerializer onboardingCookieSerializer(AuthProperties properties) {
    DefaultCookieSerializer cookie = new DefaultCookieSerializer();
    cookie.setCookieName(properties.secureCookies() ? "__Host-onboarding" : "dev-onboarding");
    cookie.setUseSecureCookie(properties.secureCookies());
    cookie.setUseHttpOnlyCookie(true);
    cookie.setCookiePath("/");
    cookie.setSameSite(properties.crossSiteCookies() ? "None" : "Lax");
    return cookie;
  }
}

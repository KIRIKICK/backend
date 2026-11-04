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
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class AuthInfrastructureConfiguration {
  @Bean
  AuthorizationRequestRepository<OAuth2AuthorizationRequest> oauthAuthorizationRequestRepository(
      Clock clock, RedisFlowClaimGuard claims) {
    return new OAuthFlowAuthorizationRequestRepository(clock, claims);
  }

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock authClock() {
    return Clock.systemUTC();
  }

  @Bean
  RedisRefreshSessionStore redisRefreshSessionStore(
      StringRedisTemplate redis, AuthProperties properties, Clock clock, Environment environment) {
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
    cookie.setSameSite("Lax");
    return cookie;
  }
}

package org.kirikick.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.kirikick.api.auth.controller.AuthHttpSupport;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.infra.JwtTokenService;
import org.kirikick.api.auth.infra.OriginValidationFilter;
import org.kirikick.api.auth.infra.RedisFlowClaimGuard;
import org.kirikick.api.auth.infra.RedisRefreshSessionStore;
import org.kirikick.api.auth.infra.SessionAuthorizationFilter;
import org.kirikick.api.auth.infra.SessionStateUnavailableException;
import org.kirikick.api.auth.oauth.NoopOAuth2AuthorizedClientRepository;
import org.kirikick.api.auth.oauth.OAuthLoginFailureHandler;
import org.kirikick.api.auth.oauth.OAuthLoginSuccessHandler;
import org.kirikick.api.auth.repository.OAuthIdentityRepository;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.service.SignupApplicationPublicationService;
import org.kirikick.api.common.controller.dto.ErrorResponse;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class AuthSecurityConfiguration {

  @Bean
  LoginSessionService loginSessionService(
      RedisRefreshSessionStore sessions, JwtTokenService jwtTokens, UserRepository users) {
    return new LoginSessionService(sessions, jwtTokens, users);
  }

  @Bean
  AuthHttpSupport authHttpSupport(AuthProperties properties) {
    return new AuthHttpSupport(properties.secureCookies());
  }

  @Bean
  OAuthLoginSuccessHandler oauthLoginSuccessHandler(
      OAuthIdentityRepository identities,
      UserRepository users,
      LoginSessionService sessions,
      SignupApplicationPublicationService publications,
      AuthHttpSupport http,
      ObjectMapper objectMapper,
      RedisFlowClaimGuard guard,
      Clock clock,
      AuthProperties properties) {
    return new OAuthLoginSuccessHandler(
        identities,
        users,
        sessions,
        publications,
        http,
        objectMapper,
        guard,
        clock,
        properties.feOrigin());
  }

  @Bean
  OAuthLoginFailureHandler oauthLoginFailureHandler(
      RedisFlowClaimGuard guard, Clock clock, AuthProperties properties) {
    return new OAuthLoginFailureHandler(guard, clock, properties.feOrigin());
  }

  private static ObjectPostProcessor<OAuth2AuthorizationRequestRedirectFilter>
      oauthStartFailurePostProcessor(AuthProperties properties) {
    return new ObjectPostProcessor<>() {
      @Override
      public <O extends OAuth2AuthorizationRequestRedirectFilter> O postProcess(O filter) {
        filter.setAuthenticationFailureHandler((request, response, failure) -> {
          Throwable cause = failure;
          while (cause != null) {
            if (cause instanceof SessionStateUnavailableException unavailable) {
              throw unavailable;
            }
            cause = cause.getCause();
          }
          response.setStatus(HttpServletResponse.SC_SEE_OTHER);
          response.setHeader("Cache-Control", "no-store");
          response.setHeader(
              "Location",
              properties.feOrigin() + "/auth/kakao/callback?result=ERROR&errorCode=OAUTH_FAILED");
        });
        return filter;
      }
    };
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtDecoder jwtDecoder,
      LoginSessionService sessions,
      AuthProperties properties,
      Environment environment,
      AuthorizationRequestRepository<OAuth2AuthorizationRequest>
          oauthAuthorizationRequestRepository,
      OAuth2AuthorizationRequestResolver oauthAuthorizationRequestResolver,
      OAuthLoginSuccessHandler oauthLoginSuccessHandler,
      OAuthLoginFailureHandler oauthLoginFailureHandler,
      ObjectMapper objectMapper)
      throws Exception {
    boolean documentationEnabled =
        !environment.acceptsProfiles(Profiles.of("prod"))
            && environment.acceptsProfiles(Profiles.of("local", "dev"));
    NullSecurityContextRepository noSecurityContext = new NullSecurityContextRepository();
    CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrf.setCookieHttpOnly(true);
    csrf.setCookieName(properties.secureCookies() ? "__Host-csrf" : "dev-csrf");
    csrf.setHeaderName("X-XSRF-TOKEN");
    csrf.setCookiePath("/");
    csrf.setCookieCustomizer(cookie ->
        cookie.httpOnly(true).secure(properties.secureCookies()).sameSite("Strict").path("/"));
    CorsConfiguration cors =
        new CorsConfiguration() {
          @Override
          public String checkOrigin(String requestOrigin) {
            String actual = OriginValidationFilter.canonicalOriginOrNull(requestOrigin);
            return OriginValidationFilter.canonicalOrigin(properties.feOrigin()).equals(actual)
                ? requestOrigin
                : null;
          }
        };
    cors.setAllowCredentials(true);
    cors.addAllowedOrigin(properties.feOrigin());
    cors.addAllowedHeader("Content-Type");
    cors.addAllowedHeader("X-XSRF-TOKEN");
    cors.addAllowedHeader("Authorization");
    cors.addAllowedMethod("GET");
    cors.addAllowedMethod("POST");
    UrlBasedCorsConfigurationSource corsSource = new UrlBasedCorsConfigurationSource();
    corsSource.registerCorsConfiguration("/**", cors);

    http.securityContext(context -> context.securityContextRepository(noSecurityContext))
        .requestCache(cache -> cache.requestCache(new NullRequestCache()))
        .cors(corsConfig -> corsConfig.configurationSource(corsSource))
        .csrf(csrfConfig ->
            csrfConfig
                .csrfTokenRepository(csrf)
                .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler()))
        .authorizeHttpRequests(authorize -> {
          var documentation =
              authorize.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html");
          if (documentationEnabled) {
            documentation.permitAll();
          } else {
            documentation.denyAll();
          }
          authorize
              .requestMatchers(
                  "/actuator/health/**",
                  "/auth/csrf",
                  "/auth/refresh",
                  "/oauth2/**",
                  "/login/oauth2/**",
                  "/auth/signup/**")
              .permitAll()
              .anyRequest()
              .authenticated();
        })
        .oauth2ResourceServer(resource ->
            resource
                .authenticationEntryPoint((request, response, error) ->
                    writeError(
                        response,
                        objectMapper,
                        HttpStatus.UNAUTHORIZED,
                        AuthErrorCode.AUTHENTICATION_REQUIRED))
                .jwt(jwt -> jwt.decoder(jwtDecoder)))
        .oauth2Login(oauth ->
            oauth
                .withObjectPostProcessor(oauthStartFailurePostProcessor(properties))
                .authorizedClientRepository(new NoopOAuth2AuthorizedClientRepository())
                .authorizationEndpoint(endpoint ->
                    endpoint
                        .authorizationRequestRepository(oauthAuthorizationRequestRepository)
                        .authorizationRequestResolver(oauthAuthorizationRequestResolver))
                .successHandler(oauthLoginSuccessHandler)
                .failureHandler(oauthLoginFailureHandler))
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .logout(logout -> logout.disable())
        .sessionManagement(
            // OAuth slot lifecycle performs verified fresh-session transitions; framework rotation
            // breaks flow binding.
            session ->
                session
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionFixation(fixation -> fixation.none()))
        .exceptionHandling(exception ->
            exception
                .authenticationEntryPoint((request, response, error) ->
                    writeError(
                        response,
                        objectMapper,
                        HttpStatus.UNAUTHORIZED,
                        AuthErrorCode.AUTHENTICATION_REQUIRED))
                .accessDeniedHandler((request, response, error) ->
                    writeError(
                        response,
                        objectMapper,
                        HttpStatus.FORBIDDEN,
                        AuthErrorCode.AUTHENTICATION_REQUIRED)))
        .addFilterBefore(
            new OriginValidationFilter(properties.feOrigin(), objectMapper),
            UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(
            new SessionAuthorizationFilter(sessions, objectMapper),
            BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  private static void writeError(
      HttpServletResponse response,
      ObjectMapper objectMapper,
      HttpStatus status,
      AuthErrorCode code)
      throws IOException {
    AuthHttpSupport.writeError(
        response, objectMapper, status.value(), new ErrorResponse(code.name(), code.getMessage()));
  }
}

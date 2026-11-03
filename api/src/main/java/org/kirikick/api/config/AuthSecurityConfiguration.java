package org.kirikick.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.kirikick.api.auth.controller.AuthHttpSupport;
import org.kirikick.api.auth.controller.dto.AuthErrorResponse;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.infra.JwtTokenService;
import org.kirikick.api.auth.infra.OriginValidationFilter;
import org.kirikick.api.auth.infra.RedisRefreshSessionStore;
import org.kirikick.api.auth.infra.SessionAuthorizationFilter;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
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
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtDecoder jwtDecoder,
      LoginSessionService sessions,
      AuthProperties properties,
      ObjectMapper objectMapper)
      throws Exception {
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
        .authorizeHttpRequests(authorize ->
            authorize
                .requestMatchers("/actuator/health/**", "/auth/csrf", "/auth/refresh")
                .permitAll()
                .anyRequest()
                .authenticated())
        .oauth2ResourceServer(resource ->
            resource
                .authenticationEntryPoint((request, response, error) ->
                    writeError(
                        response,
                        objectMapper,
                        HttpStatus.UNAUTHORIZED,
                        AuthErrorCode.AUTHENTICATION_REQUIRED))
                .jwt(jwt -> jwt.decoder(jwtDecoder)))
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .logout(logout -> logout.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
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
      throws java.io.IOException {
    response.setStatus(status.value());
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    objectMapper.writeValue(
        response.getWriter(), new AuthErrorResponse(code.name(), code.getMessage()));
  }
}

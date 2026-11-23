package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.ApiApplication;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.publication.service.OpenRoomService;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class OpenRoomRegistrationPostgresTest {
  @ParameterizedTest(name = "profile={0}")
  @ValueSource(strings = {"default", "local", "dev"})
  @DisplayName("S-06: 기본·로컬·DEV 기동은 Grace가 없으면 출제 경로를 등록하지 않는다")
  void leavesTheExistingApplicationUnchangedWithoutGrace(String profile) {
    try (ConfigurableApplicationContext context = start(profile, null)) {
      assertThat(context.getBeansOfType(OpenRoomService.class)).isEmpty();
      assertThat(hasPublicationRoute(context)).isFalse();
    }
  }

  @ParameterizedTest(name = "profile={0}")
  @ValueSource(strings = {"local", "dev"})
  @DisplayName("S-06: 로컬·DEV의 명시적 유효 시험 Grace에만 출제 경로가 등록된다")
  void registersOnlyWithExplicitValidFixtureGrace(String profile) {
    try (ConfigurableApplicationContext context = start(profile, "PT5M")) {
      assertThat(context.getBeansOfType(OpenRoomService.class)).hasSize(1);
      assertThat(hasPublicationRoute(context)).isTrue();
    }
  }

  @ParameterizedTest(name = "grace={0}")
  @ValueSource(strings = {"not-a-duration", "PT-1S", "PT0.000000001S"})
  @DisplayName("S-06: 명시적 무효 Grace는 실제 앱 기동에 실패한다")
  void refusesInvalidGraceAtApplicationStartup(String grace) {
    assertThatThrownBy(
            () -> {
              try (ConfigurableApplicationContext context = start("dev", grace)) {
                assertThat(context.isActive())
                    .as("invalid grace must fail before an active context")
                    .isFalse();
              }
            })
        .hasStackTraceContaining("publication");
  }

  @ParameterizedTest(name = "grace={0}")
  @ValueSource(strings = {"PT9223372036854775807S", "P109500000D"})
  @DisplayName("S-05: 실제 HTTP의 만료 계산 overflow와 PostgreSQL 범위 초과는 500으로 전체 롤백한다")
  void rollsBackWhenExpiryCannotBeRepresented(String grace) throws Exception {
    try (ConfigurableApplicationContext context = start("local", grace)) {
      int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
      JdbcClient jdbc = context.getBean(JdbcClient.class);
      ObjectMapper json = context.getBean(ObjectMapper.class);
      NkCreateHttpFixture requests =
          new NkCreateHttpFixture(
              port,
              json,
              jdbc,
              context.getBean(UserRepository.class),
              context.getBean(LoginSessionService.class));
      NkCreateHttpFixture.Fixture fixture = requests.fixture();
      HttpResponse<String> response = requests.postRaw(fixture, UUID.randomUUID().toString(), true);
      assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(500);
      assertThat(json.readTree(response.body()).required("code").textValue())
          .isEqualTo("INTERNAL_ERROR");
      assertThat(
              jdbc.sql("select count(*) from rooms where host_user_id=:id")
                  .param("id", fixture.user().getId())
                  .query(Long.class)
                  .single())
          .isZero();
      assertThat(
              jdbc.sql("select count(*) from publication_idempotency where user_id=:id")
                  .param("id", fixture.user().getId())
                  .query(Long.class)
                  .single())
          .isZero();
    }
  }

  private static boolean hasPublicationRoute(ConfigurableApplicationContext context) {
    return context
        .getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)
        .getHandlerMethods()
        .keySet()
        .stream()
        .anyMatch(mapping -> mapping.getPatternValues().contains("/games"));
  }

  private static ConfigurableApplicationContext start(String profile, String grace) {
    Map<String, Object> properties = new LinkedHashMap<>();
    AuthTestSettings.register((key, supplier) -> properties.put(key, supplier.get()));
    properties.put(
        "spring.datasource.url",
        NkCreateHttpFixture.withCurrentSchema(
            PostgresTestDatabase.container().getJdbcUrl(), "nk75_registration"));
    properties.put("spring.datasource.username", PostgresTestDatabase.container().getUsername());
    properties.put("spring.datasource.password", PostgresTestDatabase.container().getPassword());
    properties.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
    properties.put("spring.jpa.hibernate.ddl-auto", "validate");
    properties.put("spring.flyway.enabled", "true");
    properties.put("spring.flyway.default-schema", "nk75_registration");
    properties.put("spring.docker.compose.enabled", "false");
    properties.put("server.port", "0");
    if (profile.equals("dev")) {
      String namespace = "kirikick:dev:fixture:" + UUID.randomUUID();
      properties.put("auth.secure-cookies", true);
      properties.put("auth.redis-namespace", namespace + ":auth");
      properties.put("auth.session-redis-namespace", namespace + ":spring:session");
      properties.put("spring.session.redis.namespace", namespace + ":spring:session");
    }
    if (!profile.equals("default")) properties.put("spring.profiles.active", profile);
    if (grace != null) properties.put("publication.grace", grace);
    String[] args =
        properties.entrySet().stream()
            .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
            .toArray(String[]::new);
    return new SpringApplicationBuilder(
            ApiApplication.class, OpenRoomHttpPostgresTest.DomainEntities.class)
        .registerShutdownHook(false)
        .run(args);
  }
}

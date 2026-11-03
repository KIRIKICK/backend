package org.kirikick.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

class ProfileConfigurationTest {

  private static final String DATABASE_HOST = "jdbc:postgresql://database.example:5432/";

  @Test
  void defaultsToLocalAndKeepsCommonRuntimePolicySafe() {
    configuration()
        .run(context -> {
          Environment environment = context.getEnvironment();

          assertThat(environment.getActiveProfiles()).isEmpty();
          assertThat(environment.getDefaultProfiles()).containsExactly("local");
          assertThat(environment.getProperty("spring.config.import"))
              .isEqualTo("optional:file:./.env[.properties]");
          assertThat(environment.getProperty("spring.docker.compose.enabled")).isEqualTo("true");
          assertThat(environment.getProperty("spring.datasource.driver-class-name"))
              .isEqualTo("org.postgresql.Driver");
          assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
              .isEqualTo("validate");
          assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
          assertThat(environment.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
          assertThat(environment.getProperty("server.port")).isEqualTo("8080");
        });
  }

  @Test
  void enablesDockerComposeOnlyForTheLocalProfile() {
    profileConfiguration("local")
        .run(context ->
            assertThat(context.getEnvironment().getProperty("spring.docker.compose.enabled"))
                .isEqualTo("true"));
  }

  @Test
  void readsDotEnvPropertiesForTheDatabaseConnection(@TempDir Path directory) throws IOException {
    Path dotEnv = directory.resolve(".env");
    Files.writeString(
        dotEnv,
        """
        DB_URL=jdbc:postgresql://database.example:5432/dev?sslmode=require
        DB_USERNAME=dotenv_user
        DB_PASSWORD=from-dot-env
        REDIS_HOST=redis.example
        REDIS_PASSWORD=redis-from-dot-env
        """);

    profileConfiguration("dev", "spring.config.import=optional:" + dotEnv.toUri() + "[.properties]")
        .run(context -> {
          Environment environment = context.getEnvironment();
          assertThat(environment.getProperty("spring.datasource.url"))
              .isEqualTo("jdbc:postgresql://database.example:5432/dev?sslmode=require");
          assertThat(environment.getProperty("spring.datasource.username"))
              .isEqualTo("dotenv_user");
          assertThat(environment.getProperty("spring.datasource.password"))
              .isEqualTo("from-dot-env");
          assertThat(environment.getProperty("spring.data.redis.host")).isEqualTo("redis.example");
          assertThat(environment.getProperty("spring.data.redis.password"))
              .isEqualTo("redis-from-dot-env");
          assertThat(environment.getProperty("spring.docker.compose.enabled")).isEqualTo("false");
        });
  }

  @Test
  void configuresDevDatabaseWithTlsAndNoEmbeddedPassword() {
    profileConfiguration(
            "dev",
            "DB_URL=" + DATABASE_HOST + "dev?sslmode=require",
            "DB_USERNAME=app_user",
            "DB_PASSWORD=test")
        .run(context -> assertProfileDatabase(context.getEnvironment(), "dev"));
  }

  @Test
  void configuresProdDatabaseWithTlsAndNoEmbeddedPassword() {
    profileConfiguration(
            "prod",
            "DB_URL=" + DATABASE_HOST + "prod?sslmode=require",
            "DB_USERNAME=app_user",
            "DB_PASSWORD=test")
        .run(context -> assertProfileDatabase(context.getEnvironment(), "prod"));
  }

  @Test
  void allowsDeploymentToOverrideTheDevDatabaseConnection() {
    profileConfiguration(
            "dev",
            "DB_URL=jdbc:postgresql://database.example:5432/future_dev?sslmode=require",
            "DB_USERNAME=future_user",
            "DB_PASSWORD=test")
        .run(context -> {
          Environment environment = context.getEnvironment();

          assertThat(environment.getProperty("spring.datasource.url"))
              .isEqualTo("jdbc:postgresql://database.example:5432/future_dev?sslmode=require");
          assertThat(environment.getProperty("spring.datasource.username"))
              .isEqualTo("future_user");
          assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("test");
        });
  }

  @ParameterizedTest
  @CsvSource({
    "dev, url, DB_URL",
    "dev, username, DB_USERNAME",
    "dev, password, DB_PASSWORD",
    "prod, url, DB_URL",
    "prod, username, DB_USERNAME",
    "prod, password, DB_PASSWORD"
  })
  void requiresDatabaseCredentials(String profile, String property, String variable) {
    profileConfiguration(profile, "spring.config.import=")
        .run(context -> {
          assertThatThrownBy(() ->
                  context.getEnvironment().getRequiredProperty("spring.datasource." + property))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining(variable);
        });
  }

  @Test
  void doesNotImportTheLocalDotEnvForProd() {
    profileConfiguration("prod")
        .run(context ->
            assertThat(context.getEnvironment().getProperty("spring.config.import")).isNull());
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "prod"})
  void configuresRedisWithTlsAndDeploymentCredentials(String profile) {
    profileConfiguration(
            profile,
            "REDIS_HOST=redis.example",
            "REDIS_PORT=6380",
            "REDIS_USERNAME=redis_user",
            "REDIS_PASSWORD=redis_test")
        .run(context -> {
          Environment environment = context.getEnvironment();
          assertThat(environment.getProperty("spring.data.redis.host")).isEqualTo("redis.example");
          assertThat(environment.getProperty("spring.data.redis.port")).isEqualTo("6380");
          assertThat(environment.getProperty("spring.data.redis.username")).isEqualTo("redis_user");
          assertThat(environment.getProperty("spring.data.redis.password")).isEqualTo("redis_test");
          assertThat(environment.getProperty("spring.data.redis.ssl.enabled")).isEqualTo("true");
        });
  }

  @ParameterizedTest
  @CsvSource({
    "dev, host, REDIS_HOST",
    "dev, password, REDIS_PASSWORD",
    "prod, host, REDIS_HOST",
    "prod, password, REDIS_PASSWORD"
  })
  void requiresRedisCredentials(String profile, String property, String variable) {
    profileConfiguration(profile, "spring.config.import=")
        .run(context -> {
          assertThatThrownBy(() ->
                  context.getEnvironment().getRequiredProperty("spring.data.redis." + property))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining(variable);
        });
  }

  @Test
  void defaultsToTheStandardRedisPortAndUser() {
    profileConfiguration("prod")
        .run(context -> {
          assertThat(context.getEnvironment().getProperty("spring.data.redis.port"))
              .isEqualTo("6379");
          assertThat(context.getEnvironment().getProperty("spring.data.redis.username"))
              .isEqualTo("default");
        });
  }

  @Test
  void limitsTheDefaultConnectionPool() {
    configuration()
        .run(context -> {
          assertThat(
                  context
                      .getEnvironment()
                      .getProperty("spring.datasource.hikari.maximum-pool-size"))
              .isEqualTo("5");
          assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.minimum-idle"))
              .isEqualTo("1");
        });
  }

  @Test
  void allowsDeploymentToOverrideTheConnectionPool() {
    configuration("DB_POOL_MAX_SIZE=3", "DB_POOL_MIN_IDLE=0")
        .run(context -> {
          assertThat(
                  context
                      .getEnvironment()
                      .getProperty("spring.datasource.hikari.maximum-pool-size"))
              .isEqualTo("3");
          assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.minimum-idle"))
              .isEqualTo("0");
        });
  }

  @Test
  void separatesProcessLivenessFromDatabaseReadiness() {
    configuration()
        .run(context -> {
          Environment environment = context.getEnvironment();
          assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
              .isEqualTo("health");
          assertThat(environment.getProperty("management.endpoint.health.show-details"))
              .isEqualTo("never");
          assertThat(environment.getProperty("management.endpoint.health.probes.enabled"))
              .isEqualTo("true");
          assertThat(environment.getProperty("management.endpoint.health.group.liveness.include"))
              .isEqualTo("livenessState");
          assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
              .isEqualTo("readinessState,db,redis");
          assertThat(environment.getProperty("management.health.redis.enabled")).isEqualTo("true");
        });
  }

  @ParameterizedTest
  @CsvSource({"local,true", "dev,true", "prod,false", "test,false"})
  void exposesApiDocumentationOnlyInLocalAndDev(String profile, String enabled) {
    profileConfiguration(profile, "spring.config.import=")
        .run(context -> {
          Environment environment = context.getEnvironment();
          assertThat(environment.getProperty("springdoc.api-docs.enabled")).isEqualTo(enabled);
          assertThat(environment.getProperty("springdoc.swagger-ui.enabled")).isEqualTo(enabled);
        });
  }

  private static ApplicationContextRunner configuration(String... properties) {
    return new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues(properties);
  }

  private static ApplicationContextRunner profileConfiguration(
      String profile, String... properties) {
    String[] withProfile = new String[properties.length + 1];
    withProfile[0] = "spring.profiles.active=" + profile;
    System.arraycopy(properties, 0, withProfile, 1, properties.length);
    return configuration(withProfile);
  }

  private static void assertProfileDatabase(Environment environment, String database) {
    assertThat(environment.getProperty("spring.datasource.url"))
        .isEqualTo(DATABASE_HOST + database + "?sslmode=require");
    assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("app_user");
    assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("test");
    assertThat(environment.getProperty("spring.docker.compose.enabled")).isEqualTo("false");
  }
}

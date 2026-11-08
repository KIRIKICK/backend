package org.kirikick.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.servlet.DispatcherServlet;

@JpaSliceTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = PostgresJpaSliceTest.ExternalHikariEnvironment.class)
class PostgresJpaSliceTest {

  @Autowired private DataSource dataSource;

  @PersistenceContext private EntityManager entityManager;

  @Autowired private ApplicationContext applicationContext;

  @Test
  void usesPostgresWithoutLoadingWebLayer() throws SQLException {
    try (var connection = dataSource.getConnection()) {
      assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
    }
    assertThat(applicationContext.getBeansOfType(DispatcherServlet.class)).isEmpty();
    assertThat(applicationContext.getBean(JPAQueryFactory.class)).isNotNull();
    assertThat(entityManager.isJoinedToTransaction()).isTrue();
    assertThat(
            entityManager
                .createNativeQuery(
                    "select jsonb_extract_path_text(cast('{\"database\":\"postgres\"}' as jsonb), 'database')")
                .getSingleResult())
        .isEqualTo("postgres");
  }

  @Test
  void ignoresExternalHikariSettingsAndSharesTheContainer() throws SQLException {
    var container = PostgresTestDatabase.container();

    assertThat(dataSource)
        .isInstanceOfSatisfying(
            HikariDataSource.class,
            hikari -> {
              assertThat(hikari.getJdbcUrl()).isEqualTo(container.getJdbcUrl());
              assertThat(hikari.getUsername()).isEqualTo(container.getUsername());
              assertThat(hikari.getPassword()).isEqualTo(container.getPassword());
            });

    try (var springConnection = dataSource.getConnection();
        var jdbcConnection =
            DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword())) {
      assertThat(springConnection.getMetaData().getURL()).isEqualTo(container.getJdbcUrl());
      assertThat(jdbcConnection.getMetaData().getURL()).isEqualTo(container.getJdbcUrl());
    }
  }

  static class ExternalHikariEnvironment
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      var sourceName = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
      context
          .getEnvironment()
          .getPropertySources()
          .replace(
              sourceName,
              new SystemEnvironmentPropertySource(
                  sourceName,
                  Map.of(
                      "SPRING_DATASOURCE_HIKARI_JDBC_URL",
                      "jdbc:postgresql://127.0.0.1:1/unused?connectTimeout=1",
                      "SPRING_DATASOURCE_HIKARI_USERNAME",
                      "unused",
                      "SPRING_DATASOURCE_HIKARI_PASSWORD",
                      "unused")));
    }
  }
}

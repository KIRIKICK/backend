package org.kirikick.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.DriverManager;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

@JpaSliceTest
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
  void sharesTheContainerWithDirectJdbcAccess() throws SQLException {
    var container = PostgresTestDatabase.container();

    try (var springConnection = dataSource.getConnection();
        var jdbcConnection =
            DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword())) {
      assertThat(springConnection.getMetaData().getURL()).isEqualTo(container.getJdbcUrl());
      assertThat(jdbcConnection.getMetaData().getURL()).isEqualTo(container.getJdbcUrl());
    }
  }
}

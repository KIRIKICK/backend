package org.kirikick.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.kirikick.api.support.H2JpaTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

@H2JpaTest
class QuerydslIntegrationTest {

  @PersistenceContext private EntityManager entityManager;

  @Autowired private JPAQueryFactory queryFactory;

  @Autowired private DataSource dataSource;

  @Autowired private ApplicationContext applicationContext;

  @Test
  void usesH2WithoutLoadingWebLayer() throws SQLException {
    try (var connection = dataSource.getConnection()) {
      assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("H2");
    }
    assertThat(applicationContext.getBeansOfType(DispatcherServlet.class)).isEmpty();
    assertThat(entityManager.isJoinedToTransaction()).isTrue();
  }

  @Test
  void queriesJakartaEntityUsingGeneratedQType() {
    entityManager.persist(new Probe(1L, "matching"));
    entityManager.persist(new Probe(2L, "other"));
    entityManager.flush();

    QQuerydslIntegrationTest_Probe probe = QQuerydslIntegrationTest_Probe.probe;
    String name = queryFactory.select(probe.name).from(probe).where(probe.id.eq(1L)).fetchOne();

    assertThat(name).isEqualTo("matching");
  }

  @Entity
  public static class Probe {

    @Id private Long id;
    private String name;

    protected Probe() {}

    Probe(Long id, String name) {
      this.id = id;
      this.name = name;
    }
  }
}

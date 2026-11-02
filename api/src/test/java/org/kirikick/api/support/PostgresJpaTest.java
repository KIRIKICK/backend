package org.kirikick.api.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.kirikick.api.config.JpaAuditingConfig;
import org.kirikick.api.config.QuerydslConfig;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/** Uses a dedicated test database: Hibernate creates and drops its mapped tables. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@DataJpaTest(
    properties = {
      "spring.datasource.url=${TEST_POSTGRES_URL}",
      "spring.datasource.username=${TEST_POSTGRES_USERNAME:postgres}",
      "spring.datasource.password=${TEST_POSTGRES_PASSWORD:}",
      "spring.datasource.driver-class-name=org.postgresql.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuerydslConfig.class, JpaAuditingConfig.class})
public @interface PostgresJpaTest {}

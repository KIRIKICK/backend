package org.kirikick.api.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.kirikick.api.config.JpaAuditingConfig;
import org.kirikick.api.config.QuerydslConfig;
import org.kirikick.testsupport.postgres.PostgresJpaTest;
import org.springframework.context.annotation.Import;

/** Application JPA slice with Querydsl and auditing. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@PostgresJpaTest
@Import({QuerydslConfig.class, JpaAuditingConfig.class})
public @interface JpaSliceTest {}

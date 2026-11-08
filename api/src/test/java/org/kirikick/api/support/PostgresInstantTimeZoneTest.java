package org.kirikick.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.OAuthProvider;
import org.kirikick.api.auth.domain.SignupApplication;
import org.kirikick.api.user.domain.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@JpaSliceTest
@ActiveProfiles("test")
// Other tests define probe entities with no Flyway migrations.
@EntityScan(basePackageClasses = {SignupApplication.class, User.class})
class PostgresInstantTimeZoneTest {

  private static final Instant ISSUED_AT = Instant.parse("2030-01-02T00:15:30.123456Z");
  private static final Instant EXPECTED_EXPIRES_AT = Instant.parse("2030-01-02T00:20:30.123456Z");

  @Autowired private Environment environment;
  @PersistenceContext private EntityManager entityManager;

  @DynamicPropertySource
  static void migrationSchema(DynamicPropertyRegistry registry) {
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }

  @Test
  void preservesTheInstantAcrossDifferentJvmAndDatabaseTimeZones() {
    assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class))
        .as("Flyway must create the schema instead of Hibernate")
        .isTrue();
    assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    assertThat(entityManager.getEntityManagerFactory().getProperties())
        .as("effective Hibernate schema mode")
        .containsEntry("hibernate.hbm2ddl.auto", "validate");
    Number appliedMigrations =
        (Number)
            entityManager
                .createNativeQuery(
                    """
                    select count(*) from flyway_schema_history
                    where type = 'SQL' and success = true and version in ('1', '2')
                    """)
                .getSingleResult();
    assertThat(appliedMigrations.longValue())
        .as("successful Flyway V1 and V2 migrations")
        .isEqualTo(2);

    assertThat(System.getProperty("user.timezone"))
        .as("test worker JVM timezone")
        .isEqualTo("Asia/Seoul");
    assertThat(ZoneId.systemDefault())
        .as("actual JVM default timezone")
        .isEqualTo(ZoneId.of("Asia/Seoul"));
    assertThat(entityManager.isJoinedToTransaction()).isTrue();
    assertThat(
            entityManager
                .createNativeQuery("select set_config('TimeZone', 'Pacific/Honolulu', true)")
                .getSingleResult())
        .isEqualTo("Pacific/Honolulu");
    Object[] sessionBefore =
        (Object[])
            entityManager
                .createNativeQuery("select current_setting('TimeZone'), pg_backend_pid()")
                .getSingleResult();
    assertThat(sessionBefore[0])
        .as("actual DB session timezone before persistence")
        .isEqualTo("Pacific/Honolulu");

    Object[] column =
        (Object[])
            entityManager
                .createNativeQuery(
                    """
                    select udt_name, datetime_precision from information_schema.columns
                    where table_schema = current_schema() and table_name = 'signup_applications'
                      and column_name = 'expires_at'
                    """)
                .getSingleResult();
    assertThat(column[0]).as("migrated expires_at type").isEqualTo("timestamptz");
    assertThat(((Number) column[1]).intValue()).as("migrated expires_at precision").isEqualTo(6);
    assertThat(ISSUED_AT.getNano() % 1_000).as("microsecond-aligned fixture").isZero();

    UUID applicationId = UUID.randomUUID();
    SignupApplication application =
        SignupApplication.open(applicationId, OAuthProvider.KAKAO, "timezone-subject", ISSUED_AT);
    entityManager.persist(application);
    entityManager.flush();
    entityManager.clear();

    assertThat(entityManager.contains(application)).isFalse();
    SignupApplication reloaded = entityManager.find(SignupApplication.class, applicationId);
    assertThat(reloaded).isNotNull().isNotSameAs(application);
    assertThat(reloaded.getExpiresAt())
        .as("Instant read from the migrated schema")
        .isEqualTo(EXPECTED_EXPIRES_AT);

    Object[] stored =
        (Object[])
            entityManager
                .createNativeQuery(
                    """
                    select current_setting('TimeZone'), pg_backend_pid(), extract(epoch from expires_at)
                    from signup_applications where id = :id
                    """)
                .setParameter("id", applicationId)
                .getSingleResult();
    assertThat(stored[0])
        .as("actual DB session timezone after reload")
        .isEqualTo("Pacific/Honolulu");
    assertThat(stored[1])
        .as("same physical DB session throughout the transaction")
        .isEqualTo(sessionBefore[1]);
    assertThat((BigDecimal) stored[2])
        .as("independent stored epoch seconds")
        .isEqualByComparingTo("1893543630.123456");
  }
}

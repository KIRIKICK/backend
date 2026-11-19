package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class PublicationSchemaMigrationTest {

  private static final PublicationPhase1Postgres POSTGRES = new PublicationPhase1Postgres();

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.close();
  }

  @Test
  @DisplayName("P1-01: 빈 스키마 설치와 V2 업그레이드에서 체크섬·매핑을 검증한다")
  void p101MigratesFreshAndUpgradesV2WithoutChangingItsHistoryOrRows() throws SQLException {
    String fresh = POSTGRES.newSchema();
    String upgrade = POSTGRES.newSchema();
    try (var freshPool = POSTGRES.openSchema(fresh);
        var upgradePool = POSTGRES.openSchema(upgrade)) {
      try {
        var freshFlyway = PublicationPhase1Postgres.flyway(freshPool, fresh, "4");
        var v2 = PublicationPhase1Postgres.flyway(upgradePool, upgrade, "2");
        var upgradedFlyway = PublicationPhase1Postgres.flyway(upgradePool, upgrade, "4");
        var freshJdbc = JdbcClient.create(freshPool);
        var upgradeJdbc = JdbcClient.create(upgradePool);
        assertThat(v2.migrate().migrationsExecuted).isEqualTo(2);
        var before = history(upgradeJdbc);
        assertThat(before).extracting(row -> row.get("version")).containsExactly("1", "2");
        long legacyUser =
            upgradeJdbc
                .sql(
                    """
                    insert into users (uuid, display_name, status, created_at, updated_at)
                    values (gen_random_uuid(), 'p1b legacy user', 'ACTIVE', now(), now()) returning id
                    """)
                .query(Long.class)
                .single();
        assertThat(freshFlyway.migrate().migrationsExecuted).isEqualTo(4);
        assertThat(upgradedFlyway.migrate().migrationsExecuted).isEqualTo(2);
        freshFlyway.validate();
        upgradedFlyway.validate();
        var freshHistory = history(freshJdbc);
        var upgradedHistory = history(upgradeJdbc);
        assertThat(freshHistory)
            .extracting(row -> row.get("version"))
            .containsExactly("1", "2", "3", "4");
        assertThat(upgradedHistory).isEqualTo(freshHistory);
        assertThat(upgradedHistory.subList(0, 2)).isEqualTo(before);
        for (var jdbc : List.of(freshJdbc, upgradeJdbc)) {
          var tables =
              jdbc.sql(
                      """
                      select tablename from pg_tables where schemaname = current_schema()
                      """)
                  .query(String.class)
                  .list();
          assertThat(tables)
              .containsExactlyInAnyOrder(
                  "flyway_schema_history",
                  "users",
                  "oauth_identities",
                  "signup_applications",
                  "reward_presets",
                  "question_presets",
                  "question_preset_choices",
                  "rooms",
                  "games",
                  "choices",
                  "publication_idempotency");
          assertThat(
                  jdbc.sql(
                          """
                          select (select count(*) from reward_presets) + (select count(*) from question_presets)
                            + (select count(*) from question_preset_choices)
                          """)
                      .query(Long.class)
                      .single())
              .isZero();
          System.out.printf("P1-01 tables=%s no operational seed%n", tables);
        }
        assertThat(
                upgradeJdbc
                    .sql("select display_name from users where id = :id")
                    .param("id", legacyUser)
                    .query(String.class)
                    .single())
            .isEqualTo("p1b legacy user");
        System.out.printf(
            "P1-01 V1/V2 before=%s fresh=%s upgrade=%s%n", before, freshHistory, upgradedHistory);
        for (var pool : List.of(freshPool, upgradePool)) {
          PublicationPhase1Postgres.jpa(pool)
              .run(context -> {
                assertThat(context).hasNotFailed();
                var factory = context.getBean(EntityManagerFactory.class);
                assertThat(factory.getProperties())
                    .containsEntry("hibernate.hbm2ddl.auto", "validate");
                System.out.printf(
                    "P1-01 Hibernate validate schema=%s entities=%s%n",
                    pool.getPoolName(),
                    factory.getMetamodel().getEntities().stream()
                        .map(entity -> entity.getName())
                        .sorted()
                        .toList());
              });
        }
      } finally {
        PublicationPhase1Postgres.assertConnectionsReturned(freshPool, "P1-01 fresh");
        PublicationPhase1Postgres.assertConnectionsReturned(upgradePool, "P1-01 upgrade");
      }
    }
  }

  private static List<Map<String, Object>> history(JdbcClient jdbc) {
    return jdbc.sql(
            """
            select version, script, checksum from flyway_schema_history
            where type = 'SQL' and success order by installed_rank
            """)
        .query()
        .listOfRows();
  }
}

package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.flywaydb.core.Flyway;
import org.hibernate.Version;
import org.kirikick.api.auth.domain.SignupApplication;
import org.kirikick.api.common.infra.DbTimeSource;
import org.kirikick.api.config.JpaAuditingConfig;
import org.kirikick.api.config.PublicationConfiguration;
import org.kirikick.api.game.domain.Game;
import org.kirikick.api.game.repository.GameRepository;
import org.kirikick.api.publication.repository.PublicationIdempotencyRepository;
import org.kirikick.api.question.domain.QuestionPreset;
import org.kirikick.api.question.repository.QuestionPresetRepository;
import org.kirikick.api.reward.domain.RewardPreset;
import org.kirikick.api.reward.repository.RewardPresetRepository;
import org.kirikick.api.room.domain.Room;
import org.kirikick.api.room.repository.RoomRepository;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;

final class PublicationPhase1Postgres implements AutoCloseable {

  static final String MIGRATIONS = "classpath:db/migration";
  private static final AtomicReference<Throwable> CLEANUP_FAILURE = new AtomicReference<>();
  private final PostgreSQLContainer<?> container =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName(randomName("phase1b_"))
          .withLabel("kirikick.validation", "publication-phase1b")
          .withCommand(
              "postgres", "-c", "log_line_prefix=%m [%p] xid=%x ", "-c", "log_statement=all")
          .withReuse(false);

  void start() {
    requireClean();
    container.start();
  }

  String newSchema() {
    return randomName("p1b_");
  }

  void logActorSql(int... pids) {
    container
        .getLogs()
        .lines()
        .forEach(line -> {
          for (int pid : pids) {
            if (line.contains(" [" + pid + "] xid=")) {
              System.out.println("server " + line);
              break;
            }
          }
        });
  }

  HikariDataSource openSchema(String schema) throws SQLException {
    requireClean();
    assertThat(schema).matches("p1b_[a-f0-9]{32}");
    HikariConfig config = new HikariConfig();
    config.setPoolName(schema);
    config.setJdbcUrl(container.getJdbcUrl());
    config.setUsername(container.getUsername());
    config.setPassword(container.getPassword());
    config.setSchema(schema);
    config.setMaximumPoolSize(6);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(5_000);
    config.setConnectionInitSql(
        """
        set statement_timeout = '15s';
        set lock_timeout = '12s';
        set idle_in_transaction_session_timeout = '20s';
        set time zone 'Pacific/Honolulu';
        """);
    HikariDataSource pool = new HikariDataSource(config);
    try (Connection connection = pool.getConnection();
        var statement = connection.createStatement();
        var row =
            statement.executeQuery(
                """
                select current_database(), current_setting('server_version_num')::int,
                       current_setting('server_encoding'), pg_backend_pid(), version(),
                       current_setting('transaction_isolation'), current_setting('TimeZone')
                """)) {
      assertThat(row.next()).isTrue();
      assertThat(connection.getMetaData().getURL()).isEqualTo(container.getJdbcUrl());
      assertThat(row.getString(1)).isEqualTo(container.getDatabaseName());
      assertThat(row.getInt(2)).isBetween(170000, 179999);
      assertThat(row.getString(3)).isEqualTo("UTF8");
      System.out.printf(
          "PG gate image=%s container=%s url=%s schema=%s pid=%d encoding=%s version=%s isolation=%s"
              + " timezone=%s autocommit=%s poolMax=%d JDBC=%s%n",
          container.getDockerImageName(),
          container.getContainerId(),
          connection.getMetaData().getURL(),
          schema,
          row.getInt(4),
          row.getString(3),
          row.getString(5),
          row.getString(6),
          row.getString(7),
          connection.getAutoCommit(),
          pool.getMaximumPoolSize(),
          connection.getMetaData().getDriverVersion());
    } catch (SQLException | RuntimeException | Error failure) {
      pool.close();
      throw failure;
    }
    return pool;
  }

  List<String> sqlLog() {
    return container.getLogs().lines().toList();
  }

  static Flyway flyway(HikariDataSource pool, String schema, String target) {
    return Flyway.configure()
        .dataSource(pool)
        .locations(MIGRATIONS)
        .defaultSchema(schema)
        .schemas(schema)
        .target(target)
        .load();
  }

  static ApplicationContextRunner jpa(HikariDataSource pool) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(HibernateJpaAutoConfiguration.class))
        .withUserConfiguration(PersistenceConfig.class)
        .withInitializer(context -> context.getBeanFactory().registerSingleton("dataSource", pool))
        .withBean(JdbcClient.class, () -> JdbcClient.create(pool))
        .withPropertyValues(
            "publication.grace=PT5M",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.show-sql=true");
  }

  void inMigratedSchema(
      String trial, BiConsumer<AssertableApplicationContext, HikariDataSource> test)
      throws SQLException {
    String schema = newSchema();
    try (var pool = openSchema(schema)) {
      Throwable testFailure = null;
      try {
        assertThat(flyway(pool, schema, "5").migrate().migrationsExecuted).isEqualTo(5);
        jpa(pool)
            .run(context -> {
              assertThat(context).hasNotFailed();
              System.out.printf(
                  "versions Spring Boot=%s Hibernate=%s%n",
                  SpringBootVersion.getVersion(), Version.getVersionString());
              test.accept(context, pool);
            });
      } catch (RuntimeException | Error failure) {
        testFailure = failure;
        throw failure;
      } finally {
        try {
          assertConnectionsReturned(pool, trial);
        } catch (RuntimeException | Error cleanupFailure) {
          if (testFailure != null) {
            testFailure.addSuppressed(cleanupFailure);
          } else {
            throw cleanupFailure;
          }
        }
      }
    }
  }

  static void assertConnectionsReturned(HikariDataSource pool, String trial) {
    var stats = pool.getHikariPoolMXBean();
    Map<String, Integer> counts =
        Map.of(
            "active", stats.getActiveConnections(),
            "idle", stats.getIdleConnections(),
            "pending", stats.getThreadsAwaitingConnection());
    System.out.printf("cleanup %s schema=%s pool=%s%n", trial, pool.getPoolName(), counts);
    try {
      assertThat(counts).containsEntry("active", 0).containsEntry("pending", 0);
    } catch (AssertionError failure) {
      recordCleanupFailure(failure);
      throw failure;
    }
  }

  @Override
  public void close() {
    String id = container.getContainerId();
    try {
      container.stop();
      assertThat(container.isRunning()).isFalse();
    } catch (RuntimeException | AssertionError failure) {
      recordCleanupFailure(failure);
      throw failure;
    }
    System.out.printf("cleanup stopped PG container=%s%n", id);
  }

  static void recordCleanupFailure(Throwable failure) {
    CLEANUP_FAILURE.compareAndSet(null, failure);
  }

  private static void requireClean() {
    if (CLEANUP_FAILURE.get() != null) {
      throw new IllegalStateException(
          "Previous scenario cleanup was not confirmed", CLEANUP_FAILURE.get());
    }
  }

  private static String randomName(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "");
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EntityScan(
      basePackageClasses = {
        SignupApplication.class,
        User.class,
        RewardPreset.class,
        QuestionPreset.class,
        Room.class,
        Game.class
      })
  @EnableJpaRepositories(
      basePackageClasses = {
        UserRepository.class,
        RewardPresetRepository.class,
        QuestionPresetRepository.class,
        RoomRepository.class,
        GameRepository.class
      })
  @EnableTransactionManagement
  @Import({
    JpaAuditingConfig.class,
    PublicationConfiguration.class,
    PublicationIdempotencyRepository.class,
    DbTimeSource.class
  })
  static class PersistenceConfig {}
}

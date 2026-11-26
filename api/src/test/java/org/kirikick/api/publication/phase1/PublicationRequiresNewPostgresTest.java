package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.common.infra.DbTimeSource;
import org.kirikick.api.config.PublicationProperties;
import org.kirikick.api.game.repository.ChoiceRepository;
import org.kirikick.api.game.repository.GameRepository;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.domain.PublicationKind;
import org.kirikick.api.publication.infra.PublicationCommitObserver;
import org.kirikick.api.publication.infra.PublicationDataSource;
import org.kirikick.api.publication.repository.PublicationIdempotencyRepository;
import org.kirikick.api.publication.service.OpenRoomService;
import org.kirikick.api.publication.service.PublicationResult;
import org.kirikick.api.question.repository.QuestionPresetChoiceRepository;
import org.kirikick.api.question.repository.QuestionPresetRepository;
import org.kirikick.api.reward.repository.RewardPresetRepository;
import org.kirikick.api.room.repository.RoomRepository;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class PublicationRequiresNewPostgresTest {
  private static final PublicationPhase1Postgres POSTGRES = new PublicationPhase1Postgres();

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.close();
  }

  @Test
  @DisplayName("REQUIRES_NEW 공개는 외부 트랜잭션과 다른 PostgreSQL 연결에서 JDBCㆍHibernateㆍCOMMIT을 수행한다")
  void publishesOnOneInnerConnectionAndResumesOuterConnection() throws SQLException {
    POSTGRES.inMigratedSchema(
        "publication-requires-new",
        (ignored, pool) -> {
          var events = new ArrayList<Event>();
          var observedAcquisitions = new ArrayList<Boolean>();
          DataSource dataSource =
              new RecordingDataSource(
                  new PublicationDataSource(pool), events, observedAcquisitions);

          jpa(dataSource)
              .run(context -> {
                JdbcClient jdbc = context.getBean(JdbcClient.class);
                Fixture fixture = fixture(jdbc);
                events.clear();
                observedAcquisitions.clear();

                TransactionTemplate outer =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                outer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
                outer.executeWithoutResult(status -> {
                  ConnectionState outerBefore = state(jdbc);

                  PublicationResult result =
                      service(context)
                          .publish(fixture.userUuid(), "requires-new-key", input(fixture));

                  ConnectionState outerAfter = state(jdbc);
                  assertThat(result.created()).isTrue();
                  assertThat(outerAfter).isEqualTo(outerBefore);
                  assertThat(observedAcquisitions).contains(false, true);

                  List<Event> inner =
                      events.stream().filter(event -> event.pid() != outerBefore.pid()).toList();
                  // PostgreSQL assigns an XID only once the inner transaction first writes.
                  List<Event> innerTransaction =
                      inner.stream().filter(event -> event.xid() != null).toList();
                  assertThat(innerTransaction).isNotEmpty();
                  int innerPid = innerTransaction.getFirst().pid();
                  String innerXid = innerTransaction.getFirst().xid();
                  assertThat(inner).isNotEmpty();
                  assertThat(inner)
                      .allSatisfy(event -> assertThat(event.pid()).isEqualTo(innerPid));
                  assertThat(innerTransaction)
                      .allSatisfy(event -> {
                        assertThat(event.pid()).isEqualTo(innerPid);
                        assertThat(event.xid()).isEqualTo(innerXid);
                      });
                  assertThat(innerTransaction)
                      .extracting(Event::sql)
                      .anyMatch(sql -> sql.contains("insert into rooms"));
                  assertThat(innerTransaction)
                      .extracting(Event::sql)
                      .anyMatch(sql -> sql.contains("select clock_timestamp()"));
                  assertThat(innerTransaction).extracting(Event::commit).contains(true);
                  assertThat(innerPid).isNotEqualTo(outerBefore.pid());
                  assertThat(innerXid).isNotEqualTo(outerBefore.xid());
                  System.out.printf(
                      "REQUIRES_NEW_DS_EVIDENCE outerBefore=%s outerAfter=%s inner=%s%n",
                      outerBefore, outerAfter, inner);
                });
              });
        });
  }

  private static ApplicationContextRunner jpa(DataSource dataSource) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(HibernateJpaAutoConfiguration.class))
        .withUserConfiguration(PublicationPhase1Postgres.PersistenceConfig.class)
        .withInitializer(
            context -> context.getBeanFactory().registerSingleton("dataSource", dataSource))
        .withBean(JdbcClient.class, () -> JdbcClient.create(dataSource))
        .withPropertyValues(
            "publication.grace=PT5M",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.show-sql=true");
  }

  private static OpenRoomService service(ApplicationContext context) {
    EntityManagerFactory factory = context.getBean(EntityManagerFactory.class);
    EntityManager entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
    QuestionPresetChoiceRepository choices =
        new JpaRepositoryFactory(entityManager).getRepository(QuestionPresetChoiceRepository.class);
    return new OpenRoomService(
        context.getBean(PlatformTransactionManager.class),
        context.getBean(PublicationIdempotencyRepository.class),
        context.getBean(UserRepository.class),
        context.getBean(RoomRepository.class),
        context.getBean(GameRepository.class),
        context.getBean(ChoiceRepository.class),
        context.getBean(QuestionPresetRepository.class),
        choices,
        context.getBean(RewardPresetRepository.class),
        entityManager,
        context.getBean(DbTimeSource.class),
        new PublicationProperties(Duration.ofMinutes(5)),
        new PublicationCommitObserver());
  }

  private static ConnectionState state(JdbcClient jdbc) {
    return jdbc.sql("select pg_backend_pid(), pg_current_xact_id()::text")
        .query((row, rowNum) -> new ConnectionState(row.getInt(1), row.getString(2)))
        .single();
  }

  private static Fixture fixture(JdbcClient jdbc) {
    UserRow user =
        jdbc.sql(
                """
                insert into users(uuid, display_name, status, created_at, updated_at)
                values (?, 'requires-new-host', 'ACTIVE', now(), now())
                returning id, uuid
                """)
            .param(UUID.randomUUID())
            .query(
                (row, rowNum) -> new UserRow(row.getLong("id"), row.getObject("uuid", UUID.class)))
            .single();
    long rewardId =
        jdbc.sql(
                """
                insert into reward_presets(title, description)
                values ('reward', 'reward')
                returning id
                """)
            .query(Long.class)
            .single();
    long questionId =
        jdbc.sql("insert into question_presets(question_text) values ('question') returning id")
            .query(Long.class)
            .single();
    List<Long> choiceIds =
        jdbc.sql(
                """
                insert into question_preset_choices(question_preset_id, choice_text)
                select ?, 'choice-' || generate_series(1, 4)
                returning id
                """)
            .param(questionId)
            .query(Long.class)
            .list();
    return new Fixture(user.uuid(), rewardId, questionId, choiceIds);
  }

  private static PublicationInput input(Fixture fixture) {
    return new PublicationInput(
        PublicationKind.NK_CREATE,
        null,
        fixture.rewardId(),
        fixture.questionId(),
        fixture.choiceIds().reversed(),
        fixture.choiceIds().get(1));
  }

  private record Fixture(UUID userUuid, long rewardId, long questionId, List<Long> choiceIds) {}

  private record UserRow(long id, UUID uuid) {}

  private record ConnectionState(int pid, String xid) {}

  private record Event(int pid, String xid, String sql, boolean commit) {}

  private static final class RecordingDataSource extends DelegatingDataSource {
    private final List<Event> events;
    private final List<Boolean> observedAcquisitions;

    private RecordingDataSource(
        DataSource target, List<Event> events, List<Boolean> observedAcquisitions) {
      super(target);
      this.events = events;
      this.observedAcquisitions = observedAcquisitions;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return connection(acquired(super.getConnection()));
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return connection(acquired(super.getConnection(username, password)));
    }

    private Connection connection(Connection targetConnection) {
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, arguments) -> invokeConnection(targetConnection, method, arguments));
    }

    private Connection acquired(Connection connection) {
      observedAcquisitions.add(Proxy.isProxyClass(connection.getClass()));
      return connection;
    }

    private Object invokeConnection(Connection connection, Method method, Object[] arguments)
        throws Throwable {
      if ("commit".equals(method.getName()) && method.getParameterCount() == 0) {
        record(connection, "commit", true);
      }
      Object value = invoke(connection, method, arguments);
      if (value instanceof PreparedStatement statement) {
        return statement(connection, statement, (String) arguments[0]);
      }
      if (value instanceof Statement statement) {
        return statement(connection, statement, null);
      }
      return value;
    }

    private Statement statement(Connection connection, Statement statement, String preparedSql) {
      return (Statement)
          Proxy.newProxyInstance(
              Statement.class.getClassLoader(),
              statement instanceof PreparedStatement
                  ? new Class<?>[] {PreparedStatement.class}
                  : new Class<?>[] {Statement.class},
              (proxy, method, arguments) -> {
                if (isExecution(method)) {
                  String sql =
                      preparedSql != null
                          ? preparedSql
                          : arguments != null && arguments.length > 0
                              ? (String) arguments[0]
                              : "batch";
                  Object value = invoke(statement, method, arguments);
                  record(connection, sql, false);
                  return value;
                }
                return invoke(statement, method, arguments);
              });
    }

    private static boolean isExecution(Method method) {
      return method.getName().startsWith("execute");
    }

    private void record(Connection connection, String sql, boolean commit) throws SQLException {
      try (Statement probe = connection.createStatement();
          ResultSet result =
              probe.executeQuery(
                  "select pg_backend_pid(), pg_current_xact_id_if_assigned()::text")) {
        assertThat(result.next()).isTrue();
        events.add(
            new Event(result.getInt(1), result.getString(2), sql.toLowerCase(Locale.ROOT), commit));
      }
    }

    private static Object invoke(Object target, Method method, Object[] arguments)
        throws Throwable {
      try {
        return method.invoke(target, arguments);
      } catch (InvocationTargetException exception) {
        throw exception.getTargetException();
      }
    }
  }
}

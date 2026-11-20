package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.kirikick.api.publication.phase1.NkCreateCommitWire.Loss;
import org.kirikick.api.room.domain.Room;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Timeout(90)
class NkCreateJdbcCommitPostgresTest {
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
  @DisplayName("실제 COMMIT 응답 유실은 JDBC 경계에서 커밋과 중단을 구분하지 못한다")
  void trueCommitReplyLossLooksIdenticalOnJdbcForCommitAndAbort() throws SQLException {
    POSTGRES.inMigratedSchema(
        "NK75-commit-outcomes",
        (ignored, direct) -> {
          JdbcClient db = JdbcClient.create(direct);
          createProbe(db);
          var committed = experiment(direct, Fault.COMMIT_REPLY, false, "F-05");
          var aborted = experiment(direct, Fault.COMMIT_REPLY, true, "F-09");
          var rejection = experiment(direct, Fault.NONE, true, "F-10");
          var flush = experiment(direct, Fault.FLUSH_REPLY, false, "F-06");

          assertThat(committed.verdict()).isEqualTo(Verdict.COMMIT_RESULT_UNKNOWN);
          assertThat(aborted.verdict()).isEqualTo(Verdict.COMMIT_RESULT_UNKNOWN);
          assertThat(committed.boundary().entered()).isTrue();
          assertThat(committed.boundary().returned()).isFalse();
          assertThat(committed.boundary().parsedBackendError()).isFalse();
          assertThat(containsIOException(committed.boundary().commitFailure())).isTrue();
          assertThat(aborted.boundary().entered()).isTrue();
          assertThat(aborted.boundary().returned()).isFalse();
          assertThat(aborted.boundary().parsedBackendError()).isFalse();
          assertThat(containsIOException(aborted.boundary().commitFailure())).isTrue();
          assertThat(committed.withheld()).isEqualTo('C');
          assertThat(aborted.withheld()).isEqualTo('E');
          assertThat(committed.finalXid()).isEqualTo("committed");
          assertThat(aborted.finalXid()).isEqualTo("aborted");
          assertThat(rejection.verdict()).isEqualTo(Verdict.DEFINITE_COMMIT_REJECTION);
          assertThat(rejection.boundary().parsedBackendError()).isTrue();
          assertThat(rejection.failureState()).isEqualTo("23505");
          assertThat(rejection.finalXid()).isEqualTo("aborted");
          assertThat(flush.verdict()).isEqualTo(Verdict.BEFORE_COMMIT);
          assertThat(flush.finalXid()).isEqualTo("aborted");
        });
  }

  @Test
  @DisplayName("커밋 전 풀·구문·rollback-only 실패는 JDBC COMMIT에 도달하지 않는다")
  void poolStatementAndRollbackOnlyFailuresDoNotReachJdbcCommit() throws SQLException {
    POSTGRES.inMigratedSchema(
        "NK75-early-failures",
        (ignored, direct) -> {
          createProbe(JdbcClient.create(direct));
          for (var fault : new Fault[] {Fault.POOL, Fault.STATEMENT, Fault.ROLLBACK_ONLY}) {
            var result =
                experiment(direct, fault, false, fault == Fault.ROLLBACK_ONLY ? "F-08" : "F-04");
            assertThat(result.verdict()).isEqualTo(Verdict.BEFORE_COMMIT);
            assertThat(result.finalXid()).isEqualTo(fault == Fault.POOL ? "none" : "aborted");
          }
        });
  }

  @Test
  @DisplayName("커밋 뒤 HTTP 응답 유실은 JDBC COMMIT 결과 불명이 아니다")
  void actualLoopbackHttpResponseLossAfterSuccessfulCommitIsNotJdbcAmbiguity() throws SQLException {
    POSTGRES.inMigratedSchema(
        "NK75-post-commit-response",
        (ignored, direct) -> {
          createProbe(JdbcClient.create(direct));
          var audit = new CompletableFuture<Audit>();
          HttpServer server;
          try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
          } catch (IOException e) {
            throw new AssertionError(e);
          }
          server.createContext(
              "/probe",
              exchange -> {
                try {
                  audit.complete(experiment(direct, Fault.NONE, false, "F-07"));
                } catch (Throwable failure) {
                  audit.completeExceptionally(failure);
                } finally {
                  exchange.close(); // Intentionally send no HTTP status or headers after the
                  // transaction finishes.
                }
              });
          server.start();
          try (var client = HttpClient.newHttpClient()) {
            var request =
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/probe"))
                    .timeout(Duration.ofSeconds(25))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            assertThatThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.discarding()))
                .isInstanceOf(IOException.class);
            var result = audit.get(25, TimeUnit.SECONDS);
            assertThat(result.verdict()).isEqualTo(Verdict.JDBC_RETURNED);
            assertThat(result.finalXid()).isEqualTo("committed");
          } catch (Exception e) {
            throw new AssertionError(e);
          } finally {
            server.stop(0);
          }
        });
  }

  private static void createProbe(JdbcClient observer) {
    observer
        .sql(
            """
            create table nk75_commit_probe (
              room_id bigint, value integer unique deferrable initially deferred
            )
            """)
        .update();
  }

  private static Audit experiment(
      HikariDataSource direct, Fault fault, boolean deferredConflict, String caseId) {
    var observer = JdbcClient.create(direct);
    long roomId = seedRoom(observer);
    var actualUrl = URI.create(direct.getJdbcUrl().substring("jdbc:".length()));
    Loss loss =
        switch (fault) {
          case COMMIT_REPLY -> Loss.COMMIT_REPLY;
          case FLUSH_REPLY -> Loss.FLUSH_REPLY;
          default -> Loss.NONE;
        };
    try (var wire = new NkCreateCommitWire(actualUrl.getHost(), actualUrl.getPort(), loss)) {
      final RecordingPool pool;
      try {
        pool = new RecordingPool(proxiedPool(direct, actualUrl, wire.port()));
      } catch (RuntimeException poolFailure) {
        wire.requireHealthy();
        throw poolFailure;
      }
      try (pool) {
        var result = new CompletableFuture<Audit>();
        PublicationPhase1Postgres.jpa(pool)
            .run(context -> {
              assertThat(context).hasNotFailed();
              var manager = context.getBean(JpaTransactionManager.class);
              assertThat(manager).isExactlyInstanceOf(JpaTransactionManager.class);
              var tx = new TransactionTemplate(manager);
              tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
              tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
              var emf = context.getBean(EntityManagerFactory.class);
              var db = context.getBean(JdbcClient.class);
              wire.arm();
              pool.record = true;
              var progress = new Progress();
              RuntimeException failure = null;
              try {
                if (fault == Fault.POOL) {
                  pool.close();
                }
                tx.executeWithoutResult(status -> {
                  progress.callbackEntered = true;
                  progress.pid = db.sql("select pg_backend_pid()").query(Integer.class).single();
                  progress.xid =
                      db.sql("select pg_current_xact_id()::text").query(String.class).single();
                  progress.isolation =
                      db.sql("show transaction_isolation").query(String.class).single();
                  if (fault == Fault.STATEMENT) {
                    db.sql("select missing_column from nk75_commit_probe").query().listOfRows();
                  }
                  var em =
                      Objects.requireNonNull(
                          EntityManagerFactoryUtils.getTransactionalEntityManager(emf));
                  progress.jpaPid =
                      ((Number) em.createNativeQuery("select pg_backend_pid()").getSingleResult())
                          .intValue();
                  progress.jpaXid =
                      String.valueOf(
                          em.createNativeQuery("select pg_current_xact_id()::text")
                              .getSingleResult());
                  assertThat(progress.jpaPid).isEqualTo(progress.pid);
                  assertThat(progress.jpaXid).isEqualTo(progress.xid);
                  assertThat(progress.isolation).isEqualTo("read committed");
                  em.find(Room.class, roomId).closeIntake();
                  db.sql("insert into nk75_commit_probe(room_id, value) values (?, 11)")
                      .param(roomId)
                      .update();
                  if (deferredConflict) {
                    db.sql("insert into nk75_commit_probe(room_id, value) values (?, 11)")
                        .param(roomId)
                        .update();
                  }
                  em.flush();
                  progress.applicationFlushReturned = true;
                  if (fault == Fault.ROLLBACK_ONLY) {
                    var participating = new TransactionTemplate(manager);
                    participating.executeWithoutResult(inner -> inner.setRollbackOnly());
                  }
                  progress.callbackReturned = true;
                });
              } catch (RuntimeException e) {
                failure = e;
              } finally {
                pool.record = false;
              }
              wire.requireHealthy();
              var boundary =
                  new Boundary(
                      pool.commitEntered,
                      pool.commitReturned,
                      pool.commitFailure,
                      failure,
                      hasParsedBackendError(pool.commitFailure));
              var verdict = classify(boundary);
              // From this point on, inspect the arm and DB for auditing, never to decide the
              // verdict above.
              var xidStatus = observeXid(observer, progress.xid);
              int probeCount =
                  observer
                      .sql("select count(*) from nk75_commit_probe where room_id = ?")
                      .param(roomId)
                      .query(Integer.class)
                      .single();
              var intake =
                  observer
                      .sql("select status from rooms where id = ?")
                      .param(roomId)
                      .query(String.class)
                      .single();
              if (verdict == Verdict.JDBC_RETURNED) {
                assertThat(failure).isNull();
              } else {
                assertThat(failure).as(caseId + " real transaction failure").isNotNull();
              }
              if (pool.commitEntered) {
                assertThat(progress.applicationFlushReturned && progress.callbackReturned).isTrue();
                assertThat(wire.targetExecuted()).as("server actually executed COMMIT").isTrue();
              } else {
                assertThat(wire.commitDispatched()).isFalse();
              }
              if (fault == Fault.FLUSH_REPLY) {
                assertThat(progress.applicationFlushReturned).isFalse();
                assertThat(wire.withheldForAuditOnly()).isEqualTo('C');
              }
              if (fault == Fault.POOL) {
                assertThat(progress.callbackEntered).isFalse();
              }
              if (fault == Fault.ROLLBACK_ONLY) {
                assertThat(pool.rollbackEntered).isTrue();
                assertThat(progress.callbackReturned).isTrue();
              }
              assertThat(probeCount).isEqualTo(xidStatus.equals("committed") ? 1 : 0);
              assertThat(intake).isEqualTo(xidStatus.equals("committed") ? "CLOSED" : "OPEN");
              System.out.printf(
                  "%s phase: callback=%s flushReturned=%s callbackReturned=%s rollback=%s "
                      + "commitEntered=%s commitReturned=%s correlated=%s parsedBackendError=%s verdict=%s; "
                      + "wire withheld=%d; independent PG pid=%d xid=%s final=%s probes=%d intake=%s failure=%s%n",
                  caseId,
                  progress.callbackEntered,
                  progress.applicationFlushReturned,
                  progress.callbackReturned,
                  pool.rollbackEntered,
                  boundary.entered(),
                  boundary.returned(),
                  containsIdentity(boundary.outerFailure(), boundary.commitFailure()),
                  boundary.parsedBackendError(),
                  verdict,
                  (int) wire.withheldForAuditOnly(),
                  progress.pid,
                  progress.xid,
                  xidStatus,
                  probeCount,
                  intake,
                  sqlState(failure));
              observer
                  .sql("delete from nk75_commit_probe where room_id = ?")
                  .param(roomId)
                  .update();
              result.complete(
                  new Audit(
                      boundary,
                      verdict,
                      wire.withheldForAuditOnly(),
                      xidStatus,
                      sqlState(failure)));
            });
        return result.getNow(null);
      }
    } catch (IOException e) {
      throw new AssertionError("Could not create isolated PG wire fixture", e);
    }
  }

  private static Verdict classify(Boundary b) {
    if (b.returned()) {
      return Verdict.JDBC_RETURNED;
    }
    if (!b.entered()) {
      return Verdict.BEFORE_COMMIT;
    }
    if (b.commitFailure() == null || !containsIdentity(b.outerFailure(), b.commitFailure())) {
      throw new AssertionError("No decisive JDBC commit-boundary observation");
    }
    if (b.parsedBackendError()) {
      return Verdict.DEFINITE_COMMIT_REJECTION;
    }
    return Verdict.COMMIT_RESULT_UNKNOWN;
  }

  private static boolean containsIdentity(Throwable outer, Throwable target) {
    for (Throwable error = outer; error != null; error = error.getCause()) {
      if (error == target) return true;
      if (error instanceof SQLException sql) {
        for (SQLException next = sql.getNextException();
            next != null;
            next = next.getNextException()) {
          if (next == target) return true;
        }
      }
    }
    return false;
  }

  private static boolean hasParsedBackendError(Throwable failure) {
    for (Throwable error = failure; error != null; error = error.getCause()) {
      if (error.getClass().getName().equals("org.postgresql.util.PSQLException")) {
        try {
          return error.getClass().getMethod("getServerErrorMessage").invoke(error) != null;
        } catch (ReflectiveOperationException e) {
          throw new AssertionError("pgjdbc ServerErrorMessage API unavailable", e);
        }
      }
    }
    return false;
  }

  private static boolean containsIOException(Throwable failure) {
    for (Throwable error = failure; error != null; error = error.getCause()) {
      if (error instanceof IOException) return true;
    }
    return false;
  }

  private static long seedRoom(JdbcClient db) {
    long owner =
        db.sql(
                """
                insert into users(uuid, display_name, status, created_at, updated_at)
                values (gen_random_uuid(), 'synthetic', 'ACTIVE', now(), now()) returning id
                """)
            .query(Long.class)
            .single();
    long reward =
        db.sql(
                "insert into reward_presets(title, description) values ('synthetic', 'fixture') returning id")
            .query(Long.class)
            .single();
    return db.sql(
            """
            insert into rooms(host_user_id, selected_reward_preset_id, status, created_at, opened_at, closes_at)
            values (?, ?, 'OPEN', now(), now(), now() + interval '24 hours') returning id
            """)
        .params(owner, reward)
        .query(Long.class)
        .single();
  }

  private static String observeXid(JdbcClient db, String xid) {
    if (xid == null) {
      return "none";
    }
    long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
    String status;
    do {
      status =
          db.sql("select pg_xact_status(cast(? as xid8))").param(xid).query(String.class).single();
      if ("committed".equals(status) || "aborted".equals(status)) {
        return status;
      }
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Independent observer did not reach a final DB outcome: " + status);
  }

  private static String sqlState(Throwable failure) {
    for (Throwable error = failure; error != null; error = error.getCause()) {
      if (error instanceof SQLException sql && sql.getSQLState() != null) {
        return sql.getSQLState();
      }
    }
    return null;
  }

  private static HikariConfig proxiedPool(HikariDataSource direct, URI upstream, int port) {
    var config = new HikariConfig();
    var query = upstream.getRawQuery();
    config.setJdbcUrl(
        "jdbc:postgresql://127.0.0.1:"
            + port
            + upstream.getRawPath()
            + (query == null ? "" : "?" + query));
    config.setUsername(direct.getUsername());
    config.setPassword(direct.getPassword());
    config.setSchema(direct.getSchema());
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(5000);
    return config;
  }

  private enum Fault {
    NONE,
    COMMIT_REPLY,
    FLUSH_REPLY,
    POOL,
    STATEMENT,
    ROLLBACK_ONLY
  }

  private enum Verdict {
    COMMIT_RESULT_UNKNOWN,
    DEFINITE_COMMIT_REJECTION,
    BEFORE_COMMIT,
    JDBC_RETURNED
  }

  private record Boundary(
      boolean entered,
      boolean returned,
      Throwable commitFailure,
      Throwable outerFailure,
      boolean parsedBackendError) {}

  private record Audit(
      Boundary boundary, Verdict verdict, char withheld, String finalXid, String failureState) {}

  private static final class Progress {
    private boolean callbackEntered;
    private boolean callbackReturned;
    private boolean applicationFlushReturned;
    private int pid;
    private String xid;
    private int jpaPid;
    private String jpaXid;
    private String isolation;
  }

  private static final class RecordingPool extends HikariDataSource {
    private volatile boolean record;
    private boolean commitEntered;
    private boolean commitReturned;
    private boolean rollbackEntered;
    private Throwable commitFailure;

    RecordingPool(HikariConfig config) {
      super(config);
    }

    @Override
    public Connection getConnection() throws SQLException {
      Connection delegate = super.getConnection();
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                boolean commit = record && method.getName().equals("commit");
                if (commit) {
                  commitEntered = true;
                }
                if (record && method.getName().equals("rollback")) {
                  rollbackEntered = true;
                }
                try {
                  var value = method.invoke(delegate, args);
                  if (commit) {
                    commitReturned = true;
                  }
                  return value;
                } catch (InvocationTargetException e) {
                  if (commit) {
                    commitFailure = e.getCause();
                  }
                  throw e.getCause();
                }
              });
    }
  }
}

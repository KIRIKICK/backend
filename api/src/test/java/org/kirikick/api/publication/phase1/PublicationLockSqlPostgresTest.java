package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.common.infra.DbTimeSource;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.repository.PublicationIdempotencyRepository;
import org.kirikick.api.room.domain.RoomResultState;
import org.kirikick.api.room.domain.RoomStatus;
import org.kirikick.api.room.repository.RoomRepository;
import org.kirikick.api.user.repository.UserRepository;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Timeout(50)
class PublicationLockSqlPostgresTest {

  private static final PublicationPhase1Postgres POSTGRES = new PublicationPhase1Postgres();
  private static final PublicationInput INPUT =
      new PublicationInput(
          PublicationInput.Kind.NK_CREATE, null, 1L, 2L, List.of(5L, 3L, 1L, 4L), 4L);

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.close();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("L1b: User 락 모드에 따라 선점 FK 또는 다음 User 락에서 대기한다")
  void l1bClaimUserFkWaitsOnlyForIncompatibleMode(boolean exclusive) throws SQLException {
    String label = exclusive ? "L1b FOR UPDATE" : "L1/L1b FOR NO KEY UPDATE";
    POSTGRES.inMigratedSchema(
        label,
        (context, pool) -> {
          var jdbc = context.getBean(JdbcClient.class);
          var users = context.getBean(UserRepository.class);
          var records = context.getBean(PublicationIdempotencyRepository.class);
          long user = newUser(jdbc);
          String key = "l1b-claim";
          var tx = transactions(context);
          try (var trial = new Trial(label, pool)) {
            tx.executeWithoutResult(holderStatus -> {
              trial.holder = actor(jdbc);
              if (exclusive) {
                assertThat(
                        jdbc.sql("select id from users where id = ? for update")
                            .param(user)
                            .query(Long.class)
                            .single())
                    .isEqualTo(user);
              } else {
                assertThat(users.lockById(user)).contains(user);
              }
              trial.launch(() ->
                  tx.executeWithoutResult(waiterStatus -> {
                    trial.signalWaiter(actor(jdbc));
                    assertThat(records.tryClaim(user, key, INPUT)).isPresent();
                    // This JDBC INSERT is already physically executed in the waiter transaction.
                    assertThat(
                            jdbc.sql(
                                    """
                                    select count(*) from publication_idempotency
                                    where user_id = ? and request_key = ? and game_id is null
                                    """)
                                .params(user, key)
                                .query(Long.class)
                                .single())
                        .isOne();
                    trial.claimExecuted.complete(true);
                    assertThat(users.lockById(user)).contains(user);
                    waiterStatus.setRollbackOnly();
                  }));
              Wait wait = trial.awaitWait(observation -> true);
              trial.requireHolderSql("from users", exclusive ? "for update" : "for no key update");
              if (exclusive) {
                assertThat(wait.waiterSql()).contains("insert into publication_idempotency");
                assertThat(trial.claimExecuted).isNotCompleted();
              } else {
                assertThat(wait.waiterSql().toLowerCase(Locale.ROOT))
                    .contains("from users", "for no key update");
                assertThat(trial.claimExecuted).isCompletedWithValue(true);
                holderStatus.setRollbackOnly();
              }
              assertThat(
                      trial
                          .observer
                          .sql(
                              """
                              select count(*) from publication_idempotency where user_id = ? and request_key = ?
                              """)
                          .params(user, key)
                          .query(Long.class)
                          .single())
                  .isZero();
            });
            trial.finished(exclusive, false);
            assertThat(trial.claimExecuted).isCompletedWithValue(true);
            assertThat(
                    trial
                        .observer
                        .sql(
                            """
                            select count(*) from publication_idempotency where user_id = ? and request_key = ?
                            """)
                        .params(user, key)
                        .query(Long.class)
                        .single())
                .isZero();
          }
        });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("L2a: Room FK는 대기 후 접수가 닫힌 방에서도 통과할 수 있다")
  void l2aFkWaitThenSuccessDoesNotImplyRoomIsOpen(boolean closesUnderLock) throws SQLException {
    String label = "L1/L2a closesUnderLock=" + closesUnderLock;
    POSTGRES.inMigratedSchema(
        label,
        (context, pool) -> {
          var jdbc = context.getBean(JdbcClient.class);
          var rooms = context.getBean(RoomRepository.class);
          var tx = transactions(context);
          long user = newUser(jdbc);
          var fixture = newRoom(jdbc, user, 300);
          long preset =
              jdbc.sql("insert into question_presets (question_text) values ('l2a') returning id")
                  .query(Long.class)
                  .single();
          var game = new CompletableFuture<Long>();
          try (var trial = new Trial(label, pool)) {
            tx.executeWithoutResult(status -> {
              trial.holder = actor(jdbc);
              assertThat(rooms.lockById(fixture.id())).contains(fixture.id());
              trial.launch(() ->
                  tx.executeWithoutResult(waiterStatus -> {
                    trial.signalWaiter(actor(jdbc));
                    long id =
                        jdbc.sql(
                                """
                                insert into games (room_id, question_preset_id, question_text_snapshot, created_at)
                                values (?, ?, 'l2a game', clock_timestamp()) returning id
                                """)
                            .params(fixture.id(), preset)
                            .query(Long.class)
                            .single();
                    game.complete(id);
                  }));
              Wait wait = trial.awaitWait(observation -> true);
              trial.requireHolderSql("from rooms", "for update");
              assertThat(wait.waiterSql()).contains("insert into games");
              if (closesUnderLock) {
                assertThat(
                        jdbc.sql("update rooms set status='CLOSED' where id = ?")
                            .param(fixture.id())
                            .update())
                    .isOne();
              }
            });
            trial.finished(true, true);
            long id = get(game);
            var row =
                trial
                    .observer
                    .sql(
                        """
                        select g.id game_id, g.room_id, g.question_preset_id, r.status, r.result_state
                        from games g join rooms r on g.room_id = r.id where g.id = ?
                        """)
                    .param(id)
                    .query()
                    .singleRow();
            assertThat(row)
                .containsEntry("game_id", id)
                .containsEntry("room_id", fixture.id())
                .containsEntry("question_preset_id", preset)
                .containsEntry("result_state", "NOT_READY")
                .containsEntry("status", closesUnderLock ? "CLOSED" : "OPEN");
            System.out.printf("%s committed %s%n", label, row);
          }
        });
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("L1/C2a: 락을 얻은 뒤 DB 시각과 Room 상태를 새로 조회한다")
  void c2aReadsIndependentPgClockAndFreshRoomStateAfterLock(boolean crossesDeadline)
      throws SQLException {
    String label = "L1/C2a crossesDeadline=" + crossesDeadline;
    POSTGRES.inMigratedSchema(
        label,
        (context, pool) -> {
          var jdbc = context.getBean(JdbcClient.class);
          var rooms = context.getBean(RoomRepository.class);
          var users = context.getBean(UserRepository.class);
          var clock = context.getBean(DbTimeSource.class);
          var tx = transactions(context);
          long user = newUser(jdbc);
          var fixture = new CompletableFuture<RoomId>();
          var result = new CompletableFuture<RoomRead>();
          try (var trial = new Trial(label, pool)) {
            tx.executeWithoutResult(status -> {
              trial.holder = actor(jdbc);
              trial.launch(() ->
                  tx.executeWithoutResult(waiterStatus -> {
                    trial.signalWaiter(actor(jdbc));
                    RoomId room = get(fixture);
                    var oldRoom = rooms.findById(room.id()).orElseThrow();
                    Instant before = clock.readCurrentTime();
                    assertThat(oldRoom.getStatus()).isEqualTo(RoomStatus.OPEN);
                    assertThat(users.lockById(user)).contains(user);
                    assertThat(rooms.lockById(room.id())).contains(room.id());
                    Instant after = clock.readCurrentTime();
                    var state = rooms.findCurrentState(room.id()).orElseThrow();
                    assertThat(state.getOpenedAt())
                        .isEqualTo(room.closesAt().minus(Duration.ofHours(24)));
                    assertThat(state.getClosesAt()).isEqualTo(room.closesAt());
                    assertThat(state.getResultState()).isEqualTo(RoomResultState.NOT_READY);
                    result.complete(
                        new RoomRead(before, after, oldRoom.getStatus(), state.getStatus()));
                    waiterStatus.setRollbackOnly();
                  }));
              get(trial.waiter);
              RoomId room =
                  requiresNewTransactions(context)
                      .execute(ignored -> newRoom(jdbc, user, crossesDeadline ? 4 : 300));
              assertThat(rooms.lockById(room.id())).contains(room.id());
              fixture.complete(room);
              Wait before = trial.awaitWait(observation -> true);
              assertRoomWait(before);
              trial.requireHolderSql("from rooms", "for update");
              assertThat(before.waitStartedAt()).isBefore(room.closesAt());
              if (crossesDeadline) {
                var after =
                    trial.awaitWait(observation -> !observation.dbTime().isBefore(room.closesAt()));
                assertRoomWait(after);
                assertThat(after.dbTime()).isAfterOrEqualTo(room.closesAt());
              } else {
                assertThat(
                        jdbc.sql("update rooms set status='CLOSED' where id = ?")
                            .param(room.id())
                            .update())
                    .isOne();
              }
            });
            trial.finished(true, false);
            RoomId room = get(fixture);
            RoomRead read = get(result);
            assertThat(read.before()).isBefore(room.closesAt());
            assertThat(read.oldStatus()).isEqualTo(RoomStatus.OPEN);
            if (crossesDeadline) {
              assertThat(read.after()).isAfterOrEqualTo(room.closesAt());
              assertThat(read.currentStatus()).isEqualTo(RoomStatus.OPEN);
            } else {
              assertThat(read.after()).isBefore(room.closesAt());
              assertThat(read.currentStatus()).isEqualTo(RoomStatus.CLOSED);
            }
            System.out.printf(
                "%s room=%d closes=%s read=%s%n", label, room.id(), room.closesAt(), read);
          }
        });
  }

  private static void assertRoomWait(Wait wait) {
    assertThat(wait.waiterSql().toLowerCase(Locale.ROOT)).contains("from rooms", "for update");
  }

  private static TransactionTemplate transactions(AssertableApplicationContext context) {
    var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    tx.setTimeout(19);
    return tx;
  }

  private static TransactionTemplate requiresNewTransactions(AssertableApplicationContext context) {
    var tx = transactions(context);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return tx;
  }

  private static Actor actor(JdbcClient jdbc) {
    return jdbc.sql(
            """
            select pg_backend_pid(), pg_current_xact_id()::text,
                   current_setting('transaction_isolation')
            """)
        .query((r, n) -> new Actor(r.getInt(1), r.getString(2), r.getString(3)))
        .single();
  }

  private static long newUser(JdbcClient jdbc) {
    return jdbc.sql(
            """
            insert into users (uuid, display_name, status, created_at, updated_at)
            values (gen_random_uuid(), 'p1b lock actor', 'ACTIVE', now(), now()) returning id
            """)
        .query(Long.class)
        .single();
  }

  private static RoomId newRoom(JdbcClient jdbc, long user, int secondsToClose) {
    long reward =
        jdbc.sql("insert into reward_presets (title, description) values ('r', 'r') returning id")
            .query(Long.class)
            .single();
    return jdbc.sql(
            """
            with boundary as (select clock_timestamp() + ? * interval '1 second' as closes)
            insert into rooms (host_user_id, selected_reward_preset_id, status, opened_at, closes_at, created_at)
            select ?, ?, 'OPEN', closes - interval '24 hours', closes, clock_timestamp() from boundary
            returning id, closes_at
            """)
        .params(secondsToClose, user, reward)
        .query((r, n) -> new RoomId(r.getLong(1), r.getObject(2, OffsetDateTime.class).toInstant()))
        .single();
  }

  private static <T> T get(Future<T> result) {
    try {
      return result.get(16, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted waiting for actor", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("Actor did not complete with a result", e);
    }
  }

  private static final class Trial implements AutoCloseable {
    private final String label;
    private final Connection observationConnection;
    private final JdbcClient observer;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CompletableFuture<Actor> waiter = new CompletableFuture<>();
    private final CompletableFuture<Boolean> claimExecuted = new CompletableFuture<>();
    private Actor holder;
    private Future<?> work;

    Trial(String label, HikariDataSource pool) {
      this.label = label;
      Connection connection = null;
      try {
        connection = pool.getConnection();
        assertThat(connection.getAutoCommit()).isTrue();
        JdbcClient candidate = JdbcClient.create(new SingleConnectionDataSource(connection, true));
        int observerPid = candidate.sql("select pg_backend_pid()").query(Integer.class).single();
        observationConnection = connection;
        observer = candidate;
        System.out.printf("%s observer pid=%d%n", label, observerPid);
      } catch (SQLException e) {
        closeAfterInitializationFailure(connection, e);
        throw new IllegalStateException("Could not open independent observer connection", e);
      } catch (RuntimeException | Error e) {
        closeAfterInitializationFailure(connection, e);
        throw e;
      }
    }

    private static void closeAfterInitializationFailure(Connection connection, Throwable failure) {
      if (connection == null) {
        return;
      }
      try {
        connection.close();
      } catch (SQLException | RuntimeException | Error closeFailure) {
        failure.addSuppressed(closeFailure);
      }
    }

    void launch(Runnable task) {
      System.out.printf("%s holder=%s autocommit=false%n", label, holder);
      work = worker.submit(task);
    }

    void signalWaiter(Actor actor) {
      System.out.printf("%s waiter=%s autocommit=false%n", label, actor);
      waiter.complete(actor);
    }

    Wait awaitWait(Predicate<Wait> accepts) {
      Actor blocked = get(waiter);
      long deadline = System.nanoTime() + Duration.ofSeconds(7).toNanos();
      Wait last = null;
      while (!Thread.currentThread().isInterrupted() && System.nanoTime() < deadline) {
        last =
            observer
                .sql(
                    """
                    select clock_timestamp(), w.backend_xid::text, w.wait_event_type, w.wait_event,
                           array_to_string(pg_blocking_pids(w.pid), ','), w.query,
                           h.backend_xid::text, h.query,
                           (select min(l.waitstart) from pg_locks l
                            where l.pid = w.pid and not l.granted),
                           (select string_agg(concat_ws(':', l.pid, l.locktype, l.mode, l.granted,
                                      l.transactionid, l.relation::regclass, l.page, l.tuple), ',')
                            from pg_locks l where l.pid in (w.pid, h.pid))
                    from pg_stat_activity w cross join pg_stat_activity h
                    where w.pid = ? and h.pid = ?
                    """)
                .params(blocked.pid(), holder.pid())
                .query((r, n) ->
                    new Wait(
                        r.getObject(1, OffsetDateTime.class).toInstant(),
                        r.getString(2),
                        r.getString(3),
                        r.getString(4),
                        r.getString(5),
                        r.getString(6),
                        r.getString(7),
                        r.getString(8),
                        waitStartedAt(r),
                        r.getString(10)))
                .single();
        if ("Lock".equals(last.waitType())
            && Integer.toString(holder.pid()).equals(last.blocker())
            && last.waitStartedAt() != null
            && accepts.test(last)) {
          System.out.printf("%s observed %s%n", label, last);
          assertThat(last.waiterXid()).isEqualTo(blocked.xid());
          assertThat(last.holderXid()).isEqualTo(holder.xid());
          assertThat(last.waitEvent()).isEqualTo("transactionid");
          assertThat(last.locks())
              .contains(
                  blocked.pid() + ":transactionid:ShareLock:f:" + holder.xid(),
                  holder.pid() + ":transactionid:ExclusiveLock:t:" + holder.xid());
          return last;
        }
        if (work.isDone()) {
          get(work);
          break;
        }
        LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
      }
      throw new AssertionError(label + " missing required wait evidence; last=" + last);
    }

    private static Instant waitStartedAt(ResultSet row) throws SQLException {
      OffsetDateTime value = row.getObject(9, OffsetDateTime.class);
      return value == null ? null : value.toInstant();
    }

    void requireHolderSql(String table, String mode) {
      // The driver's metadata query can be the holder's last statement in pg_stat_activity.
      assertThat(POSTGRES.sqlLog())
          .anySatisfy(line ->
              assertThat(line.toLowerCase(Locale.ROOT)).contains(marker(holder), table, mode));
    }

    void finished(boolean holderCommits, boolean waiterCommits) {
      get(work);
      Actor other = get(waiter);
      var sql = POSTGRES.sqlLog();
      String holderEnd = holderCommits ? "COMMIT" : "ROLLBACK";
      String waiterEnd = waiterCommits ? "COMMIT" : "ROLLBACK";
      int first = termination(sql, holder, holderEnd);
      int second = termination(sql, other, waiterEnd);
      assertThat(first).isNotNegative().isLessThan(second);
      var rows =
          observer
              .sql(
                  """
                  select pid, state, backend_xid, xact_start from pg_stat_activity where pid in (?, ?)
                  """)
              .params(holder.pid(), other.pid())
              .query()
              .listOfRows();
      assertThat(rows)
          .hasSize(2)
          .allSatisfy(row ->
              assertThat(row)
                  .containsEntry("state", "idle")
                  .containsEntry("backend_xid", null)
                  .containsEntry("xact_start", null));
      System.out.printf(
          "%s holder %s then waiter %s; final actor sessions=%s%n",
          label, holderEnd, waiterEnd, rows);
    }

    private static int termination(List<String> sql, Actor actor, String action) {
      for (int i = 0; i < sql.size(); i++) {
        if (sql.get(i).contains(marker(actor)) && sql.get(i).endsWith(": " + action)) return i;
      }
      return -1;
    }

    private static String marker(Actor actor) {
      return "[" + actor.pid() + "] xid=" + actor.xid() + " ";
    }

    @Override
    public void close() {
      try (Connection ignored = observationConnection) {
        worker.shutdownNow();
        try {
          assertThat(worker.awaitTermination(18, TimeUnit.SECONDS))
              .as("waiter terminated")
              .isTrue();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Could not verify waiter termination", e);
        }
        System.out.printf("%s cleanup waiter terminated%n", label);
        Actor other = waiter.getNow(null);
        if (holder != null && other != null) {
          POSTGRES.logActorSql(holder.pid(), other.pid());
        }
      } catch (SQLException e) {
        PublicationPhase1Postgres.recordCleanupFailure(e);
        throw new IllegalStateException("Could not return observer connection", e);
      } catch (RuntimeException | AssertionError e) {
        PublicationPhase1Postgres.recordCleanupFailure(e);
        throw e;
      }
    }
  }

  private record Actor(int pid, String xid, String isolation) {}

  private record Wait(
      Instant dbTime,
      String waiterXid,
      String waitType,
      String waitEvent,
      String blocker,
      String waiterSql,
      String holderXid,
      String holderSql,
      Instant waitStartedAt,
      String locks) {}

  private record RoomId(long id, Instant closesAt) {}

  private record RoomRead(
      Instant before, Instant after, RoomStatus oldStatus, RoomStatus currentStatus) {}
}

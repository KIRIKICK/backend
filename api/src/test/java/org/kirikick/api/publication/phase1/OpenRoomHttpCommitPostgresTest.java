package org.kirikick.api.publication.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.reset;
import static org.mockito.BDDMockito.willAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.service.LoginSessionService;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.infra.PublicationCommitObserver;
import org.kirikick.api.publication.repository.PublicationIdempotencyRepository;
import org.kirikick.api.room.domain.Room;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/** Draft only until it is moved to src/test after the application topology gate is established. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({
  OpenRoomHttpPostgresTest.DomainEntities.class,
  NkCreateHttpCommitProbe.Configuration.class
})
class OpenRoomHttpCommitPostgresTest {
  private static final String F05_KEY = "00000000-0000-4000-8000-000000000005";
  private static final String F09_KEY = "00000000-0000-4000-8000-000000000009";
  private static final String F10_KEY = "00000000-0000-4000-8000-000000000010";
  private static final NkCreateCommitWire WIRE = wire();
  private static final AtomicReference<Fault> NEXT_FAULT = new AtomicReference<>(Fault.NONE);

  private final Map<String, Map<String, Object>> databaseBefore = new LinkedHashMap<>();
  @LocalServerPort private int port;
  @Autowired private ObjectMapper json;
  @Autowired private JdbcClient jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private EntityManager entityManager;
  @Autowired private UserRepository users;
  @Autowired private LoginSessionService logins;
  @MockitoSpyBean private PublicationIdempotencyRepository records;
  @MockitoSpyBean private PublicationCommitObserver commitObserver;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    registry.add("spring.datasource.url", OpenRoomHttpCommitPostgresTest::wireUrl);
    registry.add("spring.datasource.username", PostgresTestDatabase.container()::getUsername);
    registry.add("spring.datasource.password", PostgresTestDatabase.container()::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.default-schema", () -> "nk75_http_commit");
    registry.add("publication.grace", () -> "PT5M");
    registry.add("spring.datasource.hikari.connection-timeout", () -> 250);
  }

  @BeforeEach
  void scheduleRealFaultAfterReservation() throws Exception {
    WIRE.arm(NkCreateCommitWire.Loss.NONE);
    WIRE.disarm();
    installCommitGate();
    PublicationIdempotencyRepository target = AopTestUtils.getTargetObject(records);
    willAnswer(
            invocation -> {
              NkCreateHttpCommitProbe.observeTransaction();
              NkCreateHttpCommitProbe.Case active = NkCreateHttpCommitProbe.active();
              if (active != null && "F04_STATEMENT".equals(active.id())) {
                NkCreateHttpCommitProbe.captureJdbcState(jdbc);
                jdbc.sql("select 1 /* nk75_statement_fault */").query(Integer.class).single();
              }
              Object result = invocation.callRealMethod();
              NkCreateHttpCommitProbe.captureJdbcState(jdbc);
              Fault fault = NEXT_FAULT.get();
              if (fault == Fault.ROLLBACK_ONLY) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
              }
              return result;
            })
        .given(target)
        .reserveNkCreate(anyLong(), anyString(), any(PublicationInput.class));
    willAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              NkCreateHttpCommitProbe.Case testCase = NkCreateHttpCommitProbe.active();
              if (testCase != null
                  && ("F09".equals(testCase.id()) || "F10".equals(testCase.id()))) {
                jdbc.sql("insert into nk75_test.commit_gate(case_id) values (:caseId), (:caseId)")
                    .param("caseId", testCase.id())
                    .update();
              }
              return result;
            })
        .given(target)
        .completeClaim(any(), anyLong(), any());
    willAnswer(
            invocation -> {
              NkCreateHttpCommitProbe.Case testCase = NkCreateHttpCommitProbe.active();
              try (NkCreateHttpCommitProbe.PoolBorrow ignored =
                  testCase != null && "F04".equals(testCase.id())
                      ? NkCreateHttpCommitProbe.borrowPool(dataSource)
                      : null) {
                return invocation.callRealMethod();
              } catch (Throwable failure) {
                NkCreateHttpCommitProbe.observerFailure(failure);
                throw failure;
              }
            })
        .given(commitObserver)
        .observe(any());
  }

  @AfterEach
  void resetFaults() {
    WIRE.disarm();
    NEXT_FAULT.set(Fault.NONE);
    reset(records);
  }

  @AfterAll
  static void close() throws Exception {
    WIRE.close();
  }

  @Test
  @DisplayName("F-05: 실제 COMMIT 완료 응답 유실은 결과 불명이며 감사 DB에는 공개가 남는다")
  void returnsUnavailableForWithheldCommitCompletion() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture();
    NEXT_FAULT.set(Fault.COMMIT_REPLY);
    NkCreateHttpCommitProbe.Case initial =
        NkCreateHttpCommitProbe.arm("F05", F05_KEY, pid -> WIRE.arm(pid, Fault.COMMIT_REPLY.loss));
    HttpResponse<String> response = post(fixture, F05_KEY);
    List<NkCreateCommitWire.WireEvent> initialEvents = WIRE.events();
    recordEvidence("F05", F05_KEY, fixture, initial, initialEvents, response);
    String expiresAtBeforeReplay = auditRecordExpiresAt(F05_KEY);
    JsonNode originalResult = json.valueToTree(auditPublishedResult(F05_KEY));
    WIRE.requireHealthy();
    assertThat(initialEvents)
        .extracting(NkCreateCommitWire.WireEvent::event)
        .contains("COMMIT_FORWARDED", "BACKEND_REPLY_WITHHELD");
    WIRE.disarm();
    NkCreateHttpCommitProbe.disarm(F05_KEY);
    NkCreateHttpCommitProbe.Case replayProbe =
        NkCreateHttpCommitProbe.arm(
            "F05_REPLAY", F05_KEY, pid -> WIRE.arm(pid, NkCreateCommitWire.Loss.NONE));
    HttpResponse<String> replay = post(fixture, F05_KEY);
    recordEvidence("F05_REPLAY", F05_KEY, fixture, replayProbe, WIRE.events(), replay);
    assertError(response, 503, "PUBLICATION_UNAVAILABLE");
    assertCommitBoundary(initial, initialEvents, 'C', true);
    assertThat(auditCount(F05_KEY)).isEqualTo(1L);
    assertThat(replay.statusCode()).withFailMessage(replay.body()).isEqualTo(200);
    assertThat(auditRecordExpiresAt(F05_KEY)).isEqualTo(expiresAtBeforeReplay);
    assertThat(json.readTree(replay.body())).isEqualTo(originalResult);
  }

  @Test
  @DisplayName("F-09: 실제 COMMIT 거절 응답 유실은 결과 불명이며 감사 DB에는 공개가 남지 않는다")
  void returnsUnavailableForWithheldCommitRejection() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture();
    long oldRoomId = insertExpiredOpenRoom(fixture);
    NEXT_FAULT.set(Fault.COMMIT_REPLY);
    NkCreateHttpCommitProbe.Case initial =
        NkCreateHttpCommitProbe.arm("F09", F09_KEY, pid -> WIRE.arm(pid, Fault.COMMIT_REPLY.loss));
    HttpResponse<String> response = post(fixture, F09_KEY);
    List<NkCreateCommitWire.WireEvent> initialEvents = WIRE.events();
    recordEvidence("F09", F09_KEY, fixture, initial, initialEvents, response);
    assertAbortedDatabase(F09_KEY, fixture, initial);
    assertCommitBoundary(initial, initialEvents, 'E', true);
    assertError(response, 503, "PUBLICATION_UNAVAILABLE");
    assertThat(initial.finalFlushReturned()).isTrue();
    assertThat(initial.beforeCommit()).isTrue();
    assertThat(initial.backendPid()).isPositive();
    assertThat(initial.xid()).isNotBlank();
    assertThat(initial.commitEntered()).isTrue();
    assertThat(initial.commitReturned()).isFalse();
    assertThat(WIRE.commitDispatched()).isTrue();
    assertThat(WIRE.withheldForAuditOnly()).isEqualTo('E');
    assertThat(initialEvents)
        .extracting(NkCreateCommitWire.WireEvent::event)
        .contains("COMMIT_FORWARDED", "BACKEND_REPLY_WITHHELD");
    assertThat(auditCount(F09_KEY)).isZero();
    assertThat(roomStatus(oldRoomId)).isEqualTo("OPEN");
  }

  @Test
  @DisplayName("F-10: 전달된 실제 COMMIT 거절은 내부 오류이며 공개가 남지 않는다")
  void returnsInternalErrorForDeliveredCommitRejection() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture();
    long oldRoomId = insertExpiredOpenRoom(fixture);
    NkCreateHttpCommitProbe.Case initial =
        NkCreateHttpCommitProbe.arm(
            "F10", F10_KEY, pid -> WIRE.arm(pid, NkCreateCommitWire.Loss.NONE));
    HttpResponse<String> response = post(fixture, F10_KEY);
    recordEvidence("F10", F10_KEY, fixture, initial, WIRE.events(), response);
    assertAbortedDatabase(F10_KEY, fixture, initial);
    assertCommitBoundary(initial, WIRE.events(), 'E', false);
    assertError(response, 500, "INTERNAL_ERROR");
    assertThat(initial.finalFlushReturned()).isTrue();
    assertThat(initial.beforeCommit()).isTrue();
    assertThat(initial.backendPid()).isPositive();
    assertThat(initial.xid()).isNotBlank();
    assertThat(initial.commitEntered()).isTrue();
    assertThat(initial.commitReturned()).isFalse();
    assertThat(auditCount(F10_KEY)).isZero();
    assertThat(roomStatus(oldRoomId)).isEqualTo("OPEN");
  }

  @Test
  @DisplayName("F-06: 물리 COMMIT 전 flush 응답 유실은 결과 불명으로 바꾸지 않는다")
  void returnsInternalErrorForFlushReplyLoss() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    insertExpiredOpenRoom(fixture);
    NkCreateHttpCommitProbe.Case initial =
        NkCreateHttpCommitProbe.arm(
            "F06", key, pid -> WIRE.arm(pid, Fault.FLUSH_REPLY.loss), ignored -> {});
    HttpResponse<String> response = post(fixture, key);
    List<NkCreateCommitWire.WireEvent> initialEvents = WIRE.events();
    recordEvidence("F06", key, fixture, initial, initialEvents, response);
    assertAbortedDatabase(key, fixture, initial);
    assertError(response, 500, "INTERNAL_ERROR");
    assertThat(WIRE.commitDispatched()).isFalse();
    assertThat(initialEvents)
        .extracting(NkCreateCommitWire.WireEvent::event)
        .contains("BACKEND_REPLY_WITHHELD");
  }

  @Test
  @DisplayName("F-11: JpaTransactionManager doCommit flush reply loss skips physical commit")
  void returnsInternalErrorWhenJpaCommitFlushReplyIsLost() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture();
    long pendingRoomId = insertUnrelatedOpenRoom(fixture);
    String key = UUID.randomUUID().toString();
    NkCreateHttpCommitProbe.Case initial = NkCreateHttpCommitProbe.arm("F11", key, ignored -> {});
    initial.flushAtJpaTransactionManagerCommit(() -> {
      entityManager.find(Room.class, pendingRoomId).closeIntake();
      WIRE.arm(initial.backendPid(), Fault.FLUSH_REPLY.loss);
    });

    HttpResponse<String> response = post(fixture, key);
    List<NkCreateCommitWire.WireEvent> initialEvents = WIRE.events();
    recordEvidence("F11", key, fixture, initial, initialEvents, response);

    assertAbortedDatabase(key, fixture, initial);
    assertError(response, 500, "INTERNAL_ERROR");
    assertThat(initial.beforeCommit()).isTrue();
    assertThat(initial.jpaTransactionManagerCommitEntered()).isTrue();
    assertThat(initial.finalFlushReturned()).isFalse();
    assertThat(initial.commitEntered()).isFalse();
    assertThat(WIRE.commitDispatched()).isFalse();
    assertThat(roomStatus(pendingRoomId)).isEqualTo("OPEN");
    assertThat(initialEvents)
        .extracting(NkCreateCommitWire.WireEvent::event)
        .contains("BACKEND_REPLY_WITHHELD");
    long pendingChange = eventTime(initial.events(), "BEFORE_COMMIT_PENDING_CHANGE");
    long doCommit = eventTime(initial.events(), "JPA_TRANSACTION_MANAGER_DO_COMMIT_ENTER");
    long priorFlushReturned = lastEventBefore(initial.events(), "FLUSH_RETURNED", doCommit);
    long implicitFlushEntered = firstEventAfter(initial.events(), "FLUSH_ENTER", doCommit);
    assertThat(pendingChange).isLessThan(doCommit);
    assertThat(priorFlushReturned).isLessThan(doCommit);
    assertThat(doCommit).isLessThan(implicitFlushEntered);
    NkCreateCommitWire.WireEvent withheld =
        initialEvents.stream()
            .filter(event -> event.event().equals("BACKEND_REPLY_WITHHELD"))
            .findFirst()
            .orElseThrow();
    assertThat(withheld.pid()).isEqualTo(initial.backendPid());
    assertThat(withheld.sql()).isEqualTo("UPDATE_ROOMS");
    assertThat(withheld.backendType()).isEqualTo("C");
    assertThat(implicitFlushEntered).isLessThan(withheld.nanoTime());
  }

  @Test
  @DisplayName("F-08: rollback-only는 결과 불명으로 바꾸지 않는다")
  void returnsInternalErrorForRollbackOnly() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    NkCreateHttpCommitProbe.Case initial =
        NkCreateHttpCommitProbe.arm(
            "F08", key, pid -> WIRE.arm(pid, NkCreateCommitWire.Loss.NONE), ignored -> {});
    NEXT_FAULT.set(Fault.ROLLBACK_ONLY);
    HttpResponse<String> response = post(fixture, key);
    recordEvidence("F08", key, fixture, initial, WIRE.events(), response);
    assertAbortedDatabase(key, fixture, initial);
    assertError(response, 500, "INTERNAL_ERROR");
    assertThat(initial.commitEntered()).isFalse();
    assertThat(WIRE.commitDispatched()).isFalse();
    assertThat(auditCount(key)).isZero();
  }

  @Test
  @DisplayName("F-04: publication transaction pool acquisition failure is a generic internal error")
  void returnsInternalErrorForPublicationPoolAcquisitionFailure() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    NkCreateHttpCommitProbe.Case initial = NkCreateHttpCommitProbe.arm("F04", key, ignored -> {});
    HttpResponse<String> response;
    response = post(fixture, key);
    recordEvidence("F04", key, fixture, initial, WIRE.events(), response);
    assertAbortedDatabase(key, fixture, initial);
    assertError(response, 500, "INTERNAL_ERROR");
    assertThat(initial.commitEntered()).isFalse();
    assertThat(auditCount(key)).isZero();
  }

  @Test
  @DisplayName("F-04 statement: actual driver statement reply loss is generic internal error")
  void returnsInternalErrorForStatementReplyLoss() throws Exception {
    assertPreCommitConnectionLoss("F04_STATEMENT", NkCreateCommitWire.Loss.STATEMENT_REPLY);
  }

  @Test
  @DisplayName("F-04 flush: actual Hibernate flush connection loss is generic internal error")
  void returnsInternalErrorForHibernateFlushReplyLoss() throws Exception {
    assertPreCommitConnectionLoss("F04_FLUSH", NkCreateCommitWire.Loss.FLUSH_REPLY);
  }

  private void assertPreCommitConnectionLoss(String caseId, NkCreateCommitWire.Loss loss)
      throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    NkCreateHttpCommitProbe.Case initial =
        NkCreateHttpCommitProbe.arm(caseId, key, pid -> WIRE.arm(pid, loss), ignored -> {});
    HttpResponse<String> response = post(fixture, key);
    recordEvidence(caseId, key, fixture, initial, WIRE.events(), response);
    assertAbortedDatabase(key, fixture, initial);
    WIRE.requireHealthy();
    assertError(response, 500, "INTERNAL_ERROR");
    assertThat(initial.commitEntered()).isFalse();
    assertThat(WIRE.commitDispatched()).isFalse();
    assertThat(WIRE.events())
        .extracting(NkCreateCommitWire.WireEvent::event)
        .contains("BACKEND_REPLY_WITHHELD");
    if ("F04_FLUSH".equals(caseId)) assertThat(initial.finalFlushReturned()).isFalse();
    assertThat(auditCount(key)).isZero();
  }

  @Test
  @DisplayName("F-07: post-COMMIT response delivery failure preserves a replayable success")
  void commitsBeforePostCommitResponseDeliveryFailure() throws Exception {
    NkCreateHttpFixture.Fixture fixture = fixture();
    String key = UUID.randomUUID().toString();
    NkCreateHttpCommitProbe.Case initial =
        NkCreateHttpCommitProbe.armResponseLoss(
            "F07", key, pid -> WIRE.arm(pid, NkCreateCommitWire.Loss.NONE));
    Exception deliveryFailure = null;
    HttpResponse<String> response = null;
    try {
      response = post(fixture, key);
    } catch (Exception exception) {
      deliveryFailure = exception;
    }
    List<NkCreateCommitWire.WireEvent> initialEvents = WIRE.events();
    recordEvidence("F07", key, fixture, initial, initialEvents, response);
    assertThat(deliveryFailure != null || response != null && response.body().isBlank()).isTrue();
    assertThat(initial.commitEntered()).isTrue();
    assertThat(initial.commitReturned()).isTrue();
    assertThat(initial.responseWriteFailed()).isTrue();
    assertThat(initial.successWriteBeforeCommitReturned()).isFalse();
    assertThat(auditCount(key)).isEqualTo(1L);
    assertThat(auditSnapshot(key, fixture, initial).get("xidStatus")).isEqualTo("committed");
    JsonNode originalResult = json.valueToTree(auditPublishedResult(key));
    String expiresAt = auditRecordExpiresAt(key);
    NkCreateHttpCommitProbe.disarm(key);
    NkCreateHttpCommitProbe.Case replayProbe =
        NkCreateHttpCommitProbe.arm(
            "F07_REPLAY", key, pid -> WIRE.arm(pid, NkCreateCommitWire.Loss.NONE));
    HttpResponse<String> replay = post(fixture, key);
    recordEvidence("F07_REPLAY", key, fixture, replayProbe, WIRE.events(), replay);
    assertThat(replay.statusCode()).withFailMessage(replay.body()).isEqualTo(200);
    assertThat(json.readTree(replay.body())).isEqualTo(originalResult);
    assertThat(auditRecordExpiresAt(key)).isEqualTo(expiresAt);
  }

  private NkCreateHttpFixture.Fixture fixture() throws Exception {
    return new NkCreateHttpFixture(port, json, jdbc, users, logins).fixture();
  }

  private HttpResponse<String> post(NkCreateHttpFixture.Fixture fixture, String key)
      throws Exception {
    databaseBefore.put(key, auditSnapshot(key, fixture, null));
    return new NkCreateHttpFixture(port, json, jdbc, users, logins)
        .post(
            fixture, key, fixture.choiceIds(), fixture.choiceIds().getFirst(), fixture.rewardId());
  }

  private void assertError(HttpResponse<String> response, int status, String code)
      throws Exception {
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
    JsonNode body = json.readTree(response.body());
    assertThat(body.required("code").textValue()).isEqualTo(code);
    assertThat(response.headers().firstValue("Retry-After")).isEmpty();
  }

  private void installCommitGate() {
    jdbc.sql("create schema if not exists nk75_test").update();
    jdbc.sql(
            """
            create table if not exists nk75_test.commit_gate (
              case_id text not null unique deferrable initially deferred
            )
            """)
        .update();
    jdbc.sql("delete from nk75_test.commit_gate").update();
  }

  private long insertExpiredOpenRoom(NkCreateHttpFixture.Fixture fixture) {
    return jdbc.sql(
            """
            insert into rooms(host_user_id, selected_reward_preset_id, status, result_state, opened_at, closes_at, created_at)
            values (:userId, :rewardId, 'OPEN', 'NOT_READY', now() - interval '1 hour', now() - interval '1 microsecond', now())
            returning id
            """)
        .param("userId", fixture.user().getId())
        .param("rewardId", fixture.rewardId())
        .query(Long.class)
        .single();
  }

  private long insertUnrelatedOpenRoom(NkCreateHttpFixture.Fixture fixture) {
    User other =
        users.saveAndFlush(User.create("nk" + UUID.randomUUID().toString().substring(0, 6), null));
    return jdbc.sql(
            """
            insert into rooms(host_user_id, selected_reward_preset_id, status, result_state, opened_at, closes_at, created_at)
            values (:userId, :rewardId, 'OPEN', 'NOT_READY', now(), now() + interval '1 hour', now())
            returning id
            """)
        .param("userId", other.getId())
        .param("rewardId", fixture.rewardId())
        .query(Long.class)
        .single();
  }

  private String roomStatus(long roomId) {
    return jdbc.sql("select status from rooms where id = :roomId")
        .param("roomId", roomId)
        .query(String.class)
        .single();
  }

  private void recordEvidence(
      String caseId,
      String key,
      NkCreateHttpFixture.Fixture fixture,
      NkCreateHttpCommitProbe.Case testCase,
      List<NkCreateCommitWire.WireEvent> events,
      HttpResponse<String> response)
      throws Exception {
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("case", caseId);
    evidence.put("requestKey", key);
    Map<String, Object> probe = new LinkedHashMap<>();
    probe.put("backendPid", testCase.backendPid());
    probe.put("physicalCommitPid", testCase.physicalCommitPid());
    probe.put("xid", testCase.xid());
    probe.put("finalFlushReturned", testCase.finalFlushReturned());
    probe.put("beforeCommit", testCase.beforeCommit());
    probe.put("jpaTransactionManagerCommitEntered", testCase.jpaTransactionManagerCommitEntered());
    probe.put("commitEntered", testCase.commitEntered());
    probe.put("commitReturned", testCase.commitReturned());
    probe.put(
        "commitFailureType",
        testCase.commitFailure() == null ? null : testCase.commitFailure().getClass().getName());
    probe.put(
        "commitFailureSqlState",
        testCase.commitFailure() == null ? null : testCase.commitFailure().getSQLState());
    probe.put("exceptionChain", testCase.exceptionChain());
    probe.put("observerSnapshot", testCase.observerSnapshot());
    probe.put("responseWriteFailed", testCase.responseWriteFailed());
    probe.put("events", testCase.events());
    probe.put("commitFailureCorrelated", testCase.commitFailureCorrelated());
    probe.put(
        "commitFailureIdentity",
        testCase.commitFailure() == null
            ? null
            : System.identityHashCode(testCase.commitFailure()));
    probe.put("successWriteBeforeCommitReturned", testCase.successWriteBeforeCommitReturned());
    evidence.put("probe", probe);
    evidence.put("wire", events);
    Map<String, Object> http = new LinkedHashMap<>();
    http.put("status", response == null ? null : response.statusCode());
    http.put("body", response == null ? null : response.body());
    http.put(
        "retryAfter",
        response == null ? null : response.headers().firstValue("Retry-After").orElse(null));
    evidence.put("http", http);
    evidence.put("databaseBefore", databaseBefore.get(key));
    evidence.put("database", auditSnapshot(key, fixture, testCase));
    String evidenceDirectory = System.getenv("NK75_HTTP_COMMIT_EVIDENCE_DIR");
    if (evidenceDirectory == null || evidenceDirectory.isBlank()) {
      evidenceDirectory =
          System.getProperty("NK75_HTTP_COMMIT_EVIDENCE_DIR", "build/reports/nk75-http-commit");
    }
    Path directory = Path.of(evidenceDirectory);
    Files.createDirectories(directory);
    json.writerWithDefaultPrettyPrinter()
        .writeValue(directory.resolve(caseId + "-" + key + ".json").toFile(), evidence);
    WIRE.requireHealthy();
  }

  private Map<String, Object> auditSnapshot(
      String key, NkCreateHttpFixture.Fixture fixture, NkCreateHttpCommitProbe.Case testCase)
      throws SQLException {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    try (Connection connection =
        DriverManager.getConnection(
            databaseUrl(),
            PostgresTestDatabase.container().getUsername(),
            PostgresTestDatabase.container().getPassword())) {
      snapshot.put(
          "publicationRecords",
          count(
              connection,
              "select count(*) from publication_idempotency where request_key = ?",
              key));
      snapshot.put(
          "rooms",
          count(
              connection,
              "select count(*) from rooms where host_user_id = ?",
              fixture.user().getId()));
      snapshot.put(
          "games",
          count(
              connection,
              "select count(*) from games g join rooms r on r.id = g.room_id where r.host_user_id = ?",
              fixture.user().getId()));
      snapshot.put(
          "choices",
          count(
              connection,
              "select count(*) from choices c join games g on g.id = c.game_id join rooms r on r.id = g.room_id where r.host_user_id = ?",
              fixture.user().getId()));
      snapshot.put(
          "recordExpiresAt",
          scalar(
              connection,
              "select record_expires_at::text from publication_idempotency where request_key = ?",
              key));
      snapshot.put("xidStatus", xidStatus(connection, testCase == null ? null : testCase.xid()));
      snapshot.put(
          "recordRows",
          auditRows(
              connection,
              "select claim_id, game_id, record_expires_at from publication_idempotency where request_key = ?",
              key));
      snapshot.put(
          "roomRows",
          auditRows(
              connection,
              "select id, status, result_state, opened_at, closes_at from rooms where host_user_id = ? order by id",
              fixture.user().getId()));
      snapshot.put(
          "gameRows",
          auditRows(
              connection,
              "select g.id, g.room_id from games g join rooms r on r.id = g.room_id where r.host_user_id = ? order by g.id",
              fixture.user().getId()));
      snapshot.put(
          "choiceRows",
          auditRows(
              connection,
              "select c.id, c.game_id from choices c join games g on g.id = c.game_id join rooms r on r.id = g.room_id where r.host_user_id = ? order by c.id",
              fixture.user().getId()));
    }
    return snapshot;
  }

  private void assertAbortedDatabase(
      String key, NkCreateHttpFixture.Fixture fixture, NkCreateHttpCommitProbe.Case testCase)
      throws SQLException {
    Map<String, Object> after = auditSnapshot(key, fixture, testCase);
    Map<String, Object> before = databaseBefore.get(key);
    for (String field :
        List.of(
            "publicationRecords",
            "rooms",
            "games",
            "choices",
            "recordRows",
            "roomRows",
            "gameRows",
            "choiceRows")) {
      assertThat(after.get(field)).as(field).isEqualTo(before.get(field));
    }
    if (testCase.xid() != null) assertThat(after.get("xidStatus")).isEqualTo("aborted");
  }

  private List<Map<String, String>> auditRows(Connection connection, String sql, Object parameter)
      throws SQLException {
    List<Map<String, String>> result = new ArrayList<>();
    try (var statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          Map<String, String> row = new LinkedHashMap<>();
          for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++)
            row.put(rows.getMetaData().getColumnLabel(i), rows.getString(i));
          result.add(row);
        }
      }
    }
    return result;
  }

  private Map<String, Object> auditPublishedResult(String key) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                databaseUrl(),
                PostgresTestDatabase.container().getUsername(),
                PostgresTestDatabase.container().getPassword());
        var statement =
            connection.prepareStatement(
                "select r.id, g.id, r.opened_at, r.closes_at from publication_idempotency p join games g on g.id = p.game_id join rooms r on r.id = g.room_id where p.request_key = ?")) {
      statement.setString(1, key);
      try (ResultSet row = statement.executeQuery()) {
        assertThat(row.next()).isTrue();
        return Map.of(
            "roomId",
            row.getString(1),
            "gameId",
            row.getString(2),
            "openedAt",
            row.getTimestamp(3).toInstant().toString(),
            "closesAt",
            row.getTimestamp(4).toInstant().toString());
      }
    }
  }

  private long eventTime(List<NkCreateHttpCommitProbe.ProbeEvent> events, String event) {
    return events.stream()
        .filter(probeEvent -> probeEvent.event().equals(event))
        .mapToLong(NkCreateHttpCommitProbe.ProbeEvent::nanoTime)
        .findFirst()
        .orElseThrow();
  }

  private long lastEventBefore(
      List<NkCreateHttpCommitProbe.ProbeEvent> events, String event, long boundary) {
    return events.stream()
        .filter(probeEvent -> probeEvent.event().equals(event) && probeEvent.nanoTime() < boundary)
        .mapToLong(NkCreateHttpCommitProbe.ProbeEvent::nanoTime)
        .max()
        .orElseThrow();
  }

  private long firstEventAfter(
      List<NkCreateHttpCommitProbe.ProbeEvent> events, String event, long boundary) {
    return events.stream()
        .filter(probeEvent -> probeEvent.event().equals(event) && probeEvent.nanoTime() > boundary)
        .mapToLong(NkCreateHttpCommitProbe.ProbeEvent::nanoTime)
        .min()
        .orElseThrow();
  }

  private void assertCommitBoundary(
      NkCreateHttpCommitProbe.Case probe,
      List<NkCreateCommitWire.WireEvent> events,
      char reply,
      boolean withheld) {
    WIRE.requireHealthy();
    assertThat(probe.finalFlushReturned()).isTrue();
    assertThat(probe.beforeCommit()).isTrue();
    assertThat(probe.backendPid()).isPositive();
    assertThat(probe.physicalCommitPid()).isEqualTo(probe.backendPid());
    assertThat(probe.xid()).isNotBlank();
    assertThat(probe.commitEntered()).isTrue();
    assertThat(probe.commitReturned()).isFalse();
    assertThat((Throwable) probe.commitFailure()).isNotNull();
    assertThat(probe.commitFailureCorrelated()).isTrue();
    assertThat(probe.successWriteBeforeCommitReturned()).isFalse();
    NkCreateCommitWire.WireEvent commit =
        events.stream().filter(e -> e.event().equals("COMMIT_FORWARDED")).findFirst().orElseThrow();
    NkCreateCommitWire.WireEvent response =
        events.stream()
            .filter(e ->
                e.sql().equals("COMMIT")
                    && e.backendType().equals(Character.toString(reply))
                    && e.event()
                        .equals(withheld ? "BACKEND_REPLY_WITHHELD" : "BACKEND_REPLY_FORWARDED"))
            .findFirst()
            .orElseThrow();
    if (reply == 'E') {
      assertThat(events)
          .anyMatch(e ->
              e.event().equals("BACKEND_ERROR")
                  && e.exchange() == commit.exchange()
                  && e.detail().equals("23505"));
      if (withheld) {
        assertThat(probe.observerSnapshot()).isNotNull();
        assertThat(probe.observerSnapshot().parsedBackendError()).isFalse();
        assertThat(probe.observerSnapshot().transportFailure()).isTrue();
      } else {
        assertThat(probe.commitFailure().getSQLState()).isEqualTo("23505");
      }
    }
    long lastFlush =
        probe.events().stream()
            .filter(e -> e.event().equals("FLUSH_RETURNED"))
            .mapToLong(NkCreateHttpCommitProbe.ProbeEvent::nanoTime)
            .max()
            .orElseThrow();
    long entered =
        probe.events().stream()
            .filter(e -> e.event().equals("PHYSICAL_COMMIT_ENTER"))
            .mapToLong(NkCreateHttpCommitProbe.ProbeEvent::nanoTime)
            .findFirst()
            .orElseThrow();
    long failed =
        probe.events().stream()
            .filter(e -> e.event().equals("PHYSICAL_COMMIT_THROW"))
            .mapToLong(NkCreateHttpCommitProbe.ProbeEvent::nanoTime)
            .findFirst()
            .orElseThrow();
    assertThat(events).allMatch(e -> e.pid() == probe.backendPid());
    assertThat(response.exchange()).isEqualTo(commit.exchange());
    assertThat(lastFlush).isLessThan(entered);
    assertThat(entered).isLessThan(commit.nanoTime());
    assertThat(commit.nanoTime()).isLessThan(response.nanoTime());
    assertThat(response.nanoTime()).isLessThan(failed);
  }

  private long count(Connection connection, String sql, Object value) throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      statement.setObject(1, value);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  private String scalar(Connection connection, String sql, String value) throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      statement.setString(1, value);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  private String xidStatus(Connection connection, String xid) throws SQLException {
    return xid == null ? null : scalar(connection, "select pg_xact_status(?::xid8)::text", xid);
  }

  private String auditRecordExpiresAt(String key) throws SQLException {
    try (Connection connection =
        DriverManager.getConnection(
            databaseUrl(),
            PostgresTestDatabase.container().getUsername(),
            PostgresTestDatabase.container().getPassword())) {
      return scalar(
          connection,
          "select record_expires_at::text from publication_idempotency where request_key = ?",
          key);
    }
  }

  private long auditCount(String key) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                databaseUrl(),
                PostgresTestDatabase.container().getUsername(),
                PostgresTestDatabase.container().getPassword());
        var statement =
            connection.prepareStatement(
                "select count(*) from publication_idempotency where request_key = ?")) {
      statement.setString(1, key);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  private static NkCreateCommitWire wire() {
    URI target = URI.create(databaseUrl().substring("jdbc:".length()));
    try {
      return new NkCreateCommitWire(
          target.getHost(), target.getPort(), NkCreateCommitWire.Loss.NONE);
    } catch (Exception exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static String wireUrl() {
    URI target = URI.create(databaseUrl().substring("jdbc:".length()));
    String query = target.getRawQuery() == null ? "" : "?" + target.getRawQuery();
    return "jdbc:postgresql://127.0.0.1:" + WIRE.port() + target.getRawPath() + query;
  }

  private static String databaseUrl() {
    return NkCreateHttpFixture.withCurrentSchema(
        PostgresTestDatabase.container().getJdbcUrl(), "nk75_http_commit");
  }

  private enum Fault {
    NONE(null),
    COMMIT_REPLY(NkCreateCommitWire.Loss.COMMIT_REPLY),
    FLUSH_REPLY(NkCreateCommitWire.Loss.FLUSH_REPLY),
    ROLLBACK_ONLY(null);

    private final NkCreateCommitWire.Loss loss;

    Fault(NkCreateCommitWire.Loss loss) {
      this.loss = loss;
    }
  }
}

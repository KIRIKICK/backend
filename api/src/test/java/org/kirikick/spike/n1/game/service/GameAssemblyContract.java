package org.kirikick.spike.n1.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.user.domain.User;
import org.kirikick.api.user.repository.UserRepository;
import org.kirikick.spike.n1.game.domain.Choice;
import org.kirikick.spike.n1.game.domain.Game;
import org.kirikick.spike.n1.game.repository.ChoiceRepository;
import org.kirikick.spike.n1.game.repository.GameRepository;
import org.kirikick.spike.n1.reward.domain.RewardPreset;
import org.kirikick.spike.n1.reward.repository.RewardPresetRepository;
import org.kirikick.spike.n1.room.domain.Room;
import org.kirikick.spike.n1.room.repository.RoomRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({GameAssemblyService.class, GameAssemblyContract.ClockConfiguration.class})
abstract class GameAssemblyContract {

  private static final Instant INITIAL_TIME = Instant.parse("2026-11-15T09:00:00Z");

  @Autowired private GameAssemblyService assembly;
  @Autowired private UserRepository users;
  @Autowired private RewardPresetRepository rewards;
  @Autowired private RoomRepository rooms;
  @Autowired private GameRepository games;
  @Autowired private ChoiceRepository choices;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private MutableClock clock;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;

  private Long hostId;
  private Long rewardId;

  @BeforeEach
  void preparePersistedReferences() {
    clock.now = INITIAL_TIME;
    clock.beforeRead = () -> {};
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          hostId = users.save(User.create("host", null)).getId();
          rewardId = rewards.save(RewardPreset.create("보상", "보상 설명")).getId();
        });
  }

  @AfterEach
  void removeOnlyThisTestsAssembly() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          jdbc.update(
              "update games set correct_choice_id = null where room_id in (select id from rooms"
                  + " where host_user_id = ?)",
              hostId);
          jdbc.update(
              "delete from choices where game_id in (select g.id from games g join rooms r on r.id"
                  + " = g.room_id where r.host_user_id = ?)",
              hostId);
          jdbc.update(
              "delete from games where room_id in (select id from rooms where host_user_id = ?)",
              hostId);
          jdbc.update("delete from rooms where host_user_id = ?", hostId);
          jdbc.update("delete from reward_presets where id = ?", rewardId);
          jdbc.update("delete from users where id = ?", hostId);
        });
  }

  @Test
  @DisplayName("외부 테스트 트랜잭션 없이 방·첫 게임·선택지·정답을 함께 커밋한다")
  void commitsTheFirstAssemblyAndServerOpeningTime() {
    Long gameId =
        assembly.createRoomWithFirstGame(
            hostId, rewardId, 10, "첫 질문", List.of("첫 선택지", "둘째 선택지"), 1);

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          var saved = games.findById(gameId).orElseThrow();
          entityManager.flush();
          entityManager.clear();
          var game = games.findById(gameId).orElseThrow();
          assertThat(game).isNotSameAs(saved);
          var room = game.getRoom();
          var storedChoices = choices.findByGame_IdOrderByPositionAscIdAsc(gameId);
          assertThat(game.getQuestionTextSnapshot()).isEqualTo("첫 질문");
          assertThat(storedChoices)
              .extracting(Choice::getTextSnapshot)
              .containsExactly("첫 선택지", "둘째 선택지");
          assertThat(game.getCorrectChoiceId()).isEqualTo(storedChoices.get(1).getId());
          assertThat(room.getHost().getId()).isEqualTo(hostId);
          assertThat(room.getSelectedRewardPreset().getId()).isEqualTo(rewardId);
          assertThat(room.getOpenedAt()).isEqualTo(INITIAL_TIME);
          assertThat(room.getClosesAt()).isEqualTo(Instant.parse("2026-11-16T09:00:00Z"));
          assertThat(room.getCreatedAt()).isNotNull();
          assertThat(game.getCreatedAt()).isNotNull();
        });
  }

  @Test
  @DisplayName("다른 게임의 정답 연결은 거절되고 커밋·재조회 후에도 기존 상태를 보존한다")
  void rejectsAnotherGamesChoiceWithoutPersistingTheInvalidLink() {
    var transaction = new TransactionTemplate(transactionManager);
    // 거절을 잡고 커밋해 롤백이 잘못된 쓰기를 숨기지 않게 한다.
    Long roomId =
        transaction.execute(status -> {
          var room =
              rooms.save(
                  Room.create(users.getReferenceById(hostId), rewards.getReferenceById(rewardId)));
          var first = games.save(Game.create(room, 1, "첫 질문"));
          choices.save(Choice.create(first, 0, "첫 선택지"));
          var second = games.save(Game.create(room, 2, "둘째 질문"));
          var secondChoice = choices.save(Choice.create(second, 0, "둘째 정답"));
          second.initializeCorrectChoice(secondChoice);
          entityManager.flush();
          entityManager.clear();

          var reloadedFirst = games.findById(first.getId()).orElseThrow();
          var otherChoice = choices.findById(secondChoice.getId()).orElseThrow();
          assertThatThrownBy(() -> reloadedFirst.initializeCorrectChoice(otherChoice))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessage("저장된 같은 게임의 선택지가 필요합니다.");

          entityManager.flush();
          entityManager.clear();
          assertThat(games.findById(first.getId()).orElseThrow().getCorrectChoiceId()).isNull();
          assertThat(games.findById(second.getId()).orElseThrow().getCorrectChoiceId())
              .isEqualTo(secondChoice.getId());
          return room.getId();
        });

    transaction.executeWithoutResult(status -> {
      var storedGames = games.findByRoom_IdOrderByOrdinalAscIdAsc(roomId);
      assertThat(storedGames).hasSize(2);
      assertThat(storedGames.getFirst().getCorrectChoiceId()).isNull();
      var secondChoices = choices.findByGame_IdOrderByPositionAscIdAsc(storedGames.get(1).getId());
      assertThat(secondChoices).hasSize(1);
      assertThat(storedGames.get(1).getCorrectChoiceId())
          .isEqualTo(secondChoices.getFirst().getId());
    });
  }

  @Test
  @DisplayName("정답 연결을 DB에 반영한 뒤 공개 기준 서버 시각을 기록한다")
  void completesTheCorrectLinkBeforeRecordingOpeningTime() {
    clock.beforeRead =
        () -> {
          assertThat(
                  jdbc.queryForObject(
                      "select count(*) from games g join rooms r on r.id = g.room_id where"
                          + " r.host_user_id = ? and g.correct_choice_id is not null",
                      Long.class,
                      hostId))
              .isEqualTo(1L);
          assertThat(
                  jdbc.queryForObject(
                      "select count(*) from rooms where host_user_id = ? and opened_at is null",
                      Long.class,
                      hostId))
              .isEqualTo(1L);
        };

    assembly.createRoomWithFirstGame(hostId, rewardId, 1, "질문", List.of("정답"), 0);
  }

  @Test
  @DisplayName("이미 열린 방에도 새 게임의 최초 정답 연결을 허용하고 기존 시각·정답을 보존한다")
  void addsANewGameWithoutReopeningTheRoomOrChangingTheFirstGame() {
    Long firstId =
        assembly.createRoomWithFirstGame(hostId, rewardId, 10, "첫 질문", List.of("첫 정답"), 0);
    Long roomId = games.findById(firstId).orElseThrow().getRoom().getId();
    Long firstCorrectId = games.findById(firstId).orElseThrow().getCorrectChoiceId();
    clock.now = Instant.parse("2026-11-15T12:00:00Z");

    Long nextId = assembly.addGame(roomId, 20, "추가 질문", List.of("정답", "오답", "다른 답"), 0);

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          var next = games.findById(nextId).orElseThrow();
          assertThat(next.getCorrectChoiceId()).isNotNull();
          assertThat(choices.findById(next.getCorrectChoiceId()).orElseThrow().getGame().getId())
              .isEqualTo(nextId);
          assertThat(next.getRoom().getOpenedAt()).isEqualTo(INITIAL_TIME);
          assertThat(next.getRoom().getClosesAt()).isEqualTo(Instant.parse("2026-11-16T09:00:00Z"));
          assertThat(games.findById(firstId).orElseThrow().getCorrectChoiceId())
              .isEqualTo(firstCorrectId);
          assertThat(games.findByRoom_IdOrderByOrdinalAscIdAsc(roomId))
              .extracting(game -> game.getId())
              .containsExactly(firstId, nextId);
        });
  }

  @Test
  @DisplayName("선택지 조립과 정답 인덱스 검증 실패는 새 방·게임·선택지를 남기지 않는다")
  void rollsBackIncompleteAssembly() {
    List<Long> before = rowCounts();
    assertThatThrownBy(() ->
            assembly.createRoomWithFirstGame(hostId, rewardId, 1, "질문", List.of("저장한 선택지", " "), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(rowCounts()).isEqualTo(before);

    assertThatThrownBy(
            () -> assembly.createRoomWithFirstGame(hostId, rewardId, 1, "질문", List.of("선택지"), 3))
        .isInstanceOf(IndexOutOfBoundsException.class);
    assertThat(rowCounts()).isEqualTo(before);
  }

  @Test
  @DisplayName("실제 Choice INSERT 거절과 정답 UPDATE 거절 시 DB 변경을 롤백한다")
  void rollsBackDatabaseInsertAndCorrectLinkFailures() {
    List<Long> before = rowCounts();
    jdbc.execute(
        "alter table choices add constraint test_reject_choice check (text_snapshot <>"
            + " 'fail-choice')");
    try {
      assertThatThrownBy(() ->
              assembly.createRoomWithFirstGame(
                  hostId, rewardId, 1, "질문", List.of("먼저 저장", "fail-choice"), 0))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(rowCounts()).isEqualTo(before);
    } finally {
      jdbc.execute("alter table choices drop constraint test_reject_choice");
    }

    jdbc.execute(
        "alter table games add constraint test_reject_link check (question_text_snapshot <>"
            + " 'fail-link' or correct_choice_id is null)");
    try {
      assertThatThrownBy(() ->
              assembly.createRoomWithFirstGame(hostId, rewardId, 1, "fail-link", List.of("선택지"), 0))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(rowCounts()).isEqualTo(before);
    } finally {
      jdbc.execute("alter table games drop constraint test_reject_link");
    }
  }

  @Test
  @DisplayName("openedAt의 마지막 UPDATE가 거절돼도 방·첫 게임·선택지를 남기지 않는다")
  void rollsBackTheFinalOpeningFlush() {
    List<Long> before = rowCounts();
    jdbc.execute(
        "alter table rooms add constraint test_reject_open check (host_user_id <> "
            + hostId
            + " or opened_at is null)");
    try {
      assertThatThrownBy(
              () -> assembly.createRoomWithFirstGame(hostId, rewardId, 1, "질문", List.of("선택지"), 0))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(rowCounts()).isEqualTo(before);
    } finally {
      jdbc.execute("alter table rooms drop constraint test_reject_open");
    }
  }

  @Test
  @DisplayName("추가 게임 조립 실패는 이미 열린 방과 기존 게임·정답·마감을 보존한다")
  void preservesExistingStateWhenAddingAGameFails() {
    Long firstId =
        assembly.createRoomWithFirstGame(hostId, rewardId, 10, "기존 질문", List.of("기존 정답"), 0);
    Long roomId = games.findById(firstId).orElseThrow().getRoom().getId();
    Long correctId = games.findById(firstId).orElseThrow().getCorrectChoiceId();
    List<Long> before = rowCounts();

    assertThatThrownBy(() -> assembly.addGame(roomId, 20, "추가 질문", List.of("새 선택지"), -1))
        .isInstanceOf(IndexOutOfBoundsException.class);

    assertThat(rowCounts()).isEqualTo(before);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          assertThat(rooms.findById(roomId).orElseThrow().getOpenedAt()).isEqualTo(INITIAL_TIME);
          assertThat(games.findById(firstId).orElseThrow().getCorrectChoiceId())
              .isEqualTo(correctId);
        });
  }

  @Test
  @DisplayName("독립 connection에서는 미커밋 조립이 보이지 않고 성공 뒤 완성된 방을 본다")
  void hidesAssemblyFromAnIndependentTransaction() throws Exception {
    CountDownLatch assembled = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    clock.beforeRead =
        () -> {
          assembled.countDown();
          try {
            if (!release.await(10, TimeUnit.SECONDS)) {
              throw new IllegalStateException("테스트 조립 재개 신호가 없습니다.");
            }
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
          }
        };
    try (var executor = Executors.newSingleThreadExecutor()) {
      var result =
          executor.submit(() ->
              assembly.createRoomWithFirstGame(hostId, rewardId, 1, "질문", List.of("정답", "오답"), 0));
      try {
        assertThat(assembled.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(independentCounts()).containsExactly(0L, 0L, 0L);
      } finally {
        release.countDown();
      }
      result.get(10, TimeUnit.SECONDS);
      assertThat(independentCounts()).containsExactly(1L, 1L, 2L);
    }
  }

  @Test
  @DisplayName("보상 문구 변경은 재조회에 반영되지만 게임별 질문·선택지 사본은 바뀌지 않는다")
  void retainsGameSnapshotsWhileReadingTheCurrentReward() {
    Long gameId =
        assembly.createRoomWithFirstGame(hostId, rewardId, 1, "저장한 질문", List.of("저장한 선택지"), 0);
    jdbc.update("update reward_presets set title = ? where id = ?", "변경된 보상", rewardId);

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          var game = games.findById(gameId).orElseThrow();
          assertThat(game.getRoom().getSelectedRewardPreset().getTitle()).isEqualTo("변경된 보상");
          assertThat(game.getQuestionTextSnapshot()).isEqualTo("저장한 질문");
          assertThat(choices.findByGame_IdOrderByPositionAscIdAsc(gameId))
              .extracting(Choice::getTextSnapshot)
              .containsExactly("저장한 선택지");
        });
  }

  private List<Long> rowCounts() {
    return List.of(rooms.count(), games.count(), choices.count());
  }

  private List<Long> independentCounts() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      List<Long> counts =
          List.of(
              countForHost(connection, "select count(*) from rooms where host_user_id = ?"),
              countForHost(
                  connection,
                  "select count(*) from games g join rooms r on r.id = g.room_id where"
                      + " r.host_user_id = ?"),
              countForHost(
                  connection,
                  "select count(*) from choices c join games g on g.id = c.game_id join rooms r on"
                      + " r.id = g.room_id where r.host_user_id = ?"));
      connection.commit();
      return counts;
    }
  }

  private long countForHost(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, hostId);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {
    @Bean
    MutableClock clock() {
      return new MutableClock();
    }
  }

  static class MutableClock extends Clock {
    private Instant now = INITIAL_TIME;
    private Runnable beforeRead = () -> {};

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      beforeRead.run();
      return now;
    }
  }
}

package org.kirikick.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.ApiApplication;

class ProductArtifactIsolationTest {

  @Test
  @DisplayName("제품 main은 승인된 출제 매핑만 포함하고 스파이크와 시험 Q 타입은 제외한다")
  void mainClassesContainOnlyApprovedPublicationTypes() throws Exception {
    Path mainClasses = mainClasses();
    Path api = Path.of("org/kirikick/api");
    List<Path> publicationPackages =
        List.of("game", "publication", "question", "reward", "room").stream()
            .map(api::resolve)
            .toList();
    List<Path> approved =
        List.of(
                "game/domain/Choice.class",
                "game/domain/Game.class",
                "game/domain/QChoice.class",
                "game/domain/QGame.class",
                "game/domain/exception/GameErrorCode.class",
                "game/repository/ChoiceRepository.class",
                "game/repository/GameRepository.class",
                "publication/domain/PublicationIdempotencyRecord.class",
                "publication/domain/PublicationInput.class",
                "publication/domain/PublicationInput$Kind.class",
                "publication/domain/exception/PublicationErrorCode.class",
                "publication/repository/PublicationIdempotencyRepository.class",
                "publication/repository/PublicationIdempotencyRepository$Claim.class",
                "question/domain/QQuestionPreset.class",
                "question/domain/QQuestionPresetChoice.class",
                "question/domain/QuestionPreset.class",
                "question/domain/QuestionPresetChoice.class",
                "question/repository/QuestionPresetChoiceRepository.class",
                "question/repository/QuestionPresetRepository.class",
                "reward/domain/QRewardPreset.class",
                "reward/domain/RewardPreset.class",
                "reward/repository/RewardPresetRepository.class",
                "room/domain/QRoom.class",
                "room/domain/Room.class",
                "room/domain/Room$ResultState.class",
                "room/domain/Room$Status.class",
                "room/domain/exception/RoomErrorCode.class",
                "room/repository/RoomRepository.class",
                "room/repository/RoomRepository$State.class")
            .stream()
            .map(api::resolve)
            .toList();
    try (var files = Files.walk(mainClasses)) {
      var classes = files.filter(Files::isRegularFile).map(mainClasses::relativize).toList();
      assertThat(classes)
          .noneMatch(path ->
              path.startsWith(api.resolve("spike"))
                  || path.startsWith(Path.of("org/kirikick/spike")));
      assertThat(
              classes.stream()
                  .filter(path -> publicationPackages.stream().anyMatch(path::startsWith)))
          .containsExactlyInAnyOrderElementsOf(approved);
    }
  }

  @Test
  @DisplayName("기본 Flyway 위치에는 제품 V1~V4만 있고 스파이크 마이그레이션은 없다")
  void defaultMigrationLocationContainsOnlyProductMigrations() throws Exception {
    var locations = ApiApplication.class.getClassLoader().getResources("db/migration");
    assertThat(locations.hasMoreElements()).isTrue();
    while (locations.hasMoreElements()) {
      try (var files = Files.list(Path.of(locations.nextElement().toURI()))) {
        assertThat(files.map(path -> path.getFileName().toString()))
            .containsExactlyInAnyOrder(
                "V1__create_users_and_oauth_identities.sql",
                "V2__create_signup_applications.sql",
                "V3__create_question_room_game_core.sql",
                "V4__create_publication_idempotency.sql");
      }
    }
  }

  private Path mainClasses() throws URISyntaxException {
    return Path.of(
        ApiApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
  }
}

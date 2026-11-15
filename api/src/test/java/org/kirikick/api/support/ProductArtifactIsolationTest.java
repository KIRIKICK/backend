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
  @DisplayName("제품 main 클래스에는 N1 실험 타입과 생성 Q 타입이 없다")
  void mainClassesExcludeN1Experiments() throws Exception {
    Path mainClasses = mainClasses();
    try (var files = Files.walk(mainClasses)) {
      assertThat(files.filter(Files::isRegularFile).map(mainClasses::relativize))
          .noneMatch(path ->
              List.of(
                      "org/kirikick/api/game/",
                      "org/kirikick/api/room/",
                      "org/kirikick/api/reward/",
                      "org/kirikick/api/spike/",
                      "org/kirikick/spike/")
                  .stream()
                  .anyMatch(prefix -> path.startsWith(Path.of(prefix))));
    }
  }

  @Test
  @DisplayName("기본 Flyway 위치에는 제품 V1·V2만 있고 스파이크 V9000·V9001은 없다")
  void defaultMigrationLocationContainsOnlyProductMigrations() throws Exception {
    var locations = ApiApplication.class.getClassLoader().getResources("db/migration");
    assertThat(locations.hasMoreElements()).isTrue();
    while (locations.hasMoreElements()) {
      try (var files = Files.list(Path.of(locations.nextElement().toURI()))) {
        assertThat(files.map(path -> path.getFileName().toString()))
            .containsExactlyInAnyOrder(
                "V1__create_users_and_oauth_identities.sql", "V2__create_signup_applications.sql");
      }
    }
  }

  private Path mainClasses() throws URISyntaxException {
    return Path.of(
        ApiApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
  }
}

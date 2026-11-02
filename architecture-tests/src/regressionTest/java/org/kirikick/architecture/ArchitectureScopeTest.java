package org.kirikick.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.tngtech.archunit.core.domain.JavaClasses;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kirikick.architecturefixtures.domain.DomainFixtures;

class ArchitectureScopeTest {

  @TempDir Path directory;

  @Test
  void importsOnlyTheSelectedModulesClassOutput() throws IOException {
    Class<?> fixture = DomainFixtures.PrivateState.class;
    try (InputStream bytes =
        fixture.getResourceAsStream("/" + fixture.getName().replace('.', '/') + ".class")) {
      Files.copy(bytes, directory.resolve("PrivateState.class"));
    }

    JavaClasses classes = ArchitectureTest.importClasses(directory.toString());

    assertThat(classes).hasSize(1);
    assertThat(classes.contain(fixture)).isTrue();
    assertThat(classes.contain(ArchitectureRules.class)).isFalse();
  }

  @Test
  void rejectsMissingModuleOutputConfiguration() {
    assertThatIllegalArgumentException().isThrownBy(() -> ArchitectureTest.importClasses(null));
    assertThatIllegalArgumentException().isThrownBy(() -> ArchitectureTest.importClasses(" "));
  }

  @Test
  void allowsModulesWithoutCompiledProductionClasses() {
    assertThat(ArchitectureTest.importClasses(directory.toString())).isEmpty();
  }
}

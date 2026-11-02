package org.kirikick.architecture;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

  private static JavaClasses classes;

  @BeforeAll
  static void importModuleProductionClasses() {
    classes = importClasses(System.getProperty("architecture.classes.dirs"));
    assumeFalse(classes.isEmpty(), "This module has no compiled production classes");
  }

  static JavaClasses importClasses(String directories) {
    if (directories == null || directories.isBlank()) {
      throw new IllegalArgumentException(
          "architecture.classes.dirs must specify this module's main output");
    }
    return new ClassFileImporter()
        .importPaths(
            Arrays.stream(directories.split(Pattern.quote(File.pathSeparator)))
                .map(Path::of)
                .filter(Files::isDirectory)
                .toList());
  }

  @Test
  void domainStateIsPrivate() {
    ArchitectureRules.DOMAIN_STATE_IS_PRIVATE.check(classes);
  }

  @Test
  void domainHasNoSetters() {
    ArchitectureRules.DOMAIN_HAS_NO_SETTERS.check(classes);
  }

  @Test
  void domainDoesNotDependOnOuterLayers() {
    ArchitectureRules.DOMAIN_DOES_NOT_DEPEND_ON_OUTER_LAYERS.check(classes);
  }

  @Test
  void servicesDoNotExposeRepositories() {
    ArchitectureRules.SERVICES_DO_NOT_EXPOSE_REPOSITORIES.check(classes);
  }

  @Test
  void controllersReturnDtos() {
    ArchitectureRules.CONTROLLERS_RETURN_DTOS.check(classes);
  }
}

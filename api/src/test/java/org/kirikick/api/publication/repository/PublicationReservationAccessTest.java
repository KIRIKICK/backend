package org.kirikick.api.publication.repository;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.publication.repository.model.LockedReservation;

class PublicationReservationAccessTest {

  @Test
  @DisplayName("저장소만 잠긴 예약을 생성하거나 내부 값을 읽는다")
  void lockedReservationsAreOpaqueOutsideRepository() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(PublicationIdempotencyRepository.class.getName())
        .and()
        .resideOutsideOfPackage(LockedReservation.class.getPackageName())
        .should()
        .accessClassesThat()
        .areAssignableTo(LockedReservation.class)
        .check(
            new ClassFileImporter()
                .importPath(Path.of(System.getProperty("architecture.classes.dirs"))));
  }
}

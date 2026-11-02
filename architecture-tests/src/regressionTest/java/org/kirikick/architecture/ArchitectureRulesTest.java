package org.kirikick.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;
import org.kirikick.architecturefixtures.controller.ControllerFixtures;
import org.kirikick.architecturefixtures.controller.dto.ControllerDtos;
import org.kirikick.architecturefixtures.domain.DomainFixtures;
import org.kirikick.architecturefixtures.jpa.JpaFixtures;
import org.kirikick.architecturefixtures.repository.RepositoryFixtures;
import org.kirikick.architecturefixtures.service.ServiceFixtures;

class ArchitectureRulesTest {

  @Test
  void rejectsWildcardNoContentResponseContracts() {
    assertThat(
            ArchitectureRules.CONTROLLERS_RETURN_DTOS
                .evaluate(
                    importClasses(
                        org.kirikick.architecturefixtures.controller.WildcardController.class))
                .hasViolation())
        .isTrue();
  }

  @Test
  void allowsPrivateDomainStateAndEntityGraphs() {
    EvaluationResult result =
        ArchitectureRules.DOMAIN_STATE_IS_PRIVATE.evaluate(
            importClasses(DomainFixtures.PrivateState.class, DomainFixtures.EntityGraph.class));

    assertThat(result.hasViolation()).isFalse();
  }

  @Test
  void rejectsNonPrivateDomainStateIncludingAnnotatedEntitiesOutsideDomainPackages() {
    EvaluationResult result =
        ArchitectureRules.DOMAIN_STATE_IS_PRIVATE.evaluate(
            importClasses(
                DomainFixtures.ExposedState.class, JpaFixtures.EntityOutsideDomainPackage.class));

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().getDetails())
        .anyMatch(detail -> detail.contains("ExposedState") && detail.contains("name"))
        .anyMatch(
            detail -> detail.contains("EntityOutsideDomainPackage") && detail.contains("name"));
  }

  @Test
  void allowsDomainBehaviorMethodsThatAreNotSetters() {
    EvaluationResult result =
        ArchitectureRules.DOMAIN_HAS_NO_SETTERS.evaluate(
            importClasses(DomainFixtures.PrivateState.class));

    assertThat(result.hasViolation()).isFalse();
  }

  @Test
  void rejectsJavaBeanSettersInDomainClasses() {
    EvaluationResult result =
        ArchitectureRules.DOMAIN_HAS_NO_SETTERS.evaluate(
            importClasses(DomainFixtures.SetterState.class));

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().getDetails())
        .anyMatch(detail -> detail.contains("SetterState.setName"));
  }

  @Test
  void allowsDomainClassesWithoutOuterLayerDependencies() {
    EvaluationResult result =
        ArchitectureRules.DOMAIN_DOES_NOT_DEPEND_ON_OUTER_LAYERS.evaluate(
            importClasses(DomainFixtures.PrivateState.class, DomainFixtures.EntityGraph.class));

    assertThat(result.hasViolation()).isFalse();
  }

  @Test
  void rejectsDomainDependenciesOnServices() {
    EvaluationResult result =
        ArchitectureRules.DOMAIN_DOES_NOT_DEPEND_ON_OUTER_LAYERS.evaluate(
            importClasses(
                DomainFixtures.OuterLayerDependency.class,
                ServiceFixtures.Orchestrator.class,
                RepositoryFixtures.StoreRepository.class));

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().getDetails())
        .anyMatch(
            detail -> detail.contains("OuterLayerDependency") && detail.contains("Orchestrator"));
  }

  @Test
  void allowsConstructorInjectedRepositoriesAndDomainReturnValues() {
    EvaluationResult result =
        ArchitectureRules.SERVICES_DO_NOT_EXPOSE_REPOSITORIES.evaluate(
            importClasses(
                ServiceFixtures.Orchestrator.class,
                RepositoryFixtures.StoreRepository.class,
                DomainFixtures.PrivateState.class));

    assertThat(result.hasViolation()).isFalse();
  }

  @Test
  void rejectsRepositoryTypesInPublicAndProtectedServiceReturnSignatures() {
    EvaluationResult result =
        ArchitectureRules.SERVICES_DO_NOT_EXPOSE_REPOSITORIES.evaluate(
            importClasses(
                ServiceFixtures.RepositoryLeak.class, RepositoryFixtures.StoreRepository.class));

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().getDetails())
        .anyMatch(detail -> detail.contains("RepositoryLeak.direct"))
        .anyMatch(detail -> detail.contains("RepositoryLeak.nested"))
        .anyMatch(detail -> detail.contains("RepositoryLeak.array"))
        .anyMatch(detail -> detail.contains("RepositoryLeak.wildcard"))
        .anyMatch(detail -> detail.contains("RepositoryLeak.typeVariable"));
  }

  @Test
  void allowsRequestHandlersThatReturnDtosAndNoContentResponses() {
    EvaluationResult result =
        ArchitectureRules.CONTROLLERS_RETURN_DTOS.evaluate(
            importClasses(ControllerFixtures.ValidController.class, ControllerDtos.Response.class));

    assertThat(result.hasViolation()).isFalse();
  }

  @Test
  void rejectsRequestHandlersThatReturnEntitiesOrRawTypes() {
    EvaluationResult result =
        ArchitectureRules.CONTROLLERS_RETURN_DTOS.evaluate(
            importClasses(
                ControllerFixtures.InvalidController.class, DomainFixtures.PrivateState.class));

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().getDetails())
        .anyMatch(detail -> detail.contains("InvalidController.entity"))
        .anyMatch(detail -> detail.contains("InvalidController.rawString"))
        .anyMatch(detail -> detail.contains("InvalidController.rawContainer"))
        .anyMatch(detail -> detail.contains("InvalidController.rawResponse"))
        .anyMatch(detail -> detail.contains("InvalidController.stringResponse"))
        .anyMatch(detail -> detail.contains("InvalidController.entityList"))
        .anyMatch(detail -> detail.contains("InvalidController.repository"))
        .anyMatch(detail -> detail.contains("InvalidController.object"));
  }

  private static JavaClasses importClasses(Class<?>... classes) {
    return new ClassFileImporter().importClasses(classes);
  }
}

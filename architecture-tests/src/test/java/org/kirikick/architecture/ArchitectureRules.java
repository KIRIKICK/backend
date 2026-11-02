package org.kirikick.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;

final class ArchitectureRules {

  static final ArchRule DOMAIN_STATE_IS_PRIVATE =
      classes()
          .should(
              new ArchCondition<>("keep domain state private (except static final constants)") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                  if (!isDomain(type)) {
                    return;
                  }
                  type.getFields().stream()
                      .filter(field ->
                          !field
                              .getModifiers()
                              .containsAll(List.of(JavaModifier.STATIC, JavaModifier.FINAL)))
                      .filter(field -> !field.getModifiers().contains(JavaModifier.PRIVATE))
                      .forEach(field ->
                          events.add(
                              SimpleConditionEvent.violated(
                                  field, field.getFullName() + " must be private")));
                }
              });

  static final ArchRule DOMAIN_HAS_NO_SETTERS =
      classes()
          .should(
              new ArchCondition<>("use domain behavior instead of JavaBean setters") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                  if (!isDomain(type)) {
                    return;
                  }
                  type.getMethods().stream()
                      .filter(method -> !method.getModifiers().contains(JavaModifier.STATIC))
                      .filter(method -> method.getName().matches("set[A-Z].*"))
                      .filter(method -> method.getRawParameterTypes().size() == 1)
                      .forEach(method ->
                          events.add(
                              SimpleConditionEvent.violated(
                                  method,
                                  method.getFullName()
                                      + " must express domain behavior, not a setter")));
                }
              });

  static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_OUTER_LAYERS =
      classes()
          .should(
              new ArchCondition<>(
                  "keep domain independent of controller, service, repository and infra") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                  if (!isDomain(type)) {
                    return;
                  }
                  type.getDirectDependenciesFromSelf().stream()
                      .filter(dependency -> isOuterLayer(dependency.getTargetClass()))
                      .forEach(dependency ->
                          events.add(
                              SimpleConditionEvent.violated(
                                  dependency, dependency.getDescription())));
                }
              });

  static final ArchRule SERVICES_DO_NOT_EXPOSE_REPOSITORIES =
      classes()
          .should(
              new ArchCondition<>(
                  "not expose repository types through public or protected service return signatures") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                  if (!isService(type)) {
                    return;
                  }
                  type.getAllMethods().stream()
                      .filter(method ->
                          method.getModifiers().contains(JavaModifier.PUBLIC)
                              || method.getModifiers().contains(JavaModifier.PROTECTED))
                      .filter(method ->
                          method.getReturnType().getAllInvolvedRawTypes().stream()
                              .anyMatch(ArchitectureRules::isRepository))
                      .forEach(method ->
                          events.add(
                              SimpleConditionEvent.violated(
                                  method, method.getFullName() + " exposes a repository type")));
                }
              });

  static final ArchRule CONTROLLERS_RETURN_DTOS =
      classes()
          .should(
              new ArchCondition<>(
                  "return controller.dto DTOs or no content from REST request handlers") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                  if (!type.isAnnotatedWith(
                          "org.springframework.web.bind.annotation.RestController")
                      && !type.isMetaAnnotatedWith(
                          "org.springframework.web.bind.annotation.RestController")) {
                    return;
                  }
                  type.getAllMethods().stream()
                      .filter(ArchitectureRules::isRequestHandler)
                      .filter(method -> !isControllerResponse(method.getReturnType()))
                      .forEach(method ->
                          events.add(
                              SimpleConditionEvent.violated(
                                  method,
                                  method.getFullName()
                                      + " must return a controller.dto DTO, DTO collection/array, "
                                      + "ResponseEntity of those types, void or ResponseEntity<Void>")));
                }
              });

  private ArchitectureRules() {}

  private static boolean isDomain(JavaClass type) {
    return !type.isAssignableTo("com.querydsl.core.types.dsl.BeanPath")
        && (resideInAnyPackage("..domain..").test(type)
            || type.isAnnotatedWith("jakarta.persistence.Entity")
            || type.isAnnotatedWith("jakarta.persistence.MappedSuperclass")
            || type.isAnnotatedWith("jakarta.persistence.Embeddable"));
  }

  private static boolean isService(JavaClass type) {
    return resideInAnyPackage("..service..").test(type)
        || type.isAnnotatedWith("org.springframework.stereotype.Service")
        || type.isMetaAnnotatedWith("org.springframework.stereotype.Service");
  }

  private static boolean isRepository(JavaClass type) {
    return resideInAnyPackage("..repository..").test(type)
        || type.isAssignableTo("org.springframework.data.repository.Repository");
  }

  private static boolean isOuterLayer(JavaClass type) {
    return resideInAnyPackage("..controller..", "..infra..").test(type)
        || isService(type)
        || isRepository(type);
  }

  private static boolean isRequestHandler(JavaMethod method) {
    return method.isAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping")
        || method.isMetaAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping");
  }

  private static boolean isControllerResponse(JavaType type) {
    if (type.toErasure().isEquivalentTo(void.class)) {
      return true;
    }
    if (type instanceof JavaParameterizedType parameterized
        && parameterized.toErasure().getName().equals("org.springframework.http.ResponseEntity")) {
      List<JavaType> arguments = parameterized.getActualTypeArguments();
      return arguments.size() == 1
          && (arguments.getFirst() instanceof JavaClass raw && raw.isEquivalentTo(Void.class)
              || isDtoBody(arguments.getFirst()));
    }
    return isDtoBody(type);
  }

  private static boolean isDtoBody(JavaType type) {
    if (type instanceof JavaClass raw) {
      if (raw.isArray()) {
        return isDtoBody(raw.getComponentType());
      }
      return resideInAnyPackage("..controller.dto..").test(raw)
          && !isDomain(raw)
          && !isService(raw)
          && !isRepository(raw);
    }
    if (type instanceof JavaParameterizedType parameterized
        && parameterized.toErasure().isAssignableTo(Iterable.class)) {
      List<JavaType> arguments = parameterized.getActualTypeArguments();
      return arguments.size() == 1 && isDtoBody(arguments.getFirst());
    }
    return false;
  }
}

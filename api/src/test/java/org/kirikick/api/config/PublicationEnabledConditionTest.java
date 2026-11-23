package org.kirikick.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

class PublicationEnabledConditionTest {

  private final ApplicationContextRunner context =
      new ApplicationContextRunner().withUserConfiguration(ConditionedConfiguration.class);

  @Test
  void doesNotEnablePublicationComponentsWithoutAnExplicitGraceProperty() {
    context.run(application -> assertThat(application).doesNotHaveBean(Marker.class));
  }

  @Test
  void enablesPublicationComponentsWhenGracePropertyIsExplicitlyPresent() {
    context
        .withPropertyValues("publication.grace=PT5M")
        .run(application -> assertThat(application).hasSingleBean(Marker.class));
  }

  @Test
  void treatsFalseAsPresentSoPropertyBindingCanRejectItInsteadOfSilentlyDisablingTheFeature() {
    context
        .withPropertyValues("publication.grace=false")
        .run(application -> assertThat(application).hasSingleBean(Marker.class));
  }

  @Configuration(proxyBeanMethods = false)
  @Conditional(PublicationEnabledCondition.class)
  static class ConditionedConfiguration {

    @Bean
    Marker marker() {
      return new Marker();
    }
  }

  static class Marker {}
}

package org.kirikick.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PublicationPropertiesTest {

  @Test
  @DisplayName("G1: ZERO와 마이크로초 단위 Grace를 허용한다")
  void acceptsZeroAndMicrosecondAlignedGrace() {
    assertThat(new PublicationProperties(Duration.ZERO).isGraceValid()).isTrue();
    assertThat(new PublicationProperties(Duration.ofNanos(1_000)).isGraceValid()).isTrue();
  }

  @Test
  @DisplayName("G1: null·음수·서브마이크로초 Grace를 거절한다")
  void rejectsNullNegativeAndSubmicrosecondGrace() {
    assertThat(new PublicationProperties(null).isGraceValid()).isFalse();
    assertThat(new PublicationProperties(Duration.ofNanos(-1_000)).isGraceValid()).isFalse();
    assertThat(new PublicationProperties(Duration.ofNanos(1)).isGraceValid()).isFalse();
  }

  @Test
  @DisplayName("G1: 명시 설정을 바인딩하고 누락과 잘못된 설정은 기동을 거절한다")
  void bindsExplicitIsoGraceAndRejectsMissingTypeAndSemanticValues() {
    assertStarts("publication.grace=PT5M", Duration.ofMinutes(5));
    assertStarts("publication.grace=PT0S", Duration.ZERO);
    assertFails();
    assertFails("publication.grace=");
    assertFails("publication.grace=not-a-duration");
    assertFails("publication.grace=PT-1S");
    assertFails("publication.grace=PT0.000000001S");
  }

  @Test
  @DisplayName("G1: 기본값 없이 주입한 Grace로 만료를 계산한다")
  void calculatesTheConfiguredGraceWithoutFallback() {
    Instant openedAt = Instant.parse("2026-11-20T00:00:00.123456Z");

    assertThat(new PublicationProperties(Duration.ofMinutes(5)).recordExpiresAt(openedAt))
        .isEqualTo(Instant.parse("2026-11-21T00:35:00.123456Z"));
    assertThatThrownBy(
            () -> new PublicationProperties(Duration.ofDays(1)).recordExpiresAt(Instant.MAX))
        .isInstanceOf(DateTimeException.class);
  }

  private static void assertStarts(String property, Duration expected) {
    runner(property)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(PublicationProperties.class).grace()).isEqualTo(expected);
          System.out.printf("G1 explicit %s binds %s%n", property, expected);
        });
  }

  private static void assertFails(String... properties) {
    runner(properties)
        .run(context -> {
          assertThat(context.getStartupFailure()).isNotNull();
          assertThat(context.getStartupFailure().getMessage()).contains("publication");
          System.out.printf(
              "G1 startup rejected %s: %s%n",
              String.join(",", properties), context.getStartupFailure().getClass().getSimpleName());
        });
  }

  private static ApplicationContextRunner runner(String... properties) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
        .withUserConfiguration(GraceBindingConfig.class)
        .withPropertyValues(properties);
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableConfigurationProperties(PublicationProperties.class)
  static class GraceBindingConfig {}
}

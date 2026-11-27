package org.kirikick.api.publication.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;

class PublicationRequestKeyTest {
  private static final String CANONICAL_KEY = "00000000-0000-4000-8000-00000000000a";

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidStorageKeys")
  @DisplayName("저장할 수 없는 요청 키는 VO 생성 시 거절한다")
  void rejectsInvalidStorageKeys(String description, String key) {
    assertThatThrownBy(() -> new PublicationRequestKey(key))
        .isInstanceOfSatisfying(
            DomainException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(PublicationErrorCode.INVALID_PUBLICATION_REQUEST_KEY));
  }

  private static Stream<Arguments> invalidStorageKeys() {
    return Stream.of(
        Arguments.of("NULL", null),
        Arguments.of("빈 문자열", ""),
        Arguments.of("NUL 포함", "nul\0key"),
        Arguments.of("짝이 없는 surrogate", "\uD800"),
        Arguments.of("ASCII 256바이트", "a".repeat(256)),
        Arguments.of("UTF-8 256바이트", "가".repeat(85) + "a"));
  }

  @Test
  @DisplayName("저장용 키는 UTF-8 255바이트의 비UUID 문자열을 보정하지 않는다")
  void preservesNonUuidStorageKeys() {
    String key = " " + "가".repeat(84) + "A ";
    PublicationRequestKey requestKey = new PublicationRequestKey(key);

    assertThat(requestKey.value()).isEqualTo(key);
    assertThat(requestKey.toString()).isEqualTo("PublicationRequestKey[redacted]");
  }

  @Test
  @DisplayName("canonical UUID v4도 원문을 보존한다")
  void preservesCanonicalV4() {
    assertThat(PublicationRequestKey.canonicalV4(CANONICAL_KEY).value()).isEqualTo(CANONICAL_KEY);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("nonCanonicalKeys")
  @DisplayName("저장 가능한 문자열이어도 canonical UUID v4가 아니면 거절한다")
  void rejectsNonCanonicalKeys(String description, String key) {
    assertThatThrownBy(() -> PublicationRequestKey.canonicalV4(key))
        .isInstanceOfSatisfying(
            DomainException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(PublicationErrorCode.INVALID_PUBLICATION_REQUEST_KEY));
  }

  private static Stream<Arguments> nonCanonicalKeys() {
    return Stream.of(
        Arguments.of("비UUID", "legacy-key"),
        Arguments.of("대문자", "00000000-0000-4000-8000-00000000000A"),
        Arguments.of("공백 포함", CANONICAL_KEY + " "),
        Arguments.of("버전이 v4 아님", "00000000-0000-1000-8000-00000000000a"),
        Arguments.of("잘못된 variant", "00000000-0000-4000-7000-00000000000a"));
  }
}

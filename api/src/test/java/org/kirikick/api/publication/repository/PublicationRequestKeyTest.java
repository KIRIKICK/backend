package org.kirikick.api.publication.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.common.infra.DbTimeSource;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.domain.PublicationKind;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.springframework.jdbc.core.simple.JdbcClient;

class PublicationRequestKeyTest {
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidKeys")
  @DisplayName("선점과 조회는 잘못된 요청 키를 SQL 실행 전에 거절한다")
  void rejectsInvalidKeysBeforeSql(String description, String key) {
    JdbcClient jdbc = mock(JdbcClient.class);
    var records = new PublicationIdempotencyRepository(jdbc, new DbTimeSource(jdbc));
    var input =
        new PublicationInput(PublicationKind.NK_CREATE, null, 1L, 2L, List.of(1L, 2L, 3L, 4L), 1L);

    assertThatThrownBy(() -> records.tryClaim(1L, key, input))
        .isInstanceOfSatisfying(
            DomainException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(PublicationErrorCode.INVALID_PUBLICATION_REQUEST_KEY));
    assertThatThrownBy(() -> records.findForUpdate(1L, key))
        .isInstanceOfSatisfying(
            DomainException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(PublicationErrorCode.INVALID_PUBLICATION_REQUEST_KEY));
    then(jdbc).shouldHaveNoInteractions();
  }

  private static Stream<Arguments> invalidKeys() {
    return Stream.of(
        Arguments.of("NULL", null),
        Arguments.of("빈 키", ""),
        Arguments.of("NUL 포함", "a\0b"),
        Arguments.of("짝이 없는 서로게이트", "a\uD800b"),
        Arguments.of("UTF-8 256바이트", "가".repeat(85) + "a"));
  }
}

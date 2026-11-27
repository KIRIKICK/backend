package org.kirikick.api.publication.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.publication.domain.PublicationKind;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;

class NkKickAddRequestTest {
  @ParameterizedTest(name = "ID={0}")
  @ValueSource(strings = {"", " ", "0", "-1", "01", "1.5", "other"})
  @DisplayName("질문 ID는 양의 10진 문자열이어야 한다")
  void rejectsInvalidDecimalId(String id) {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(
              factory
                  .getValidator()
                  .validate(new NkKickAddRequest(id, List.of("1", "2", "3", "4"), "1")))
          .isNotEmpty();
    }
  }

  @Test
  @DisplayName("필수 필드 누락과 네 개가 아닌 보기 목록은 Bean Validation에서 거절한다")
  void rejectsMissingFieldsAndWrongChoiceCount() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      assertThat(validator.validate(new NkKickAddRequest(null, null, null))).isNotEmpty();
      for (var choices : List.of(List.of("1", "2", "3"), List.of("1", "2", "3", "4", "5"))) {
        assertThat(validator.validate(new NkKickAddRequest("10", choices, "1"))).isNotEmpty();
      }
    }
  }

  @Test
  @DisplayName("중복 보기와 선택되지 않은 정답은 의미 입력에서 거절한다")
  void rejectsDuplicateChoicesAndUnselectedAnswer() {
    assertThatThrownBy(
            () -> new NkKickAddRequest("10", List.of("1", "1", "2", "3"), "1").toInput(20L))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(
            () -> new NkKickAddRequest("10", List.of("1", "2", "3", "4"), "5").toInput(20L))
        .isInstanceOf(DomainException.class);
  }

  @Test
  @DisplayName("추가 출제 ID를 변환하고 대상 방과 보기 순서를 정규화한다")
  void convertsDecimalIdsAndCanonicalizesChoiceOrderForTheTargetRoom() {
    var request = new NkKickAddRequest("10", List.of("4", "2", "3", "1"), "3");

    var input = request.toInput(20L);

    assertThat(input.kind()).isEqualTo(PublicationKind.NK_KICK_ADD);
    assertThat(input.roomId()).isEqualTo(20L);
    assertThat(input.rewardPresetId()).isNull();
    assertThat(input.choiceIds()).containsExactly(1L, 2L, 3L, 4L);
  }

  @Test
  @DisplayName("저장 범위를 초과한 ID 문자열은 출제 입력 오류로 거절한다")
  void rejectsADecimalIdentifierThatCannotFitTheDomainLong() {
    var request = new NkKickAddRequest("9223372036854775808", List.of("1", "2", "3", "4"), "1");

    assertThatThrownBy(() -> request.toInput(20L))
        .isInstanceOf(DomainException.class)
        .extracting(exception -> ((DomainException) exception).getErrorCode())
        .isEqualTo(PublicationErrorCode.INVALID_PUBLICATION_INPUT);
  }
}

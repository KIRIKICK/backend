package org.kirikick.api.publication.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.publication.domain.PublicationInput.Kind;

class PublicationInputTest {

  @Test
  @DisplayName("출제 의미 입력은 원본 변경과 접근자 변경으로부터 선택지 순서를 보존한다")
  void choiceIdsAreSortedAndImmutable() {
    var source = new ArrayList<>(List.of(4L, 2L, 1L, 3L));

    var input = new PublicationInput(Kind.NK_CREATE, null, 1L, 2L, source, 1L);

    source.set(0, 99L);
    assertThat(input.choiceIds()).containsExactly(1L, 2L, 3L, 4L);
    assertThatThrownBy(() -> input.choiceIds().add(5L))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}

package org.kirikick.api.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.common.exception.DomainException;

class DisplayNameValidationTest {
  @Test
  @DisplayName("허용된 표시 이름에 NFC 정규화나 공백 제거를 적용하지 않는다")
  void preservesTheExactAcceptedDisplayNameWithoutNfcOrTrimTransformation() {
    String displayName = " 민지 ";

    assertThat(User.create(displayName, null).getDisplayName()).isSameAs(displayName);
  }

  @Test
  @DisplayName("기존 표시 이름 형식에 맞는 입력을 그대로 허용한다")
  void acceptsTheExistingNameSyntaxWithoutTransformingIt() {
    for (String name : new String[] {"김 민지", "민지_2", "Alice-2", "ㅋㅋ", "가나다라마바사아자차"}) {
      assertThat(User.create(name, null).getDisplayName()).isEqualTo(name);
    }
  }

  @Test
  @DisplayName("허용 범위를 벗어난 문자와 길이 및 구분자를 거절한다")
  void rejectsOutOfContractCharactersLengthsAndSeparators() {
    for (String name :
        new String[] {
          "가나다라마바사아자차카", "김  민지", "민지😊", "---", " _- ", "민지\n", "민지\t", "민지\u00A0", "민\u200B지"
        }) {
      assertThatThrownBy(() -> User.create(name, null)).isInstanceOf(DomainException.class);
    }
  }
}

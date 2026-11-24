package org.kirikick.api.publication.controller.dto;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;

class NkCreateRequestTest {

  @Test
  void rejectsADecimalIdentifierThatCannotFitTheDomainLong() {
    NkCreateRequest request =
        new NkCreateRequest("9223372036854775808", List.of("1", "2", "3", "4"), "1", "5");

    assertThatThrownBy(request::toInput)
        .isInstanceOf(DomainException.class)
        .extracting(exception -> ((DomainException) exception).getErrorCode())
        .isEqualTo(PublicationErrorCode.INVALID_PUBLICATION_INPUT);
  }
}

package org.kirikick.api.publication.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class PublicationInputJsonConverter {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
          .build();

  static String encode(PublicationInput input) {
    try {
      return JSON.writeValueAsString(Objects.requireNonNull(input));
    } catch (JsonProcessingException exception) {
      throw new DomainException(
          PublicationErrorCode.PUBLICATION_INPUT_SERIALIZATION_FAILED, exception);
    }
  }

  static PublicationInput decode(String json) {
    try {
      return Objects.requireNonNull(JSON.readValue(json, PublicationInput.class));
    } catch (JsonProcessingException | RuntimeException exception) {
      throw new DomainException(PublicationErrorCode.UNREADABLE_PUBLICATION_RECORD);
    }
  }
}

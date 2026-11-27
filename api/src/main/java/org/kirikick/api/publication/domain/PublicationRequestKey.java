package org.kirikick.api.publication.domain;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.regex.Pattern;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;

public record PublicationRequestKey(String value) {
  private static final Pattern UUID_V4 =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

  public PublicationRequestKey {
    if (value == null
        || value.isEmpty()
        || value.length() > 255
        || value.indexOf('\0') >= 0
        || !UTF_8.newEncoder().canEncode(value)
        || value.getBytes(UTF_8).length > 255) {
      throw new DomainException(PublicationErrorCode.INVALID_PUBLICATION_REQUEST_KEY);
    }
  }

  public static PublicationRequestKey canonicalV4(String value) {
    if (value == null || !UUID_V4.matcher(value).matches()) {
      throw new DomainException(PublicationErrorCode.INVALID_PUBLICATION_REQUEST_KEY);
    }
    return new PublicationRequestKey(value);
  }

  @Override
  public String toString() {
    return "PublicationRequestKey[redacted]";
  }
}

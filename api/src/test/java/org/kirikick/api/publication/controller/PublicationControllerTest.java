package org.kirikick.api.publication.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.then;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.publication.controller.dto.NkCreateResponse;
import org.kirikick.api.publication.controller.dto.NkKickAddRequest;
import org.kirikick.api.publication.domain.PublicationRequestKey;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.kirikick.api.publication.service.AddKickToRoomService;
import org.kirikick.api.publication.service.OpenRoomService;
import org.kirikick.api.publication.service.PublicationResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

class PublicationControllerTest {
  private final AddKickToRoomService service = mock(AddKickToRoomService.class);
  private final PublicationController controller =
      new PublicationController(mock(OpenRoomService.class), service);
  private final Jwt jwt =
      Jwt.withTokenValue("token")
          .header("alg", "none")
          .subject(UUID.randomUUID().toString())
          .build();
  private final NkKickAddRequest request =
      new NkKickAddRequest("10", List.of("4", "3", "2", "1"), "3");

  @Test
  @DisplayName("추가 출제는 생성과 복구 상태를 no-store 문자열 응답으로 구분한다")
  void addMapsCreatedAndReplayResponses() {
    String key = validKey();
    PublicationResult created =
        PublicationResult.created(
            20L, 30L, Instant.parse("2030-06-12T09:15:00Z"), Instant.parse("2030-06-13T09:15:00Z"));
    given(
            service.add(
                UUID.fromString(jwt.getSubject()),
                new PublicationRequestKey(key),
                request.toInput(20L)))
        .willReturn(created);

    ResponseEntity<NkCreateResponse> response = controller.add(jwt, "20", headers(key), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getBody())
        .isEqualTo(new NkCreateResponse("20", "30", created.openedAt(), created.closesAt()));

    PublicationResult replay =
        PublicationResult.replayed(20L, 30L, created.openedAt(), created.closesAt());
    given(
            service.add(
                UUID.fromString(jwt.getSubject()),
                new PublicationRequestKey(key),
                request.toInput(20L)))
        .willReturn(replay);
    assertThat(controller.add(jwt, "20", headers(key), request).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("추가 출제 요청 키와 경로 ID는 공백과 비V4 및 숫자 외 표현을 허용하지 않는다")
  void addRejectsInvalidHeadersAndPathIdsBeforeService() {
    for (String key :
        List.of(
            "",
            " " + validKey(),
            validKey() + " ",
            "A" + validKey().substring(1),
            new UUID(0, 0).toString())) {
      assertThatThrownBy(() -> controller.add(jwt, "20", headers(key), request))
          .isInstanceOf(DomainException.class)
          .extracting(error -> ((DomainException) error).getErrorCode())
          .isEqualTo(PublicationErrorCode.INVALID_PUBLICATION_REQUEST_KEY);
    }
    for (String roomId : List.of("0", "-1", "x", "9223372036854775808")) {
      assertThatThrownBy(() -> controller.add(jwt, roomId, headers(validKey()), request))
          .isInstanceOf(DomainException.class)
          .extracting(error -> ((DomainException) error).getErrorCode())
          .isEqualTo(PublicationErrorCode.INVALID_PUBLICATION_INPUT);
    }
    HttpServletRequest missing = mock(HttpServletRequest.class);
    given(missing.getHeaders("Idempotency-Key")).willReturn(Collections.emptyEnumeration());
    assertThatThrownBy(() -> controller.add(jwt, "20", missing, request))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).getErrorCode())
        .isEqualTo(PublicationErrorCode.INVALID_PUBLICATION_REQUEST_KEY);
    HttpServletRequest duplicate = mock(HttpServletRequest.class);
    given(duplicate.getHeaders("Idempotency-Key"))
        .willReturn(Collections.enumeration(List.of(validKey(), UUID.randomUUID().toString())));
    assertThatThrownBy(() -> controller.add(jwt, "20", duplicate, request))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).getErrorCode())
        .isEqualTo(PublicationErrorCode.INVALID_PUBLICATION_REQUEST_KEY);
    then(service).shouldHaveNoInteractions();
  }

  private static HttpServletRequest headers(String key) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    given(request.getHeaders("Idempotency-Key")).willReturn(Collections.enumeration(List.of(key)));
    return request;
  }

  private static String validKey() {
    return UUID.randomUUID().toString();
  }
}

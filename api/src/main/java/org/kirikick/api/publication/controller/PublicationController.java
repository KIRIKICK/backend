package org.kirikick.api.publication.controller;

import static org.kirikick.api.publication.controller.model.PublicationHttpErrorCodes.INTERNAL_ERROR;
import static org.kirikick.api.publication.controller.model.PublicationHttpErrorCodes.PUBLICATION_RECORD_UNAVAILABLE;
import static org.kirikick.api.publication.controller.model.PublicationHttpErrorCodes.PUBLICATION_UNAVAILABLE;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.kirikick.api.common.controller.dto.ErrorResponse;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.config.PublicationEnabledCondition;
import org.kirikick.api.publication.controller.dto.NkCreateRequest;
import org.kirikick.api.publication.controller.dto.NkCreateResponse;
import org.kirikick.api.publication.controller.dto.NkKickAddRequest;
import org.kirikick.api.publication.domain.PublicationRequestKey;
import org.kirikick.api.publication.domain.exception.PublicationErrorCode;
import org.kirikick.api.publication.service.AddKickToRoomService;
import org.kirikick.api.publication.service.OpenRoomService;
import org.kirikick.api.publication.service.PublicationResult;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Conditional(PublicationEnabledCondition.class)
@RequiredArgsConstructor
@Tag(name = "출제", description = "방을 열면서 첫 킥을 공개하거나, 기존 방에 킥을 추가합니다.")
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT")
public class PublicationController {

  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
  private final OpenRoomService openRoomService;
  private final AddKickToRoomService addKickToRoomService;

  @PostMapping(
      path = "/games",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "방 열기(첫 킥 공개)", operationId = "publish")
  @SecurityRequirement(name = "bearerAuth")
  @Parameter(
      name = IDEMPOTENCY_KEY,
      in = ParameterIn.HEADER,
      required = true,
      description = "소문자 UUID v4 요청 키")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "새 방과 첫 킥 공개",
        content = @Content(schema = @Schema(implementation = NkCreateResponse.class))),
    @ApiResponse(
        responseCode = "200",
        description = "같은 요청 키와 의미 입력의 성공 복구",
        content = @Content(schema = @Schema(implementation = NkCreateResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "유효하지 않은 요청 키 또는 출제 입력",
        content =
            @Content(
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "INVALID_PUBLICATION_REQUEST_KEY",
                      value =
                          "{\"code\":\"INVALID_PUBLICATION_REQUEST_KEY\",\"message\":\"유효하지 않은 출제 요청 키입니다.\"}"),
                  @ExampleObject(
                      name = "INVALID_PUBLICATION_INPUT",
                      value =
                          "{\"code\":\"INVALID_PUBLICATION_INPUT\",\"message\":\"출제 입력이 유효하지 않습니다.\"}")
                })),
    @ApiResponse(
        responseCode = "401",
        description = "JWT 인증 필요",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(
        responseCode = "403",
        description = "CSRF·Origin 검증 실패 또는 비활성 계정",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "참조 프리셋을 찾을 수 없음",
        content =
            @Content(
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "PRESET_NOT_FOUND",
                        value =
                            "{\"code\":\"PRESET_NOT_FOUND\",\"message\":\"출제 프리셋을 찾을 수 없습니다.\"}"))),
    @ApiResponse(
        responseCode = "409",
        description = "요청 키 충돌 또는 접수 중인 기존 방",
        content =
            @Content(
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "IDEMPOTENCY_KEY_CONFLICT",
                      value =
                          "{\"code\":\"IDEMPOTENCY_KEY_CONFLICT\",\"message\":\"같은 요청 키에 다른 출제 입력을 사용할 수 없습니다.\"}"),
                  @ExampleObject(
                      name = "OPEN_ROOM_EXISTS",
                      value = "{\"code\":\"OPEN_ROOM_EXISTS\",\"message\":\"접수 중인 방이 이미 있습니다.\"}")
                })),
    @ApiResponse(
        responseCode = "500",
        description = "내부 오류 또는 복원할 수 없는 출제 기록",
        content =
            @Content(
                schema = @Schema(implementation = ErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = INTERNAL_ERROR,
                      value =
                          "{\"code\":\""
                              + INTERNAL_ERROR
                              + "\",\"message\":\"요청을 처리하는 중 오류가 발생했습니다.\"}"),
                  @ExampleObject(
                      name = PUBLICATION_RECORD_UNAVAILABLE,
                      value =
                          "{\"code\":\""
                              + PUBLICATION_RECORD_UNAVAILABLE
                              + "\",\"message\":\"저장된 출제 식별 기록을 복원할 수 없습니다.\"}")
                })),
    @ApiResponse(
        responseCode = "503",
        description = "JDBC COMMIT 결과를 확인할 수 없음",
        content =
            @Content(
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = PUBLICATION_UNAVAILABLE,
                        value =
                            "{\"code\":\""
                                + PUBLICATION_UNAVAILABLE
                                + "\",\"message\":\"출제 결과를 확인할 수 없습니다.\"}")))
  })
  public ResponseEntity<NkCreateResponse> publish(
      @AuthenticationPrincipal Jwt jwt,
      HttpServletRequest request,
      @Valid @RequestBody NkCreateRequest body) {
    PublicationResult result =
        openRoomService.publish(
            UUID.fromString(jwt.getSubject()), requestKey(request), body.toInput());
    return response(result);
  }

  @PostMapping(
      value = "/rooms/{roomId}/games",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "방에 킥 추가", operationId = "addKick")
  @SecurityRequirement(name = "bearerAuth")
  @Parameter(
      name = IDEMPOTENCY_KEY,
      in = ParameterIn.HEADER,
      required = true,
      schema = @Schema(type = "string", format = "uuid"))
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        content = @Content(schema = @Schema(implementation = NkCreateResponse.class))),
    @ApiResponse(
        responseCode = "200",
        content = @Content(schema = @Schema(implementation = NkCreateResponse.class))),
    @ApiResponse(
        responseCode = "400",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(
        responseCode = "401",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(
        responseCode = "403",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(
        responseCode = "404",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(
        responseCode = "409",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(
        responseCode = "500",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(
        responseCode = "503",
        content =
            @Content(
                schema = @Schema(implementation = ErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = PUBLICATION_UNAVAILABLE,
                        value =
                            "{\"code\":\""
                                + PUBLICATION_UNAVAILABLE
                                + "\",\"message\":\"추가 출제 결과를 확인할 수 없습니다.\"}")))
  })
  ResponseEntity<NkCreateResponse> add(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String roomId,
      HttpServletRequest request,
      @Valid @RequestBody NkKickAddRequest body) {
    long targetRoomId = parseRoomId(roomId);
    PublicationResult result =
        addKickToRoomService.add(
            UUID.fromString(jwt.getSubject()), requestKey(request), body.toInput(targetRoomId));
    return response(result);
  }

  private static long parseRoomId(String roomId) {
    if (!roomId.matches("[1-9][0-9]*")) {
      throw new DomainException(PublicationErrorCode.INVALID_PUBLICATION_INPUT);
    }
    try {
      return Long.parseLong(roomId);
    } catch (NumberFormatException exception) {
      throw new DomainException(PublicationErrorCode.INVALID_PUBLICATION_INPUT);
    }
  }

  private static ResponseEntity<NkCreateResponse> response(PublicationResult result) {
    NkCreateResponse response =
        new NkCreateResponse(
            Long.toString(result.roomId()),
            Long.toString(result.gameId()),
            result.openedAt(),
            result.closesAt());
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .cacheControl(CacheControl.noStore())
        .body(response);
  }

  private static PublicationRequestKey requestKey(HttpServletRequest request) {
    Enumeration<String> headers = request.getHeaders(IDEMPOTENCY_KEY);
    List<String> values = headers == null ? List.of() : Collections.list(headers);
    if (values.size() != 1) {
      throw new DomainException(PublicationErrorCode.INVALID_PUBLICATION_REQUEST_KEY);
    }
    return PublicationRequestKey.canonicalV4(values.getFirst());
  }
}

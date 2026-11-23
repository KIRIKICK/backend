# 출제 Phase 1 저장 계약

## 방 공개와 게임 조립

- **방:** 접수 상태(`OPEN`·`CLOSED`)와 결과 상태(`NOT_READY`·`FINAL`)는 별개다. 방은 `OPEN / NOT_READY`로 조립하며, 공개 전에는 `opened_at`과 `closes_at`이 모두 NULL이다. `closeIntake()`는 공개 시각 쌍이 있는 방의 접수만 닫고 결과 상태는 바꾸지 않는다.
- **게임:** 정답이 NULL인 Game을 먼저 저장하고, 같은 질문의 선택지 원본에서 Choice 4개를 복사해 저장한 뒤 그중 하나를 정답으로 연결한다. 앱은 위치 0~3을 검사하고, 공개 전 `Game.verifyComplete()`에서 같은 게임의 서로 다른 저장된 선택지 ID 4개와 그 안의 정답을 확인한다.
- **DB 보장:** [V3](../../api/src/main/resources/db/migration/V3__create_question_room_game_core.sql)는 방의 두 시각이 함께 없거나 함께 있고 마감이 공개보다 늦도록 한다. 부분 유니크 인덱스는 공개 전이라도 호스트당 `OPEN` 방을 하나만 허용한다. 게임별 선택지 위치는 중복될 수 없고, 정답이 있으면 복합 FK가 같은 게임의 선택지만 허용한다. **정확히 24시간의 공개 기간, 선택지 4개, 정답 연결 완료**는 DB 제약이 아니라 앱이 검사한다.

## 출제 멱등성 기록

- [V4](../../api/src/main/resources/db/migration/V4__create_publication_idempotency.sql)의 기본 키는 `(user_id, request_key)`다. 요청 키는 NUL(U+0000)을 제외한 유효한 유니코드 문자열로, UTF-8 인코딩 기준 1~255바이트다. 공백 제거·대소문자 변환·유니코드 정규화 없이 원문 그대로 사용한다. 선점과 조회 모두 저장소 진입 시 NULL·빈 문자열·길이 초과·NUL·짝이 없는 서로게이트를 SQL 실행 전에 `INVALID_PUBLICATION_REQUEST_KEY`로 거절한다. 같은 사용자의 두 출제 종류는 키를 공유한다. `normalized_input`은 원본 요청이 아니라 **출제 의미**를 담은 JSONB 객체다. 종류별 대상(방 생성은 보상 프리셋, 게임 추가는 방), 질문, 오름차순으로 정렬한 서로 다른 선택지 ID 4개, 정답 ID를 저장한다. 선택지 순서만 바뀌면 같은 입력이다.
- **선점 → 완료:** `tryClaim()`은 새 행을 삽입했을 때만 선점 권한을 반환한다. 키 충돌 시 빈 결과는 선점하지 못했다는 뜻이지 성공이 아니다. `completeClaim()`은 선점 때의 PostgreSQL 트랜잭션 ID와 삽입마다 발급된 [선점 식별자](../../api/src/main/resources/db/migration/V5__add_publication_claim_identity.sql), 키·입력이 일치하는 미완료 행 한 건만 갱신한다. 선점과 게임 저장, 완료를 한 트랜잭션에 두면 실패 시 함께 롤백되고, 이전 선점 권한으로 나중에 기록을 완성할 수 없다.
- **조회:** DB는 `game_id`와 `record_expires_at`이 모두 NULL이거나 모두 값이 있도록 한다. `findForUpdate()`의 빈 결과는 행이 없다는 뜻이다. 두 값이 모두 NULL인 기존 행은 미완료 기록(`isComplete() == false`)으로 남는다. 저장 입력을 복원할 수 없거나 완료 값의 쌍이 깨졌거나 만료 시각이 무한대면 복원 오류다. 어느 쪽도 성공이나 재사용 가능한 빈 키로 취급하지 않는다.

## DB 시계와 만료

`DbTimeSource`는 PostgreSQL의 `clock_timestamp()`를 `Instant`로 읽는다. [공개·롤백 테스트](../../api/src/test/java/org/kirikick/api/publication/phase1/PublicationRoomPostgresTest.java)는 조립 마지막에 이 값으로 방을 공개하고, **저장된 공개 시각**을 다시 읽어 만료를 계산한다.

- 방 마감: `closes_at = opened_at + 24시간`
- 멱등성 기록 만료: `record_expires_at = opened_at + 24시간 + 30분 + publication.grace`

기본 애플리케이션은 유효한 `publication.grace`가 설정된 경우에만 출제 서비스와 `POST /games`를 등록한다. 설정이 없으면 출제 API는 등록되지 않으며, 빈 값이나 잘못된 기간을 설정하면 기동에 실패한다. Grace는 0 이상이며 마이크로초 단위로 표현할 수 있어야 한다. 이 값은 멱등성 기록의 보존 기간만 늘리고 방의 24시간 접수 기간은 바꾸지 않는다. 설정 위치는 [출제 API 활성화](../deployment/cloud-run-cd.md#출제-api-활성화)를 참고한다.

## 오류와 범위

새 입력의 종류·대상·선택지 오류와 내부 조립·저장 오류는 `DomainException.getErrorCode()`로 구분한다. 예를 들어 선택지가 부족한 새 입력은 `INVALID_PUBLICATION_CHOICES`지만, 같은 내용이 저장된 행에서 발견되면 `UNREADABLE_PUBLICATION_RECORD`다. DB 제약 위반이나 연결 장애를 입력 오류로 변환하지 않는다.

| 소유 도메인 | 오류 코드 | 발생 조건 |
| --- | --- | --- |
| `GameErrorCode` | `INVALID_CHOICE` | 선택지 원본이 게임의 질문과 일치하지 않거나 위치가 0~3 밖이다. |
| | `INVALID_CORRECT_CHOICE` | 정답으로 지정한 선택지가 저장된 같은 게임의 선택지가 아니다. |
| | `CORRECT_CHOICE_ALREADY_INITIALIZED` | 이미 연결한 정답을 다시 지정한다. |
| | `INCOMPLETE_GAME` | 공개 전 검사에서 저장된 선택지 4개와 그중 하나인 정답을 확인할 수 없다. |
| `RoomErrorCode` | `ROOM_ALREADY_PUBLISHED` | 조립 중인 방(OPEN이며 공개·마감 시각이 모두 없음)이 아닌 방을 공개하려 한다. |
| | `INVALID_CLOSING_TIME` | 공개 시각이 없거나 마감 시각이 공개 시각의 정확히 24시간 뒤가 아니다. |
| | `INCOMPLETE_PUBLICATION_TIME` | 공개·마감 시각 중 하나라도 없는 방의 접수를 종료하려 한다. |
| `PublicationErrorCode` | `INVALID_PUBLICATION_REQUEST_KEY` | 요청 키가 위 문자·바이트 길이 조건을 충족하지 않는다. |
| | `INVALID_PUBLICATION_TARGET` | 출제 종류, 질문 ID 또는 종류별 대상 ID 조합이 유효하지 않다. |
| | `INVALID_PUBLICATION_CHOICES` | 서로 다른 양수 선택지 ID 4개가 아니거나 정답이 그 안에 없다. |
| | `PUBLICATION_INPUT_SERIALIZATION_FAILED` | 정규화한 출제 입력을 저장할 JSON으로 직렬화하지 못했다. |
| | `UNREADABLE_PUBLICATION_RECORD` | 저장된 입력을 복원할 수 없거나 게임 ID·만료 시각의 조합이 유효하지 않다. |
| | `PUBLICATION_CLAIM_COMPLETION_FAILED` | 선점한 트랜잭션에서 의도한 게임 ID·만료 시각으로 정확히 한 건을 완성하지 못했다. |

공개 API·HTTP 오류 매핑·스위퍼·정산·운영용 시드 데이터는 Phase 1 저장 계약의 범위 밖이다.

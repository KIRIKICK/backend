# 방·킥·참여·답변 저장 기반

이 변경은 서비스 구현을 위한 최소 엔티티와 Repository를 제공한다. 공개 API와 전체 GAME 명세의 구현 완료를 의미하지 않는다.
킥은 API 도메인 이름에 맞춰 `Game`으로, 참여는 **방 가입이 아닌 특정 게임의 참여 명부**로 표현한다.

| 엔티티 | 저장 내용 | 기본 조회 |
| --- | --- | --- |
| `Room` | 내부 ID, 공개 UUID, 방장 회원 ID, 방 이름 | 공개 UUID |
| `Game` | 내부 ID, 공개 UUID, 방·출제자 ID, 종류, 진행 상태, 마감 시각 | 공개 UUID |
| `GameParticipant` | 내부 ID, 게임 범위 공개 UUID, 게임·회원 ID, 최초 풀이 시작 시각 | UUID, 게임+회원, 게임 명부 |
| `GameAnswer` | 내부 ID, 제출 UUID, 게임·참여자 ID, 선택지 또는 투표 대상, 접수 시각 | UUID, 참여자, 게임 답변 |

## 저장 규칙

- 기존 `User`/`OAuthIdentity`와 같이 내부 PK/FK는 `Long`, 공개 식별자는 UUID다. 엔티티 객체 연관관계 대신 FK ID를 참조하며 Repository는 인증·권한 판단을 하지 않는다.
- `Room`, `Game`, `GameParticipant`는 `BaseTimeEntity`, 수정하지 않는 제출 사실인 `GameAnswer`는 `BaseCreatedTimeEntity`를 사용한다. 시각은 `Instant`다.
- 게임 종류는 `NAZAL`/`VOTE`, 진행 상태는 `IN_PROGRESS`/`FINALIZING`/`FINISHED`다. 생성 기본값은 `IN_PROGRESS`이며 상태 전환·마감은 후속 서비스 작업에서 구현한다.
- 참여자는 `(game_id, user_id)`로 유일하고, 답변은 `participant_id`당 하나다. 동일 회원도 다른 게임에는 참여할 수 있다. 참여·답변의 동시 INSERT는 DB UNIQUE 제약으로 중복 저장을 막는다.
- `GameParticipant.start()`는 이미 기록한 시작 시각을 유지한다. **동시 시작 요청의 원자성까지 이 메서드만으로 보장하지 않는다.** 서비스 구현 시 DB 잠금/원자적 갱신 및 트랜잭션 경계를 추가해야 한다.
- 나잘알 답변은 `selected_option_key`의 `1`~`4`, 투표는 `target_participant_id`를 사용한다. 두 값 중 정확히 하나만 저장한다. 투표는 자기 자신을 대상으로 할 수 없다.
- 답변의 작성 참여자와 투표 대상은 모두 같은 게임에 속해야 한다. Flyway의 복합 FK가 이를 보장한다. 답변의 게임 종류와 payload 종류 일치는 서비스에서 게임을 읽고 검증해야 한다.
- FK는 `ON DELETE RESTRICT`다. 회원/방/게임 삭제를 기존 참여·답변의 연쇄 물리 삭제로 구현하지 않는다. 탈퇴 마스킹과 삭제 정책은 조회 DTO 및 후속 서비스 책임이다.
- 회원 상태·현재 방 멤버십·명부 인원·게임 종료 여부·마감 시각과 시작/접수 순서·게임 종류별 제출 자격은 아직 검사하지 않는다. 생성 팩토리의 참조 ID 검증은 양수 여부이며 실제 존재는 DB FK로 검증한다.

## 이번 변경에 포함하지 않은 모델

방 멤버십·초대·강퇴/재입장 차단, 질문/선택지/정답/보상 스냅샷, FIXED/OPEN 명부 정책, 멱등 요청 기록,
숨김/삭제 상태, 결과·보상·알림 outbox는 별도 작업이다. 마감 시각은 서버가 계산한 값을 전달하며 생성 팩토리는 현재 시각이나 2시간 정책을 자체 결정하지 않는다.
위 모델과 서비스 검증 없이 이 Repository를 공개 CRUD API로 바로 노출하지 않는다.

## 스키마와 검증

- V1/V2 변경 없이 `V3__create_rooms_games_participants_and_answers.sql`을 추가한다. Flyway가 테이블·인덱스·UNIQUE/CHECK/FK를 관리한다.
- `RoomTest`, `GameDomainTest`: 필수값, 답변 형태, 최초 시작 시각 유지.
- `GameRepositoriesTest` (H2): 저장 후 flush/clear/재조회, finder 범위와 auditing. PostgreSQL 계약을 검증하는 테스트가 아니다.
- `GameSchemaMigrationTest` (PostgreSQL Testcontainers): V2→V3 업그레이드와 기존 회원 보존, 전체 운영 엔티티 Hibernate validate, 실제 FK/CHECK/UNIQUE 및 동시 중복 쓰기.
- 기존 `UserAuthSchemaMigrationTest`는 원래 검사하던 V1/V2의 정확한 스키마를 계속 검증하도록 target을 2로 고정한다. 최신 전체 스키마 연결은 새 테스트와 Flyway 기반 시각 테스트에서 확인한다.

```sh
./gradlew spotlessCheck
./gradlew :api:test --tests '*RoomTest' --tests '*GameDomainTest' --tests '*GameRepositoriesTest' --tests 'org.kirikick.architecture.*'
./gradlew :api:test --tests '*GameSchemaMigrationTest' --tests '*UserAuthSchemaMigrationTest'
./gradlew build
./gradlew spotbugsMain --continue --no-daemon
python3 scripts/check_spotbugs_report.py
```

PostgreSQL/Redis 통합 테스트에는 Docker가 필요하다. 외부 DB나 개인 `.env`로 대체하지 않는다.

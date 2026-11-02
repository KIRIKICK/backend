# JPA 슬라이스 테스트

아래 명령은 Gradle 래퍼가 있는 `backend` 루트에서 실행합니다.

- `@H2JpaTest`: 외부 DB 설정과 무관하게 새 H2 메모리 DB를 사용합니다.
- `@PostgresJpaTest`: `TEST_POSTGRES_URL`로 지정한 PostgreSQL DB를 사용하며 H2로 대체하지 않습니다.
- 둘 다 JPA 슬라이스만 로드하고 Querydsl 설정을 포함합니다. 각 테스트의 트랜잭션은 롤백됩니다.
- Flyway는 끄고 Hibernate `create-drop`으로 테이블을 생성·삭제합니다. **Postgres URL에는 반드시 폐기 가능한 전용 테스트 DB를 지정하세요. 운영·개발 DB를 사용하면 안 됩니다.**

```java
@H2JpaTest // PostgreSQL 검증이 필요하면 @PostgresJpaTest
class ExampleRepositoryTest {
}
```

기본 실행은 외부 DB 없이 가능합니다. PostgreSQL 검증 테스트는 URL이 없으면 건너뜁니다.

```sh
./gradlew :api:test

TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/kirikick_test \
TEST_POSTGRES_USERNAME=postgres \
TEST_POSTGRES_PASSWORD=secret \
./gradlew :api:test --rerun-tasks
```

PostgreSQL 사용자 기본값은 `postgres`, 비밀번호 기본값은 빈 문자열입니다.
직접 작성한 `@PostgresJpaTest` 테스트에는 URL이 필수입니다. 위의 조건부 실행은 기본 제공 검증 테스트에만 적용됩니다.

## CI

저장소 루트의 `.github/workflows/ci.yml`은 `main` 대상 PR과 `main` push에서 실행됩니다.
Java 21과 임시 `postgres:17-alpine` 서비스로 `./gradlew build --no-daemon`을 실행합니다.
`TEST_POSTGRES_URL`과 테스트 전용 계정 정보를 전달하므로 H2 테스트와 PostgreSQL 검증 테스트가 함께 실행됩니다.
CI의 DB와 계정은 테스트 전용이며 운영·개발 DB나 GitHub Secrets를 사용하지 않습니다.
현재 JPA 슬라이스 테스트는 Flyway를 비활성화하므로 마이그레이션 검증은 포함하지 않습니다.

## 회원/OAuthIdentity 마이그레이션 검증

UserAuthSchemaMigrationTest는 TEST_POSTGRES_*가 있는 경우에만 실행됩니다. 매번 임의의
격리 스키마에 Flyway를 적용하고, Hibernate mapping validation과 UUID·OAuth 고유 제약·FK·CHECK
제약을 실제 PostgreSQL에서 검증합니다. 이 테스트는 생성한 스키마만 삭제하며 public 스키마나
Flyway clean을 사용하지 않습니다.

PostgresJpaTest의 create-drop 스키마는 scalar OAuthIdentity.userId의 DB FK를 만들지 않습니다.
따라서 repository slice는 저장·조회·auditing만, FK와 migration 제약은
UserAuthSchemaMigrationTest만 검증합니다.

## 아키텍처 규칙

공유 `ArchitectureTest`는 각 Java 모듈의 main 클래스 출력만 검사합니다. API 전용 클래스나 패키지에는 의존하지 않습니다.
공통 소스와 모듈 자동 적용 방식은 [루트 문서](../../../README.md#아키텍처-검사)를 참고합니다.
기능별로 `controller`, `controller.dto`, `service`, `repository`, `domain` 패키지를 사용하고,
외부 연동 구현이 필요할 때만 `infra`를 추가합니다. 빈 레이어를 미리 만들지는 않습니다.

- `domain` 패키지와 JPA `@Entity`·`@MappedSuperclass`·`@Embeddable`의 필드는 private으로 둡니다. static final 상수는 예외입니다. Querydsl Q 클래스는 도메인 검사에서 제외합니다.
- 도메인에는 JavaBean 형태의 인스턴스 setter(`setXxx`, 인자 1개)를 두지 않습니다. 조회 getter와 `rename`, `cancel` 같은 행동 메서드는 허용합니다.
- 도메인은 controller·service·repository·infra에 의존하지 않습니다.
- service의 public·protected 반환 시그니처에는 repository 타입을 노출하지 않습니다. repository 패키지 또는 Spring Data `Repository` 구현 여부로 판단하고, 제네릭·배열·타입 경계도 검사합니다. 생성자 주입은 허용합니다.
- `@RestController`의 요청 매핑 메서드는 `controller.dto`의 DTO, DTO 배열·컬렉션 또는 이를 감싼 `ResponseEntity`를 반환합니다. 본문 없는 `void`·`ResponseEntity<Void>`는 허용합니다. 엔티티·String·Object·raw 컨테이너·모호한 wildcard 응답은 허용하지 않습니다. 매핑 없는 helper 메서드는 대상이 아닙니다.

참조 타입 자체를 금지하거나 객체의 필드 그래프를 재귀 검사하지 않습니다.
따라서 엔티티 연관관계 및 그 조회는 허용하며, DTO 내부의 엔티티·민감정보 노출은 별도 리뷰 대상입니다.
현재 규칙은 반환 시그니처를 검사하므로 `Object`로 감춘 repository의 실제 런타임 값까지 판별하지는 않습니다.

```sh
./gradlew :api:test --tests 'org.kirikick.architecture.*'
```

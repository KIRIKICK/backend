# JPA 슬라이스 테스트

`src/test/resources/application-test.yaml`은 기본적으로 H2 in-memory DB를 사용합니다.

- `@H2JpaTest`: H2로 빠르게 실행하는 JPA 단위 테스트입니다.
- `@JpaSliceTest`: 공통 PostgreSQL JPA 환경에 API의 Querydsl·auditing 설정을 추가합니다. 웹 계층은 로딩하지 않습니다.

`test-support`의 `@PostgresJpaTest`는 컨테이너 연결·JPA 슬라이스·테스트 DB 교체 방지·DDL 설정을
담당합니다. API 전용 설정에 의존하지 않으므로 다른 모듈에서도 재사용할 수 있습니다.
API 테스트에서는 이를 조합한 `@JpaSliceTest`만 사용하며 별도의 상속은 필요하지 않습니다.

```java
@JpaSliceTest
class UserRepositoryTest {}
```

## PostgreSQL Testcontainers

Docker가 실행 중이면 별도 DB URL·계정·`.env` 없이 PostgreSQL 테스트가 자동 실행됩니다.

```sh
./gradlew :api:test
./gradlew :api:test --tests 'org.kirikick.api.support.UserAuthSchemaMigrationTest' --rerun-tasks
```

IntelliJ에서도 Gradle 테스트 실행 대상을 `backend` 작업 디렉터리에서 실행하면 동일하게 동작합니다.
macOS는 Docker Desktop, Windows는 Docker Desktop 또는 WSL2 Docker 환경을 실행한 뒤 사용하세요.
Docker를 사용할 수 없으면 PostgreSQL 테스트는 skip하거나 H2로 대체하지 않고 실패합니다. 반대로 H2·단위
테스트만 선택하면 Docker가 필요하지 않습니다.

`test-support`는 테스트 JVM 안에서 PostgreSQL 컨테이너 하나를 lazy하게 시작해 Spring JPA와 직접
JDBC·Flyway 테스트가 공유합니다. 테스트 JVM·Gradle worker·별도 실행 사이에는 컨테이너를 공유하지 않으며,
`withReuse(true)` 같은 실행 간 재사용도 사용하지 않습니다. 처음 실행은 이미지 다운로드 때문에 느릴 수 있고,
Testcontainers가 JVM 종료 후 컨테이너를 정리합니다.

PostgreSQL `@PostgresJpaTest`는 Flyway를 끄고 Hibernate `create-drop`을 사용합니다. 테스트 종료 후
테이블이 제거되며, 애플리케이션 DB URL·Supabase·Upstash 설정을 테스트에 전달하지 않습니다.

## CI

저장소 `.github/workflows/ci.yml`은 GitHub-hosted runner의 Docker에서 Testcontainers를 시작한 뒤
`./gradlew build --no-daemon`을 실행합니다. 별도 PostgreSQL service나 외부 DB 환경변수는 사용하지 않으므로
로컬과 CI가 같은 경로를 검증합니다.

## 회원/OAuthIdentity 마이그레이션 검증

`UserAuthSchemaMigrationTest`는 매번 임의의 `user_auth_<uuid>` 스키마에 Flyway를 적용하고 Hibernate
`validate`로 매핑을 검증한 뒤 해당 스키마만 삭제합니다. public 스키마나 외부 DB 전체를 정리하지 않습니다.

## 향후 Redis 테스트

이번 공통 지원은 PostgreSQL만 제공합니다. OAuth transaction·Refresh session처럼 실제 Redis-backed 동작의
첫 통합 테스트와 함께 `test-support`의 독립 lazy Redis 지원을 추가합니다. PostgreSQL만 필요한 테스트는
Redis를 시작하지 않고, Redis만 필요한 테스트는 PostgreSQL을 시작하지 않습니다. 둘 다 필요한 테스트는
테스트 JVM 안에서 서비스별 컨테이너 하나씩을 공유합니다.

## 아키텍처 규칙

공유 `ArchitectureTest`는 각 Java 모듈의 main 클래스 출력만 검사합니다. API 전용 클래스나 패키지에
의존하지 않으며, 공통 소스와 모듈 자동 적용 방식은 [루트 문서](../../../README.md#아키텍처-검사)를 참고하세요.

기능별 `controller`, `controller.dto`, `service`, `repository`, `domain` 패키지 규칙과 controller DTO 반환 규칙은
공유 ArchUnit 테스트로 검증합니다.

```sh
./gradlew --tests 'org.kirikick.architecture.*'
```

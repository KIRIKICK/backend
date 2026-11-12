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

## Redis Testcontainers

인증 기능의 Redis 테스트는 `test-support`의 JVM-lazy `RedisTestServer`(`redis:7.4-alpine`)를 사용한다. Spring Session, OAuth/signup claim marker, login/Refresh state 테스트는 **한 테스트 JVM 안에서 한 Redis container**를 공유한다. PostgreSQL container와는 독립적이며, `withReuse(true)` 또는 실행 간 공유를 사용하지 않는다.

- Redis-only 테스트는 PostgreSQL을 시작하지 않고, PostgreSQL-only 테스트는 Redis를 시작하지 않는다.
- 각 테스트는 application auth와 Spring Session 모두 고유 namespace를 사용한다. `FLUSHALL`, `FLUSHDB`, developer `.env`, Supabase, Upstash, 실제 카카오는 사용하지 않는다.
- Docker를 사용할 수 없으면 이 통합 테스트는 skip·외부 연결·H2 대체 없이 실패한다.
- helper-only Redis container 테스트를 만들지 않는다. 실제 Session/auth repository 테스트가 fixture를 검증한다.

```sh
./gradlew :api:test --tests '*Redis*Test' --rerun-tasks
./gradlew :api:test --tests 'org.kirikick.api.auth.*' --rerun-tasks
```

명령은 `backend` 디렉터리에서 실행한다. OAuth의 실제 카카오/HTTPS 및 지정 DEV Redis smoke는 이 테스트 범위가 아닌 배포 gate다. 상세 운영 정책은 [AUTH 도메인 문서](../../../docs/domain/auth.md)를 참고한다.

## Local OIDC callback

`LocalOidcProvider`는 loopback authorization·token·JWKS endpoint로 PKCE, `client_secret_post`, ID token 처리를 검증한다. `OAuthSecurityChainIntegrationTest`는 신규 subject의 `SIGNUP_REQUIRED`, 비활성 identity 차단, Session·publication 장애, provider token 비저장, callback 교체 경합을 Spring Session Redis와 PostgreSQL Testcontainers 조합으로 검증한다. 실제 카카오에는 호출하지 않는다.

```sh
./gradlew :api:test --tests org.kirikick.api.auth.OAuthSecurityChainIntegrationTest --rerun-tasks
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

## 아키텍처 규칙

공유 `ArchitectureTest`는 각 Java 모듈의 main 클래스 출력만 검사합니다. API 전용 클래스나 패키지에
의존하지 않으며, 공통 소스와 모듈 자동 적용 방식은 [협업 가이드](../../../CONTRIBUTING.md#아키텍처-검사)를 참고하세요.

기능별 `controller`, `controller.dto`, `service`, `repository`, `domain` 패키지 규칙과 controller DTO 반환 규칙은
공유 ArchUnit 테스트로 검증합니다.

```sh
./gradlew :api:test --tests 'org.kirikick.architecture.*'
```

## Signup Refresh 발급 시도 전 실패 회귀 (#50, #51)

`SignupRefreshCookieFailureIntegrationTest`는 가입 실패의 grant 보상과 Refresh 쿠키 보존을 실제 HTTP로 검증한다.

| 실패 주입 지점·원인 | 최종 응답 | Refresh Set-Cookie |
| --- | --- | --- |
| grant 반환 전 불투명한 발급 오류(A0) | 401 `AUTHENTICATION_REQUIRED` | 없음 |
| 실제 FINISH_SIGNUP 완료 후 불투명한 결과 전달 오류(A1) | error dispatch를 거친 401 `AUTHENTICATION_REQUIRED` | 없음 |
| 같은 A1의 Redis 연결 오류로 분류되는 원인 | MVC 503 `AUTH_STATE_UNAVAILABLE` | 없음 |

- A1은 실제 발급·Redis 종료 처리 **뒤** 호출자에게 결과가 돌아오지 않는 상황을 한 번 주입한다. 실제 Redis 장애나 운영 발생을 관측했다는 뜻은 아니다.
- 가입 DB는 완료되고 회원·OAuth 신원은 남는다. A1의 Refresh grant는 폐기되고 쿠키 발급은 시작되지 않는다. claim은 종료되지만 onboarding Session은 남을 수 있으며, Session의 존재와 proof 권한의 유효성은 구분한다.
- #50에서는 A1의 삭제 헤더와 늦은 A 응답에 의한 B 쿠키 소실을 재현했다. #51 회귀에서는 삭제 헤더가 없어도 loopback 중계로 A의 **첫 응답 헤더**를 보류하고 정상 B OAuth·Refresh를 수행한다. A 전달 전후 B 쿠키·서버 grant 유지와 실제 Refresh 성공, A grant inactive를 확인한다. 중계 전후 상태·본문·모든 Set-Cookie와 캐시 헤더를 대조하고 클라이언트의 헤더 수신도 별도로 확인한다.
- 다른 계정의 기존 유효 로그인도 가입의 발급 시도 전 실패로 삭제하지 않는다. 동일 쿠키 저장소에서 기존 쿠키·grant 보존과 실제 Refresh 성공을 확인한다. 실패한 가입을 계정 전환 성공으로 취급하지 않는다.
- Origin·CSRF·live signup BUSY를 우회하지 않는다. 검증은 CookieManager 기반이며 실제 브라우저의 Secure·SameSite·교차 사이트 동작, 쿠키 발급 시작 후 부분 쓰기, 실제 Redis 장애는 미검증이다.
- 다섯 테스트와 중계는 회귀 검증으로 유지한다. `writeRefresh` 호출 시도 이후 실패의 기존 폐기·삭제 보상은 유지하며, 부분 전달이나 늦은 삭제 경합은 해결하지 않는다. 기존 불투명한 예외의 401 처리, OAuth 보상과 R2·R3·R5 정책도 변경하지 않는다.

```sh
./gradlew :api:test --tests org.kirikick.api.auth.SignupRefreshCookieFailureIntegrationTest
```

## 동일 Refresh 토큰 동시 사용 회귀 (#53)

`RefreshTokenConcurrencyIntegrationTest`는 local OIDC와 Testcontainers의 PostgreSQL·Redis를
사용해 같은 Refresh 토큰을 두 실제 HTTP 요청으로 보낸다. Redis 회전 호출 직전의 test-only
latch는 두 handler가 실제 저장소 호출에 도달한 뒤 함께 진행하게 하며, 회전·재사용 결과는
stub하지 않는다.

테스트는 두 최종 응답의 상태·`Set-Cookie`, family 폐기 뒤 새 Refresh·보호 API 요청, 그리고
새 OAuth 로그인으로의 복구를 확인한다. captured header를 `CookieManager`에 순서대로 적용한
검사는 통제된 cookie-jar 모델이며, 실제 브라우저·다중 탭·네트워크 header 도착 순서를
검증하는 것은 아니다.

```sh
./gradlew :api:test --tests org.kirikick.api.auth.RefreshTokenConcurrencyIntegrationTest --rerun-tasks
```

## OAuth 성공 응답의 쿠키 적용·전달 순서 선행 검증 (#55)

`OAuthSuccessResponseOrderIntegrationTest`는 A의 서버 처리가 정상 종료된 뒤 B를
시작하는 공개 OAuth 경로를 사용한다. 같은 계정·다른 계정 각각에서 실제 callback
응답 헤더를 capture하고, 새 `CookieManager`에 A→B·B→A 순서로 적용한다.
이 모델은 실제 네트워크 헤더 도착 순서나 브라우저 동작의 증거는 아니다.

다른 계정의 실제 전달 검사는 callback 전용 relay에서 A의 첫 응답 헤더를 보류하고
B를 먼저 적용한 뒤 A를 전달한다. 상태·`Location`·캐시·모든 `Set-Cookie`의 보존과
클라이언트의 헤더 수신을 확인하며, body 또는 Future 완료 지연만을 근거로 삼지 않는다.
최종 쿠키로 Refresh한 뒤 선택 family·JWT·보호 API의 계정 결합을 확인한다. 성공 callback은 기존 CSRF를 만료시킬 수 있으므로, 모든 성공 응답을 적용한 뒤 CSRF를 순차적으로 다시 확보해 R5 초기 CSRF 응답 경합과 분리한다.
미선택 family 상태는 별도로 관찰하며, 마지막 로그인 시작이 승리해야 한다는 정책을
새로 정하거나 재사용 탐지 정책을 완화하지 않는다.

기존 OAuth의 stale/replacement, R1의 onboarding 만료, R3의 동일 토큰 재사용,
R4의 실패 보상·늦은 삭제, R5의 초기 CSRF 응답 경합과는 다른 검증이다.
실제 브라우저·FE 화면·운영 발생 여부와 배포 FE의 원본은 검증하지 않는다.

```sh
./gradlew :api:test --tests org.kirikick.api.auth.OAuthSuccessResponseOrderIntegrationTest --rerun-tasks
```

## 초기 CSRF 발급 응답의 쿠키·본문 토큰 검증 (#59)

`CsrfBootstrapResponseOrderIntegrationTest`는 cookie-free `GET /auth/csrf` 응답 A/B에서 얻은 실제 쿠키·masked 본문 토큰을 사용한다. 새 `CookieManager`에 A→B·B→A 순서로 응답을 적용하고 body A/B를 선택한 네 모델에서 matching과 crossed 조합을 검증한다. raw 쿠키가 서로 다르다는 전제가 성립하지 않으면 crossed fixture를 강행하지 않으며, masked 문자열이 서로 다르다는 사실을 raw 쿠키 회전의 증거로 사용하지 않는다.

교차 Refresh는 403만으로 판단하지 않는다. Refresh 발급이 없고 회전 경계에 진입하지 않으며 grant family가 보존되는지 확인한 뒤, 현재 쿠키로 실제 CSRF 토큰을 다시 얻어 같은 grant가 정상적으로 회전하는지 확인한다. 성공 비교는 서로 다른 grant를 사용해 Refresh 재사용 탐지와 섞이지 않게 한다. 같은 raw 쿠키에서 실제로 다시 얻은 여러 masked 토큰도 각각 유효한지 검증한다.

Refresh 보존 관찰은 Redis에서 다시 읽은 `LoginSession`의 활성·계정·만료 상태와, 이 객체에 투영되지 않는 family hash의 `currentDigest`·`lastUsedAt` 두 필드다. 회전 미진입 및 원래 grant의 후속 성공은 기존 토큰 연결과 사용 가능성을 함께 확인한다. 전체 Redis 데이터나 자연 감소하는 TTL의 동일성을 주장하지 않으며 다른 family를 탐색하지 않는다.

`OAuthBoundaryIntegrationTest`의 한 회귀는 기존 정상 OAuth/signup fixture로 교차 CSRF 요청을 보낸다. claim·completion·login/publication 미진입과 application·proof·claim·onboarding 상태 보존을 확인하고, 현재 쿠키의 토큰을 다시 얻은 뒤 같은 application이 정상 완료되는지 확인한다. 기존 단일 발급·cookie profile·controller·Origin/CORS·replay 검사는 재사용하며, CSRF 재취득 뒤 비허용 Origin을 다시 보내는 별도 실험은 이 범위에 포함하지 않는다.

가입에서는 기존 application의 미완료·회원/identity 수, proof의 slot·proof ID·provider·subject·session 연결, 현재 서버 시점의 `inspectProof=CURRENT` 및 같은 onboarding 쿠키의 context를 확인한다. 이 Redis 결과는 아직 claim되지 않은 유효 proof 상태를 확인하며, 이후 같은 application의 완료·identity 연결·Refresh 발급·onboarding 세션 삭제로 복구를 검증한다. proof 전체 객체나 TTL의 일괄 비교는 하지 않는다.

CookieManager 검사는 캡처한 HTTP 응답의 적용·본문 선택 모델이다. 실제 네트워크의 첫 헤더·본문 도착 순서, 브라우저의 SameSite·cookie 정책, FE 메모리 상태·재시도·UI 복구 또는 운영 사용자 영향을 증명하지 않는다. exact CSRF exception 관찰과 relay·브라우저 검증도 이 테스트의 범위가 아니다.

다음 명령은 `backend` 디렉터리에서 실행한다. Docker와 local OIDC·Testcontainers가 필요하며, 아래 명령은 실행 예시이지 특정 소스 버전의 통과 기록은 아니다.

```sh
./gradlew :api:test --tests org.kirikick.api.auth.CsrfBootstrapResponseOrderIntegrationTest --rerun-tasks
./gradlew :api:test --tests org.kirikick.api.auth.OAuthBoundaryIntegrationTest --rerun-tasks
```

관련 인증 회귀와 전체 검증은 target 실행 뒤에 수행한다. `spotbugsMain`은 `api/main` 바이트코드를 분석하며 신규 테스트 소스를 분석하는 검사가 아니다.

여섯 실행 케이스는 현재 계약을 유지할 회귀 후보다. 결과는 정상 조합 허용·교차 거절·상태 보존·재취득 복구의 **BE/client-model 계약 확인**으로 분류하며 제품 결함이나 FE 복구 성공을 뜻하지 않는다. 별도 결함이 관찰되지 않으면 tests-only PR로 검토한다. 격리 실행·소스 해시 대조·실패 메시지 진단은 일회성 검증 기록으로 구분하며, 실제 실행 시점과 결과는 해당 소스에 대응하는 별도 기록으로 남긴다.

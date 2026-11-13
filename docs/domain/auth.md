# AUTH 도메인 운영 정책

## 책임 경계

| 상태 | 소유자 | 보존과 목적 |
| --- | --- | --- |
| OAuth authorization request·가입 proof payload | Spring Session Redis | OAuth 10분, 가입 5분의 임시 브라우저 흐름 |
| OAuth/signup consume marker | 애플리케이션 Redis key | 동시 callback·가입의 원자적 한 번 선점 |
| login/Refresh Session | 애플리케이션 Redis key | 서비스 로그인·회전·재사용 탐지 |
| Access JWT | 클라이언트 응답 | RS256 서명, 15분; 원문을 Redis Session으로 저장하지 않음 |
| User·OAuthIdentity | PostgreSQL | 명시적 가입 확정 뒤의 영속 회원 상태 |
| SignupApplication | PostgreSQL | 가입 완료·회원·identity·원래 결과의 단일 transaction; 5분 권한과 물리 보존은 별개 |

Spring Session은 OAuth 프로토콜 payload와 가입 대기 data만 보관한다. Session attribute 삭제나 invalidate는 분산 일회용 lock이 아니다. consume marker는 payload를 복제하지 않고 서버 발급 flow/proof ID만 짧게 선점한다. Spring Session의 내부 key를 애플리케이션 lock으로 조작하지 않는다.

temporary marker는 애플리케이션 namespace의 `temporary-slot:<opaque-slot-id>` **하나의 HASH**다. `FLOW`, `PROOF`, `SIGNUP_CLAIMED` 상태와 opaque ID·absolute deadline·필요한 claim ID/deadline만 둔다. provider subject, nickname, OAuth/proof payload, cookie/session ID, provider token은 이 HASH에 복제하지 않는다. HASH의 TTL은 permission이 아니다. FLOW는 OAuth absolute deadline + idle 10분 + cleanup margin 1분, PROOF는 proof deadline + idle 5분 + margin 1분, accepted signup claim은 claim lease 5분 + idle 5분 + margin 1분 뒤에 정리될 수 있다. 인가 판단은 언제나 서버 `Clock`의 embedded absolute deadline으로 한다.

서비스 인증은 HttpSession 인증이 아니다. JWT 검증 후 Redis login Session의 ACTIVE 소유자와 DB User ACTIVE를 다시 확인한다. provider token과 persistent SecurityContext·authorized client는 callback 뒤 보관하지 않는다.

## 카카오 OAuth callback

카카오는 confidential client로 `client_secret_post`, `openid`, authorization-code flow와 PKCE(S256)를 사용한다. Spring Security가 authorization code 교환, `state`, `nonce`, OIDC 서명·issuer·audience 검증을 담당한다.

- `AUTH_FE_ORIGIN`은 필수 기본 FE Origin이다. `AUTH_DEV_ADDITIONAL_FE_ORIGINS`는 쉼표로 구분한 DEV 전용 추가 Origin 목록이며, 비워 두면 기본 Origin만 허용한다. 추가 목록은 `dev`(선택적으로 `test`와 함께)에서만 허용하고 `local` 또는 `prod`가 활성화되면 기동을 거절한다. 이 검증은 lazy initialization 여부와 관계없이 기동 시 수행한다.
- 허용 Origin은 scheme·host·port가 일치하는 HTTP(S) Origin만 사용한다. 경로(마지막 `/` 포함)·query·fragment·userinfo·wildcard는 허용하지 않으며, `localhost`와 `127.0.0.1`, 다른 scheme 또는 port는 서로 다르다. CORS와 unsafe 요청 Origin 검증은 같은 allowlist를 사용한다.
- FE는 OAuth 시작 시 선택한 Origin을 `frontend_origin` query parameter로 명시할 수 있다. 값이 없으면 기본 FE Origin을 사용한다. 정확히 하나의 허용 Origin만 사용할 수 있으며, 빈 값·중복 값·전체 callback URL·미허용 값은 `400 OAUTH_FAILED`, `Cache-Control: no-store`, `Location` 없이 거절한다. 이 경우 기존 Session이나 OAuth flow를 변경하지 않는다. 유효한 selector로 시작한 뒤 일반 오류가 발생하면 선택한 FE의 `ERROR` callback으로 이동한다.
- 검증된 선택 Origin은 OAuth authorization request와 같은 Spring Session payload에만 연결한다. provider redirect URI, OAuth `state`, callback query 또는 `Origin`·`Referer` 헤더로 출발 FE를 추론하거나 덮어쓰지 않는다. 카카오가 반환하는 BE redirect URI는 기존 `KAKAO_REDIRECT_URI`를 유지한다.
- 연결된 User가 ACTIVE인 기존 OAuth 계정은 서비스 로그인 grant를 발급하고, 검증된 흐름에 연결된 FE callback에 `AUTHENTICATED`로 `303` 이동한다.
- 연결된 User가 WITHDRAWN·SUSPENDED·BANNED인 기존 OAuth 계정은 grant나 proof 없이 `OAUTH_FAILED`로 이동한다.
- 신규 subject는 temporary Session에 가입 proof를 저장한 뒤 같은 proof ID로 `SignupApplication`을 발행하고, 검증된 흐름에 연결된 FE callback에 `SIGNUP_REQUIRED`로 이동한다. 이 callback은 User를 생성하거나 서비스 인증을 발급하지 않는다.
- provider 실패도 검증된 흐름의 FE callback에 고정된 `ERROR` 결과로 이동한다. state/session binding 또는 payload 문맥을 복원할 수 없으면 기본 FE의 `ERROR` callback을 사용한다. 반면, 유효하게 binding된 이전 배포 flow에 선택 Origin attribute만 없으면 기본 FE에 기존 정상 결과(`AUTHENTICATED`, `SIGNUP_REQUIRED`, provider `ERROR`)를 반환한다.
- 저장된 선택 Origin이 잘못되었거나 현재 allowlist에서 제거되었으면 로그인 grant나 가입 proof를 발행하지 않고 기본 FE의 `ERROR` callback만 사용한다. 인증 상태 저장소 또는 DB 가용성 장애는 redirect로 바꾸지 않고 기존 `AUTH_STATE_UNAVAILABLE` 503 계약을 유지하며, 이 우선순위는 OAuth 시작 실패에도 적용한다.
- 성공·실패 redirect에는 `Cache-Control: no-store`를 적용한다. callback 경로는 `/auth/kakao/callback`으로 고정하며 URL에는 code, state, provider token, subject, proof, access/refresh token 또는 임의 return URL을 넣지 않는다.

## Access JWT 서명 정책

- 서비스 Access JWT는 RS256(RSASSA-PKCS1-v1_5 + SHA-256)으로 서명한다.
- RSA 키의 최소 길이는 **2048비트 이상**이다. 이는 [RFC 7518 §3.3](https://www.rfc-editor.org/rfc/rfc7518.html#section-3.3)의 “A key of size 2048 bits or larger MUST be used with these algorithms.” 요구사항에 따른다. `JwtTokenService`는 초기화 시 2048비트 미만의 키를 거절한다.

## Login Session 폐기 호출

- `LoginSessionService`의 로그인·Refresh 후 JWT 발급 실패와 명시적 폐기는 현재 `RedisRefreshSessionStore.revoke()`에 직접 위임한다. 실제 폐기 처리는 이미 저장소에 공통화되어 있으므로, 단순 위임만 하는 내부 메서드는 추가하지 않는다.
- JWT 발급 실패 시에는 아직 `LoginGrant`가 없으므로, `revoke(LoginGrant)`를 호출하기 위해 객체를 만들지 않고 발급된 세션 ID로 폐기한다.
- 향후 서비스 수준의 공통 감사 로그·추가 검증·예외 처리 정책이 필요해지면 세션 ID를 받는 내부 메서드로 통합한다. 이때 로그인 실패·Refresh 실패·명시적 폐기 세 경로를 함께 검토한다.

## 단일 Redis 결정

현재 비용 제약으로 Spring Session과 application auth state는 하나의 Upstash Redis endpoint를 공유한다. env-first, responsibility-second prefix를 사용한다.

```text
kirikick:<environment>:spring:session:*
kirikick:<environment>:auth:*
```

이 논리 분리는 key collision을 방지할 뿐이다. ACL, memory, command quota, eviction, availability, failure domain, access right는 분리하지 않는다. 운영 시 다음을 관찰한다.

- memory와 key count
- command volume
- concurrent temporary/login session 수
- latency와 error rate
- eviction
- 실제 비용

짧은 TTL과 terminal-flow cleanup은 abandonment retention을 제한한다. TTL은 request flood를 막거나 환경을 격리하지 않는다.

## physical 분리 재검토

사용량, 비용 또는 운영 격리 근거가 생기면 dev/prod 분리를 먼저 검토하고 이후 Spring Session·auth responsibility별 분리를 검토할 수 있다. 분리 결정은 다음을 포함한 ADR이 필요하다.

1. 관찰한 usage/cost/operational evidence
2. connection과 namespace 변경 순서
3. 남아 있는 temporary Session·Refresh Session의 migration, natural expiry 또는 forced reauthentication 방안
4. rollback 및 관찰 지표

두 번째 Redis는 현재 provision하지 않는다.

단, PROD 배포 전 보안 경계는 usage 재검토와 별도다. 하나의 endpoint를 유지하면 dev/prod별 distinct credential의 key-prefix ACL과 cross-prefix read/write denial을 증명해야 한다. 이를 증명할 수 없으면 dev/prod Redis를 물리적으로 분리한다. shared Testcontainer는 key namespace 동작만 검증하며 production credential isolation을 증명하지 않는다.

## Session과 cookie

OAuth/가입 temporary session은 HTTPS에서 `__Host-onboarding`, Secure, HttpOnly, Path `/`, Domain 없음, SameSite Lax다. 카카오 top-level callback을 위해 Lax를 쓴다. Refresh와 CSRF cookie는 SameSite Strict다.

`AUTH_CROSS_SITE_COOKIES`의 기본값은 `false`다. `true`는 Vercel 기본 도메인과 Cloud Run `run.app`처럼 서로 다른 사이트에서 수행하는 **DEV 브라우저 검증 전용** opt-in이다. dev 프로필에서만 허용하며, local/prod 프로필 또는 `secure-cookies=false`와 함께 설정하면 기동을 거절한다. opt-in 시 onboarding·Refresh·CSRF 세 쿠키는 모두 `SameSite=None; Secure`가 된다. Origin·CSRF·OAuth state·PKCE·nonce·브라우저 binding·Refresh 재사용 탐지 정책은 변경하지 않는다.

`SameSite=None; Secure`는 브라우저의 third-party-cookie 또는 anti-tracking 정책을 우회하지 않는다. 따라서 실제 브라우저 E2E 검증은 별도 rollout gate이며, 이 opt-in은 운영의 same-site/same-origin 권장을 대체하지 않는다.

서로 다른 FE Origin은 같은 BE cookie jar를 공유할 수 있다. 독립 계정이나 독립 Refresh Session을 보장하지 않으며, 두 FE에서 Refresh를 동시에 요청하면 기존 replay 탐지가 세션을 폐기할 수 있다. 검증은 순차로 수행한다.

운영은 HTTPS same-site FE/API 또는 same-origin reverse proxy를 기본으로 한다. 운영 cross-site cookie 허용은 이번 DEV opt-in 범위가 아니며 별도 security review가 필요하다.

OAuth permission deadline은 10분, signup proof deadline은 5분이다. Spring Session idle timeout은 각각 10분·5분이며 context/CSRF 조회가 permission deadline을 연장하지 않는다. OAuth 시작과 OAuth→proof 전환은 기존 Session ID를 rotate하거나 attribute를 복사하지 않고, slot ID만 전달한 genuinely fresh Session을 만든다. Spring Security의 일반 session-fixation rotation은 이 명시적 lifecycle·binding과 충돌하므로 OAuth 흐름에는 적용하지 않는다. 한 브라우저에는 하나의 in-flight flow만 유지한다.

## R1–R5 경합 변경 요약

세부 fixture·assertion은 테스트 [README](../../api/src/test/README.md)에 둔다. 아래 링크의 과거 이슈/PR은 당시 소스·검증 기록의 근거이며, 현재 실행 결과가 아니다.

| 항목 | 현재 범위 | 근거·상세 |
| --- | --- | --- |
| R1 | **제품 완화:** onboarding serializer는 정확히 빈 문자열(`""`) 쓰기만 Max-Age와 무관하게 억제하고, 비어 있지 않은 `Max-Age=0`은 기본 serializer에 위임한다. 물리 cookie 잔존은 권한이 아니다. | [#48](https://github.com/KIRIKICK/backend/issues/48)·[#49](https://github.com/KIRIKICK/backend/pull/49) |
| R2 | **tests-only:** 같은/다른 계정의 네 cookie-application 모델과 다른 계정의 local relay 전달을 확인한다. 브라우저 동작이나 로그인 승자 정책을 정하지 않는다. | [#55](https://github.com/KIRIKICK/backend/issues/55)·[#56](https://github.com/KIRIKICK/backend/pull/56), [OAuth 성공 응답의 쿠키 적용·전달 순서 선행 검증](../../api/src/test/README.md#oauth-성공-응답의-쿠키-적용전달-순서-선행-검증-55) |
| R3 | **tests-only:** 같은 Refresh의 실제 HTTP 동시 요청에서 재사용 탐지·family 폐기로 200 승자의 successor Refresh·Access도 후속 요청에서 거절됨을 확인한다. 새 OAuth 재로그인 복구도 확인한다. `CookieManager` 모델은 실제 첫 헤더 도착 순서·browser·다중 탭을 증명하지 않는다. 2026-11-13 KST 확인: [#53](https://github.com/KIRIKICK/backend/issues/53)은 OPEN, BE [PR #54](https://github.com/KIRIKICK/backend/pull/54)는 merged이고 공개 FE 구현·배포·일정·browser 완료 근거는 미확인이다. | [이전 댓글](https://github.com/KIRIKICK/backend/issues/53#issuecomment-6790432549), [동일 Refresh 토큰 동시 사용 회귀](../../api/src/test/README.md#동일-refresh-토큰-동시-사용-회귀-53) |
| R4 | **제품 완화:** 가입 grant 반환 뒤 Refresh 발급 호출 시도 전 `RuntimeException`은 삭제 cookie 없이 best-effort 보상한다. `TokenResponse` 반환 이후 직렬화·filter·transport 실패는 catch 밖이며, 호출 시도 후 보상도 best-effort다. | [#50](https://github.com/KIRIKICK/backend/issues/50)·[#51](https://github.com/KIRIKICK/backend/issues/51)·[#52](https://github.com/KIRIKICK/backend/pull/52), [Signup Refresh 발급 시도 전 실패 회귀](../../api/src/test/README.md#signup-refresh-발급-시도-전-실패-회귀-50-51) |
| R5 | **tests-only:** 실제 A/B raw-cookie·masked-body 모델, 교차 거절/미진입/제한적 보존·재취득 복구, 같은 raw의 복수 mask를 확인한다. Refresh는 실제 HTTP이고 signup은 MockMvc이며, Redis 전체·TTL·브라우저·FE를 주장하지 않는다. | [#59](https://github.com/KIRIKICK/backend/issues/59)·[#60](https://github.com/KIRIKICK/backend/pull/60), [초기 CSRF 발급 응답의 쿠키·본문 토큰 검증](../../api/src/test/README.md#초기-csrf-발급-응답의-쿠키본문-토큰-검증-59) |

README와 현재 소스는 BE/client-model 경계를 설명한다. 테스트·머지·CI/CD 성공은 [배포 전 외부 검증](#배포-전-외부-검증)의 각 요구사항을 대체하지 않는다. 외부 검증의 완료 근거 미확인은 실패·위반 또는 승인·면제를 뜻하지 않는다.

### 늦은 onboarding 만료 응답

정상 종료한 OAuth·가입 흐름은 기존 flow/claim 검증에 따라 서버 Session을 무효화한다. 다만 같은 onboarding cookie key의 오래된 삭제 응답이 새 흐름의 쿠키를 지우는 것을 막기 위해, onboarding serializer는 정확히 빈 문자열(`""`)인 쿠키 값 쓰기를 Max-Age와 무관하게 억제한다. 값이 비어 있지 않으면 `Max-Age=0` 쓰기를 포함해 기본 serializer에 위임한다. 이 변경은 서버 Session 삭제·TTL·flow 종료나 Refresh·CSRF cookie 정책을 바꾸지 않는다.

따라서 종료된 Session ID가 브라우저에 물리적으로 남을 수 있다. ID만으로는 Session·가입 proof·인증 권한을 복원할 수 없고, 서버는 없는 Session 또는 binding이 맞지 않는 proof를 거절한다. 새 OAuth 시작은 새 Session/cookie를 발급해 복구한다.

이 완화는 늦은 **삭제** 응답만 다룬다. 정상 cookie 발급 응답 간 순서 역전, Refresh 동시 사용, signup 실패의 Refresh 삭제, 초기 CSRF 응답 경합과 BUSY 정책은 별도 문제다. 혼합 배포 중 이전 인스턴스나 이미 대기 중인 삭제 응답에는 기존 위험이 남고, rollback하면 이 완화도 사라진다.

signup claim은 DB 작업을 수락하는 원자적 경계다. live 5분 lease 동안에는 새 OAuth 시작이 `BUSY`이며 수락된 가입을 취소하지 않는다. lease가 만료되면 final publication은 fail-closed이며 새 시작만 가능하다. DB commit은 취소하지 않는다. commit 뒤 로그인 grant 발급·claim 종료·proof Session 정리·Refresh 쓰기·`TokenResponse` 생성을 수행하는 `SignupController.signup()`의 `try/catch`는 해당 블록에서 발생한 `RuntimeException`만 관찰한다. 반환받은 grant가 있으면 best-effort revoke를 시도하고, 없으면 claim 종료·proof 정리를 best-effort로 시도한 뒤 오류를 전달한다. `TokenResponse` 반환 이후의 직렬화·필터 후처리·전송 실패는 이 catch의 관찰 범위 밖이며, 해당 실패까지 grant 보상한다고 보장하지 않는다. revoke 또는 transport 결과가 불확실하면 TTL-bounded state가 남을 수 있으며, 이 처리는 상태의 즉시 부재·cookie 전달·응답 순서를 보장하지 않는다.

### 가입 Refresh 발급 시도 전 실패

가입에서 grant를 반환받은 뒤 위 블록에서 `writeRefresh` 호출을 시도하기 전에 `RuntimeException`이 발생하면, A 본인 grant의 폐기는 항상 시도하되 Refresh 삭제 쿠키는 보내지 않는다. 따라서 늦은 A 실패 응답이 새 로그인 B나 기존 유효 로그인 C의 쿠키를 지우지 않는다. 다른 계정의 기존 로그인도 유지하며 실패한 가입을 계정 전환 성공으로 취급하지 않는다. 폐기 실패는 원래 오류를 가리지 않고 기존 TTL에 의존하는 잔여 위험을 남긴다.

호출 시도 여부는 쿠키 헤더 추가·commit·클라이언트 수신과 다르다. 위 catch가 관찰한 호출 시도 이후 실패에서는 grant 폐기·Refresh 삭제를 각각 best-effort로 시도하며, `clearRefresh()` 호출만으로 최종 삭제 헤더의 보존·전달·적용을 보장하지 않는다. 따라서 부분 쓰기·불명확한 전달·늦은 삭제 경합은 미검증 잔여 위험이며, OAuth 보상은 변경하지 않는다. 이 변경은 R4의 호출 시도 전 실패만 완화하며 R2·R3·R5나 기존 401 오류 처리를 변경하지 않는다. 이전 버전의 대기 응답·혼합 배포·rollback에는 기존 위험이 남는다.

## Stale OAuth completion recovery

오래된 callback은 request-local marker가 **검증된 flow·claim·wrapped resolved Session ID와 정확히 일치할 때만** stale completion으로 취급한다. 이 좁은 경로는 fixed `303`·`Cache-Control: no-store` 응답을 준비할 수 있지만, outer boundary가 reset/rebuild하기 전에는 아직 commit되지 않았는지, 준비한 status·`Location`·cache header가 정확한지, public old `SessionRepository.findById`가 `null`인지 다시 확인한다. 세 조건이 모두 맞을 때만 fixed response를 재구성하며 `Set-Cookie`는 쓰지 않는다.

일반 예외는 이 경로에서 삼키거나 redirect로 바꾸지 않는다. old-session lookup이 unavailable이면 기존 `AUTH_STATE_UNAVAILABLE` 503 경계로 보낸다. 이 복구는 transport ordering이나 client-side last-click 승자를 보장하지 않는다.

## 배포 전 외부 검증

다음은 구현/CI 완료와 구분해 `passed`, `failed`, `not run — credentials unavailable`으로 기록한다.

- public HTTPS에서 실제 카카오 confidential-client PKCE 및 cookie flow
- 지정 DEV Redis에서 production Refresh Lua rotation/replay smoke
- 단일 endpoint를 유지할 경우 dev/prod prefix ACL cross-prefix denial
- remote CI의 최종 실행 결과

`not run` 또는 `failed`는 DEV/PROD rollout을 막는다. smoke는 별도 권한이 필요하고, unique temporary namespace와 synthetic data만 사용하며 PROD에 연결하지 않는다.

## SignupApplication 호환 bridge

OAuth callback의 exact Session proof 저장 호출 성공 뒤 같은 ID·identity·발급 시각의 가입신청을 DB commit한다. 가입 context/POST는 ID–현재 proof–DB 신청을 대조하며 ID 자체는 권한이 아니다. 완료 DB transaction 반환 후에만 ACTIVE 회원을 조회해 로그인한다. 기존 완료 결과는 409이며 토큰 재발급·POST 자동 retry 없이 새 OAuth로 재진입한다. 이름은 별도 정규화 없이 원문으로 저장·비교한다.

Redis claim/lease·BUSY·최신 flow·stale 처리는 이번 연결에서 유지한다. Session ACK는 최종 Session/쿠키 상태와 같지 않으며 S08 중 R1 늦은 onboarding 삭제 응답 경합은 완화하지만, 나머지 쿠키·Session 경합은 별도 미해결이다. pending/completed 물리 보존 수치와 정리 책임 미확정은 rollout 게이트다. 상세 HTTP·실패 계약은 컨트롤러 선언에서 생성하는 OpenAPI(`/v3/api-docs`)를 기준으로 하며, 실행·접근 방법은 [API 문서 안내](../../README.md#api-문서)를 따른다.

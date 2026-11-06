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

- 연결된 User가 ACTIVE인 기존 OAuth 계정은 서비스 로그인 grant를 발급하고 `AUTHENTICATED`로 고정된 FE callback에 `303` 이동한다.
- 연결된 User가 WITHDRAWN·SUSPENDED·BANNED인 기존 OAuth 계정은 grant나 proof 없이 `OAUTH_FAILED`로 이동한다.
- 신규 subject는 temporary Session에 가입 proof를 저장한 뒤 같은 proof ID로 `SignupApplication`을 발행하고 `SIGNUP_REQUIRED`로 이동한다. 이 callback은 User를 생성하거나 서비스 인증을 발급하지 않는다.
- 성공·실패 redirect에는 `Cache-Control: no-store`를 적용하며, URL에는 code, state, provider token, subject, proof, access/refresh token 또는 임의 return URL을 넣지 않는다.

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

운영은 HTTPS same-site FE/API 또는 same-origin reverse proxy를 기본으로 한다. 운영 cross-site cookie 허용은 이번 DEV opt-in 범위가 아니며 별도 security review가 필요하다.

OAuth permission deadline은 10분, signup proof deadline은 5분이다. Spring Session idle timeout은 각각 10분·5분이며 context/CSRF 조회가 permission deadline을 연장하지 않는다. OAuth 시작과 OAuth→proof 전환은 기존 Session ID를 rotate하거나 attribute를 복사하지 않고, slot ID만 전달한 genuinely fresh Session을 만든다. Spring Security의 일반 session-fixation rotation은 이 명시적 lifecycle·binding과 충돌하므로 OAuth 흐름에는 적용하지 않는다. 한 브라우저에는 하나의 in-flight flow만 유지한다.

signup claim은 DB 작업을 수락하는 원자적 경계다. live 5분 lease 동안에는 새 OAuth 시작이 `BUSY`이며 수락된 가입을 취소하지 않는다. lease가 만료되면 final publication은 fail-closed이며 새 시작만 가능하다. DB commit은 취소하지 않는다. commit 뒤 로그인 Session 생성·claim 종료·proof Session 삭제·cookie/JWT 응답 중 하나라도 실패하면 발급된 grant를 best-effort revoke하고 성공을 publish하지 않는다. revoke 또는 transport 결과가 불확실하면 TTL-bounded residual state가 남을 수 있으나, 문서는 부재·cookie 전달·응답 순서를 보장하지 않는다.

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

Redis claim/lease·BUSY·최신 flow·stale 처리는 이번 연결에서 유지한다. Session ACK는 최종 Session/쿠키 상태와 같지 않으며 S08 늦은 쿠키 삭제 경합은 별도 미해결이다. pending/completed 물리 보존 수치와 정리 책임 미확정은 rollout 게이트다. 상세 HTTP·실패 계약은 컨트롤러 선언에서 생성하는 OpenAPI(`/v3/api-docs`)를 기준으로 하며, 실행·접근 방법은 [API 문서 안내](../../README.md#api-문서)를 따른다.

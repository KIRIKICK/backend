# AUTH 도메인 운영 정책

## 책임 경계

| 상태 | 소유자 | 목적 |
| --- | --- | --- |
| OAuth authorization request·가입 proof payload | Spring Session Redis | OAuth 및 가입 전 임시 브라우저 흐름 |
| OAuth consume marker | 애플리케이션 Redis key | callback의 원자적 한 번 선점 |
| login/Refresh Session | 애플리케이션 Redis key | 서비스 로그인·회전·재사용 탐지 |
| Access JWT | 클라이언트 응답 | RS256 서명; 원문을 Redis Session에 저장하지 않음 |
| User·OAuthIdentity | PostgreSQL | 명시적 가입 뒤 영속 회원 상태 |
| SignupApplication | PostgreSQL | 신규 OAuth callback이 발행하는 가입 대기 신청 |

Spring Session attribute 삭제나 invalidate는 분산 일회용 lock이 아니다. consume marker는 서버 발급 flow ID만 짧게 선점하며 payload를 복제하지 않는다. Spring Session 내부 key를 애플리케이션 lock으로 조작하지 않는다.

서비스 인증은 HttpSession 인증이 아니다. JWT 검증 뒤 Redis login Session의 ACTIVE 소유자와 DB User ACTIVE를 다시 확인한다. provider token, persistent SecurityContext, OAuth authorized client는 callback 뒤 Session·Redis·DB·응답·로그에 보관하지 않는다.

## 카카오 OAuth callback

카카오는 confidential client로 `client_secret_post`, `openid`, authorization-code flow와 PKCE(S256)를 사용한다. Spring Security가 authorization code 교환, `state`, `nonce`, OIDC 서명·issuer·audience 검증을 담당한다.

- 연결된 User가 ACTIVE인 기존 OAuth 계정은 서비스 로그인 grant를 발급하고 `AUTHENTICATED`로 고정된 FE callback에 `303` 이동한다.
- 연결된 User가 WITHDRAWN·SUSPENDED·BANNED인 기존 OAuth 계정은 grant나 proof 없이 `OAUTH_FAILED`로 이동한다.
- 신규 subject는 temporary Session에 가입 proof를 저장한 뒤 같은 proof ID로 `SignupApplication`을 발행하고 `SIGNUP_REQUIRED`로 이동한다. 이 callback은 User를 생성하거나 서비스 인증을 발급하지 않는다. 가입 context와 명시적 가입 API는 후속 PR 범위다.
- 성공·실패 redirect에는 `Cache-Control: no-store`를 적용하며, URL에는 code, state, provider token, subject, proof, access/refresh token 또는 임의 return URL을 넣지 않는다.

OAuth/가입 temporary session은 HTTPS에서 `__Host-onboarding`, Secure, HttpOnly, Path `/`, Domain 없음, SameSite Lax다. 카카오 top-level callback 때문에 Lax를 사용한다. Refresh와 CSRF cookie는 SameSite Strict다.

## Redis 경계와 배포 전 검증

현재 Spring Session과 application auth state는 한 Redis endpoint에서 namespace로만 구분한다.

```text
kirikick:<environment>:spring:session:*
kirikick:<environment>:auth:*
```

namespace는 key collision만 막는다. 단일 endpoint를 유지하는 PROD 배포 전에는 dev/prod별 distinct credential의 prefix ACL과 cross-prefix read/write denial을 증명해야 한다. 증명할 수 없으면 dev/prod Redis를 물리적으로 분리한다.

다음은 구현·로컬 검증과 별도 rollout gate다. `not run` 또는 `failed`는 DEV/PROD rollout을 막는다.

- public HTTPS에서 실제 카카오 confidential-client PKCE 및 cookie flow
- 지정 DEV Redis에서 Refresh rotation/replay smoke
- dev/prod Redis prefix ACL cross-prefix denial
- remote CI의 최종 실행 결과

smoke는 별도 권한으로 unique temporary namespace와 synthetic data만 사용하며 PROD에 연결하지 않는다.

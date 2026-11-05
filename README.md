# Backend

Gradle 멀티모듈 프로젝트입니다. 실행 모듈은 `api`이고, `test-support`는 테스트 전용 공통 라이브러리입니다.

```text
backend/
├── settings.gradle       # 포함할 모듈 선언
├── build.gradle          # 플러그인 버전, 공통 group/version/repositories
├── gradlew
├── gradle/
├── compose.yaml          # 로컬 개발용 PostgreSQL/Redis
├── api/
│   ├── build.gradle      # API 의존성, Java 21, 테스트/실행 설정
│   └── src/
│       ├── main/
│       └── test/
└── test-support/         # Testcontainers 등 테스트 전용 공통 지원
```

## 도메인 문서

도메인별 구현 정책은 `docs/domain/`에서 관리합니다.

- [USER: 회원 프로필](docs/domain/user.md)
- [AUTH: 인증 상태·Redis 운영 정책](docs/domain/auth.md)

## 실행과 검증

`backend` 디렉터리에서 실행합니다.

```sh
./gradlew projects         # 현재 모듈: :api, :test-support
./gradlew clean build      # 전체 모듈 빌드/테스트
./gradlew test             # 전체 모듈 테스트
./gradlew :api:test        # API 테스트만 실행
./gradlew :api:bootRun      # API 실행 (기본 local 프로필)
./gradlew :api:bootJar     # 실행 가능한 API JAR 생성
```

- API JAR: `api/build/libs/api-0.0.1-SNAPSHOT.jar`
- API 테스트 보고서: `api/build/reports/tests/test/index.html`
- [JPA 테스트 설정](api/src/test/README.md)

`bootRun`은 기존처럼 루트의 `compose.yaml`을 찾도록 작업 디렉터리를 `backend`로 유지합니다.
IDE에서 애플리케이션을 직접 실행할 때도 작업 디렉터리를 `backend`로 지정하세요.
`bootRun`의 local Docker Compose와 PostgreSQL Testcontainers는 별도입니다. PostgreSQL 통합
테스트는 Docker가 필요하지만 외부 DB URL이나 `.env` 값을 사용하지 않습니다. 자세한 실행 방법은
[JPA 테스트 설정](api/src/test/README.md)을 참고하세요.

## API 문서

애플리케이션 실행 후 `local`·`dev` 프로필에서만 문서를 제공합니다. `prod` 및 그 외 프로필에서는 문서 생성과 접근을 차단합니다.

- Swagger UI: `/swagger-ui.html`
- OpenAPI JSON: `/v3/api-docs`

Refresh API의 Origin·CSRF·쿠키 검증은 Swagger에서도 동일하게 적용됩니다. 브라우저가 Origin과 HttpOnly 쿠키를 관리하므로, Swagger 입력란만으로 인증 흐름을 재현하거나 검증을 우회할 수 없습니다.

## 실행 프로필과 DB

OAuth·Spring Session·SignupApplication 연결 설정은 [.env.example](.env.example)을 참고합니다. 실제 secret은 커밋하지 않습니다. 가입신청 보존·정리와 실제 카카오/HTTPS 등 미검증 rollout 게이트는 AUTH 문서에 구분합니다.

| 프로필 | 연결 대상 | 설정 |
| --- | --- | --- |
| `local` | 로컬 Compose PostgreSQL·Redis | Spring Boot의 서비스 연결 정보로 임의 매핑 포트·계정을 자동 적용 |
| `dev` | Supabase의 `dev` database | `.env` 또는 환경변수로 DB·Redis 연결값 필수 주입 |
| `prod` | Supabase의 `prod` database | 환경변수·Secret Manager로 DB·Redis 연결값 필수 주입; `.env` 자동 로딩 안 함 |

프로필을 지정하지 않으면 `local`을 사용합니다. Cloud Run 서비스마다 `SPRING_PROFILES_ACTIVE=dev` 또는 `prod`를 명시합니다.
IDE 로컬 실행은 작업 디렉터리를 `backend`로 설정하면 됩니다.
`local`은 `bootRun`의 개발 전용 Docker Compose 지원을 사용하며, 배포 JAR에서는 Compose를 실행하지 않습니다.

`local`과 `dev`에서만 실행 디렉터리의 `.env`를 `optional:file:./.env[.properties]`로 읽습니다.
파일이 없어도 되지만 `dev`의 DB 연결값(`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`)과 Redis 연결값은 반드시 있어야 합니다. `backend/.env`는 Git에서 제외하며,
Java properties 형식으로 `DB_PASSWORD=값`처럼 작성합니다. 값을 감싸는 따옴표나 `export`는 사용하지 않습니다.
실제 환경변수는 `.env`보다 우선합니다. `prod`는 로컬 dev용 `.env`를 읽지 않으므로 배포 설정에서 운영 DB를 지정합니다.

로컬에서 `dev`로 실행할 때는 `.env`의 기존 `DB_PASSWORD`와 함께 다음 연결값을 사용합니다.

```properties
DB_URL=jdbc:postgresql://aws-0-ap-northeast-2.pooler.supabase.com:5432/dev?sslmode=require
DB_USERNAME=postgres.nvxrstbgnzlcgnlldbvd
```

```sh
./gradlew :api:bootRun --args="--spring.profiles.active=dev"
```

URL은 `jdbc:postgresql://...` 형식과 TLS 설정을 포함합니다. `sslmode=require`는 전송을 암호화하며,
서버 인증서 검증까지 필요하면 CA 설정과 함께 `verify-full`을 사용합니다. URL의 마지막 경로가 실제 DB를 결정하며,
프로필 이름이 URL의 DB를 자동으로 바꾸지는 않습니다. 설정만으로 database가 생성되지도 않습니다.
같은 Supabase 프로젝트의 dev/prod DB는 리소스·권한·장애까지 격리되지 않으므로 필요 시 별도 프로젝트로 분리합니다.

Hikari 풀 기본값은 인스턴스당 최대 `5`, 최소 유휴 `1`입니다.
`DB_POOL_MAX_SIZE`, `DB_POOL_MIN_IDLE`로 조정하며, Cloud Run의 dev/prod 최대 인스턴스 수와 Supabase 연결 한도를 함께 고려합니다.
이 값은 초기 기본값이며 부하 측정으로 조정해야 합니다.

### Redis 연결

`local`은 계속 Compose Redis를 사용합니다. `dev`·`prod`는 Spring Data Redis의 네이티브 연결로 Upstash를 사용하며 TLS를 활성화합니다.
`REDIS_HOST`, `REDIS_PASSWORD`는 필수이며, `REDIS_PORT`는 `6379`, `REDIS_USERNAME`은 `default`가 기본값입니다.
`dev`는 `.env`, `prod`는 환경변수·Secret Manager로 연결값을 주입합니다.

`UPSTASH_REDIS_REST_URL`, `UPSTASH_REDIS_REST_TOKEN`은 HTTP REST용이며 Spring Data Redis에는 사용하지 않습니다.
`REDIS_PASSWORD`에는 REST 토큰이 아닌 Redis 네이티브 비밀번호를 설정합니다.
공유 DB의 키는 실제 사용 코드를 추가할 때 `dev:`, `prod:`로 구분해야 합니다. 연결 설정만으로 모든 Redis 키에 접두어가 자동 적용되지는 않습니다.

### Health probe

- `/actuator/health/liveness`: 애플리케이션 생존 상태만 확인; DB·Redis 장애는 포함하지 않음
- `/actuator/health/readiness`: 요청 수신 준비 상태와 DB·Redis 연결 확인
- 웹 노출은 `health`만 허용하고, 상세정보는 공개하지 않음
- Cloud Run 서비스의 probe 경로 연결은 배포 설정에서 별도로 지정

Hibernate는 `validate`만 수행하고 Flyway `clean`은 금지합니다. 테이블 변경은 migration으로 관리합니다.
연관관계 지연 로딩의 공통 완화책으로 `hibernate.default_batch_fetch_size=100`을 적용합니다. N+1 해결을 보장하는 설정은 아니며, 조회별 fetch join·EntityGraph·DTO projection과 쿼리 수 검증으로 필요한 관계를 제어합니다.
H2는 테스트 의존성으로만 유지하여 배포 앱의 설정 누락이 메모리 DB로 대체되지 않도록 했습니다.
전체 컨텍스트 테스트는 별도 `test` 프로필을 사용하며, JPA 슬라이스 테스트의 H2·폐기용 PostgreSQL 설정은 그대로 유지합니다.

## 코드 포맷

Gradle 자체를 JDK 21로 실행합니다. Spotless와 Google Java Format으로
모든 모듈의 `**/src/**/*.java`를 검사합니다. 테스트·fixture도 포함하며
`build`, `.gradle`, `bin`, `generated` 디렉터리의 생성물은 제외합니다.

```sh
./gradlew spotlessApply # 포맷 수정
./gradlew spotlessCheck # 포맷 검사
```

Java 모듈의 `check`에 연결되어 `build`·`:api:build`에서도 전체 Java 포맷을 검사합니다.

## 아키텍처 검사

`architecture-tests/src/test/java`의 공통 규칙과 테스트를 각 Java 모듈의 `test` 소스에 자동 추가합니다.
모듈마다 자신의 `main` 클래스 출력 디렉터리만 검사하며, 다른 모듈·외부 라이브러리·테스트 클래스는 검사 대상에 포함하지 않습니다.
운영 클래스가 없는 모듈은 아키텍처 검사만 건너뛰며, 없는 레이어의 생성을 강제하지 않습니다.

규칙 자체의 회귀 테스트와 fixture는 `architecture-tests/src/regressionTest/java`에 둡니다.
현재는 기존 Spring/JPA 테스트 의존성을 재사용하도록 `:api:test`에서 한 번 실행합니다.
다른 모듈의 공통 검사에는 JUnit·ArchUnit만 필요하며 Spring/JPA/Querydsl 라이브러리를 추가하지 않습니다.
`architecture-tests`는 공유 소스 디렉터리이지 별도의 Gradle 모듈이 아닙니다.

```sh
./gradlew test # 모든 모듈의 일반 테스트와 공통 아키텍처 검사
./gradlew :api:test --tests 'org.kirikick.architecture.*'
```

검사 경로는 Gradle이 `architecture.classes.dirs` 시스템 속성으로 전달하므로 IDE에서도 Gradle로 테스트를 실행합니다.
세부 규칙은 [테스트 문서](api/src/test/README.md#아키텍처-규칙)를 참고합니다.

## 비밀정보 검사

CI의 `gitleaks` job은 전체 Git 이력을 기본 내장 규칙으로 검사하며, 탐지 시 실패합니다.
공식 CLI Docker 이미지를 버전·digest로 고정해 사용하므로 별도의 Gitleaks 라이선스 키는 필요하지 않습니다.
탐지 로그의 비밀값은 `--redact`로 가립니다. 광범위한 allowlist나 baseline은 사용하지 않습니다.

Docker로 로컬 커밋 이력을 검사할 수도 있습니다.

```sh
docker run --rm --user "$(id -u):$(id -g)" \
  --volume "$PWD:/repo:ro" \
  ghcr.io/gitleaks/gitleaks:v8.30.1 \
  git --redact --no-banner --log-opts="--all" /repo
```

이 명령은 커밋 이력을 검사하므로 아직 커밋하지 않은 변경은 포함하지 않습니다.
실제 비밀정보가 발견되면 코드 삭제만으로 해결하지 말고 해당 자격증명을 폐기·재발급합니다.

## Git hooks (macOS / Windows)

커밋 전에는 staged 변경의 Gitleaks 검사와 `spotlessCheck`, push 전에는 `test`를 실행합니다.
포맷 검사는 현재 작업 디렉터리의 전체 Java 소스가 대상이며, 파일을 자동 수정하거나 스테이징하지 않습니다.
PostgreSQL 테스트는 Docker의 Testcontainers로 로컬과 CI에서 같은 방식으로 실행합니다. 외부 DB URL이나
환경변수를 사용하지 않으며, Docker를 사용할 수 없으면 PostgreSQL 테스트는 건너뛰지 않고 실패합니다.

Git·JDK 21·native Gitleaks가 필요합니다. 로컬 Gitleaks는 CI와 같은 v8.30.1을 권장합니다.
macOS는 `brew install gitleaks`로 설치할 수 있습니다.
Windows는 [공식 릴리스](https://github.com/gitleaks/gitleaks/releases/tag/v8.30.1)의 Windows 바이너리를 설치하고
`gitleaks.exe`를 PATH에 추가합니다. 로컬 훅에서는 Docker를 사용하지 않습니다.

Windows CMD·PowerShell·IDE에서 Git for Windows로 commit/push해도 같은 훅이 동작합니다.
Git for Windows가 제공하는 셸을 사용하므로 Git Bash 터미널을 직접 열 필요는 없습니다.
단, Git을 실행한 프로세스의 PATH에서 `gitleaks`와 `java`를 찾을 수 있어야 합니다.

각 clone의 `backend` 루트에서 한 번 활성화합니다. 아래 명령은 모든 터미널에서 같습니다.

```sh
git config --local core.hooksPath .githooks
```

Gitleaks가 없거나 검사가 실패하면 commit/push를 중단합니다. 훅은 도구를 자동 설치하지 않습니다.
훅은 우회할 수 있으므로 CI 검사도 유지합니다.
훅 회귀 테스트는 `python3 scripts/test_git_hooks.py`로 실행하며 Windows에서는 `python`을 사용합니다.
CI에서는 기존 `ubuntu-latest` 빌드 잡에서 같은 회귀 테스트를 실행합니다.
Gitleaks·Gradle은 스텁으로 대체하므로 실제 검사를 중복 실행하지 않습니다.

`.gitattributes`는 일반 텍스트를 LF로 통일하고 `.bat`·`.cmd`만 CRLF로 체크아웃합니다.
훅·Gradle 셸 스크립트는 LF를 유지하며 JAR 바이너리는 변환하지 않습니다.
전역 `core.autocrlf` 설정과 무관하게 같은 정책을 적용하며, 기존 작업 파일 전체를 강제 재작성하지 않습니다.

## 모듈을 추가할 때

1. `backend/<모듈명>/build.gradle`과 소스 디렉터리를 만듭니다.
2. 루트 `settings.gradle`에 `include '<모듈명>'`을 추가합니다.
3. 모듈 역할에 맞는 플러그인과 필요한 의존성만 선언합니다.

단일 프로세스 안에서 동작하는 관리자 기능 등은 `java-library` 모듈로 두고,
`api`에서 `implementation project(':<모듈명>')`으로 연결하면 함께 빌드·배포됩니다.
Spring 기반 라이브러리 모듈은 설정 진입점을 제공하고 API에서 명시적으로 `@Import`합니다.
`org.kirikick.api` 밖의 빈·Entity·Repository는 자동 스캔되지 않으므로 모듈 설정에서 필요한 스캔/등록을 지정합니다.
미디어 워커를 별도 프로세스로 실행해야 할 때는 해당 모듈에 Spring Boot 플러그인을 적용합니다.

루트가 모든 모듈에 Spring Boot나 API 의존성을 강제하지 않습니다.
모듈 간 순환 의존성을 피하고, 공통 모듈은 실제 공유할 코드가 생겼을 때만 추가합니다.

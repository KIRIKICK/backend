# 백엔드 개발·협업 가이드

프로젝트 실행 환경과 설정 방법은 [README](README.md)를 참고합니다.

사람과 AI 에이전트가 같은 기준을 사용합니다. 개인 `AGENTS.md`는 이 문서를 참조하되 개인·에이전트
전용 지침을 별도로 관리할 수 있습니다. 현재 `AGENTS.md`는 Git에서 무시되므로 다른 환경에 자동으로
전달되거나 적용된다고 가정하지 않습니다.

## 1. 작업 흐름

기본 흐름은 **이슈 → 작업 브랜치 → PR → 리뷰·검증 → main 머지**입니다.

- 문서나 작은 수정도 이슈를 만들고 PR로 반영합니다. 작은 수정·긴급 수정에 별도 예외를 두지 않습니다.
- 이슈에는 변경 목적·범위·완료 기준을 적고, PR은 하나의 설명 가능한 목적을 중심으로 작성합니다.
- 범위 밖 개선은 현재 작업과의 관련성과 위험을 보고 별도로 다룹니다.
- 다른 작업자의 변경을 임의로 수정·되돌리거나 스테이징하지 않습니다.
- 팀 운영 기준상 `main` 직접 push와 force push는 허용하지 않습니다.
- 보안 취약점이나 비밀정보 노출이 의심되면 민감한 상세를 공개 이슈·PR·로그에 기록하지 않습니다.
  백엔드 리드와 담당자·비공개 공유 경로를 먼저 확인하고, 공개 이슈·PR에는 안전한 범위만 기록합니다.

기존 [기능 이슈](.github/ISSUE_TEMPLATE/feature.md), [버그 이슈](.github/ISSUE_TEMPLATE/bug.md), [PR 템플릿](.github/pull_request_template.md)을 사용합니다.

## 2. 브랜치와 커밋

- 브랜치: `prefix/이슈번호-브랜치명`
  - `feat`: 기능 개발
  - `fix`: 버그 수정
  - 예: `fix/44-fe-origin-validation`
- 커밋: `prefix: 메시지 (#이슈번호)`
  - `feat`: 기능 추가
  - `fix`: 버그 수정
  - `refactor`: 동작 변경 없는 구조 개선
  - `docs`: 문서 수정 (README, 주석 등)
  - `test`: 테스트 추가·수정
  - `ci`: CI 설정·자동화 변경
  - `chore`: 그 외 유지보수
  - 예: `fix: FE Origin 검증 기준을 정책으로 통일 (#44)`

작업 성격을 더 잘 나타내는 다른 prefix도 사용할 수 있으며, 브랜치 prefix와 커밋 prefix가 같을 필요는 없습니다.  
merge·revert 등 자동 생성 커밋의 메시지는 이슈 추적 가능성을 고려해 상황에 맞게 처리합니다.  
현재 브랜치·커밋 형식을 검사하는 자동화는 없습니다. 

## 3. 코드 작성과 설계

- Java 포맷은 [코드 포맷](#코드-포맷)을 따릅니다.
- 반복적인 logger·단순 의존성 주입 생성자에는 Lombok을 우선합니다. 검증·변환·초기화 로직이나 공개
  API가 달라지는 생성자는 기계적으로 대체하지 않으며, `@Data` 같은 어노테이션을 무분별하게 쓰지 않습니다.
- 타입·정적 멤버는 import 후 단순 이름 사용을 기본으로 합니다. package·import 선언과 문자열은
  대상이 아니며, 이름 충돌 등 해결하기 어려운 경우는 별도로 판단합니다.
- 요청은 Spring 메시지 컨버터의 DTO 바인딩과 Bean Validation을 우선합니다. 기본 기능으로 다루기
  어려운 경우 적절한 변환기를 사용하되 기존 입력·오류 응답 계약을 유지합니다.
- 엔티티와 DB 저장값 사이의 변환은 JPA `AttributeConverter` 등 적절한 변환 책임에 두고,
  도메인 불변식은 엔티티·도메인에 둡니다. 모든 변환을 한 방식으로 일괄 강제하지 않습니다.
- 기존 모듈·계층의 책임과 의존 방향을 지킵니다. 같은 검증·정규화·정책은 기존 책임을 재사용하거나
  적절한 소유 위치로 모을 수 있는지 검토하되, 중복 제거만을 위해 한 클래스에 몰거나 추상화하지 않습니다.
- 쿼리·연관관계 변경 시 N+1과 불필요한 조회를 확인하고, 필요하면 쿼리 수·실행 계획을 검증합니다.
  실제 쿼리를 실행하는 `EXPLAIN ANALYZE` 등은 운영 데이터에 대한 부작용과 부하를 고려합니다.
- 시점은 `Instant`, API 시각은 UTC ISO 8601을 기본으로 하며 정책상 날짜와 구분합니다. 기본 타임존에
  암묵적으로 의존하지 않고, 날짜 경계의 시간대는 도메인 정책에 따릅니다. 특정 시간대를 일괄 적용하지 않습니다.

### 코드 포맷

Gradle 자체를 JDK 21로 실행합니다. Spotless와 Google Java Format으로
모든 모듈의 `**/src/**/*.java`를 검사합니다. 테스트·fixture도 포함하며
`build`, `.gradle`, `bin`, `generated` 디렉터리의 생성물은 제외합니다.

```sh
./gradlew spotlessApply # 포맷 수정
./gradlew spotlessCheck # 포맷 검사
```

Java 모듈의 `check`에 연결되어 `build`·`:api:build`에서도 전체 Java 포맷을 검사합니다.

## 4. 테스트와 검증

### 팀의 개발 기준

- 구현 세부사항보다 관찰 가능한 동작과 계약을 검증하고, 성공·실패·경계 조건을 다룹니다.
- 단위 테스트를 우선하되 저장소·트랜잭션·필터 체인·동시성처럼 실제 연결이 중요한 경우 통합 테스트를
  사용합니다. Mock은 필요한 외부 경계에 사용하고 검증 대상의 핵심 동작을 대체하지 않습니다.
- 테스트는 실행 순서·현재 시각·다른 테스트 데이터에 의존하지 않게 합니다. 필요한 경우 고정 `Clock`을
  사용하고 공유 저장소의 데이터는 테스트별로 격리합니다.
- PostgreSQL 고유 동작을 H2 테스트만으로 검증했다고 판단하지 않습니다. 기존 격리 테스트 환경을
  사용하며 개인 `.env`·키·실제 외부 DB·Redis를 검증에 사용하지 않습니다.
- 문서 전용 변경을 제외하면 로컬 SpotBugs를 실행하고 로그·보고서를 확인합니다. 경고와 분석 오류를
  구분하고, 근거 없이 억제하지 않습니다. 경고 0건은 결함이 없다는 뜻이 아닙니다.
- 전체 build 실행 범위와 설정·스크립트 변경 등 문서 전용 여부의 경계 사례는 변경 내용과 위험에 따라
  판단합니다. 기본 기준과 다르게 처리했다면 PR에 근거를 공유합니다.
- 실행하지 못한 검증은 이유·미검증 범위·남은 위험을 PR에 기록하며, 완료로 표시하지 않습니다.
- API의 요청·응답·오류 코드·인증 조건을 바꾸면 [OpenAPI](README.md#api-문서)와 실제 동작의 정합성을
  확인합니다. 문서는 springdoc가 컨트롤러·DTO 선언과 애너테이션에서 생성하므로 필요한 선언·설명과
  관련 문서 테스트를 함께 갱신하고, 문서 영향이 없으면 PR에 해당 없음과 이유를 적습니다.

명령·환경 준비는 [실행과 검증](README.md#실행과-검증), [테스트 안내](api/src/test/README.md),
[SpotBugs 실행·결과 해석](#spotbugs-pr-advisory)을 따릅니다.

### 현재 자동화된 검사

- [CI](.github/workflows/ci.yml)는 main 대상 push·PR에서 Gradle build·테스트, Spotless,
  아키텍처 검사, Gitleaks, workflow·스크립트 계약 검사를 실행하며 실패를 전파합니다.
- [SpotBugs PR advisory](#spotbugs-pr-advisory)는 main 대상 PR에서 별도로 실행하며, 상세 정책은 아래를 따릅니다.
- 로컬 Git hooks의 설치와 동작은 아래 [Git hooks 안내](#git-hooks-macos--windows)를 따릅니다.
- 브랜치·커밋 형식, Lombok·import 세부 스타일은 현재 자동 검사로 강제하지 않습니다.

로컬 실행 기준과 GitHub의 필수 병합 조건은 별개입니다. workflow가 있다는 이유만으로 required check로
판단하지 않으며, 자동 강제 범위는 실제 저장소 설정으로 확인합니다.

### 아키텍처 검사

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

### SpotBugs PR advisory

`SpotBugs Advisory`는 main 대상 PR에서 제품 CI와 별도로 실행한다. `spotbugs-main-advisory`는
필수 체크로 등록하지 않는다. 실패한 optional check를 유지하는 정책이며, 경고나 분석 오류를 성공으로
숨기는 정책이 아니다. 기존 `CI`와 그 성공한 main-push 실행만 소비하는 `CD`는 변경하지 않는다.
기존 CI의 workflow 문법 검사 실패는 이 advisory의 분석 실패와 별개다.

#### 로컬 실행과 분석 범위

backend 저장소 루트에서 실행한다. 앱·제품 테스트·DB·Redis를 실행하거나 `.env`·키를 준비할 필요는 없다.

```sh
./gradlew spotbugsMain --continue --no-daemon
python3 scripts/check_spotbugs_report.py
python3 scripts/test_check_spotbugs_report.py
```

현재는 `api/main`만 분석한다. 루트 명령은 `spotbugsMain` task가 있는 하위 프로젝트를 선택하므로,
서브모듈 추가만으로 분석되지 않는다. task 선택과 실제 실행도 다르다(`NO-SOURCE`, disabled, `onlyIf`).
`--continue`는 실패 후 가능한 독립 task를 계속 시도하되 최종 실패는 유지한다.
`runOnCheck=false`, `ignoreFailures=false`를 유지하며 일반 `build/check`에는 분석을 연결하지 않는다.

새 모듈에 task를 도입하는 PR에서는 실제 graph·실행, HTML/XML pair, 분석기·보조 classpath,
`runOnCheck=false`, `ignoreFailures=false`, 추가 시간·메모리·공개 범위와 전체 advisory 실패 영향을
함께 검토한다. 현재 task 하나 때문에 공용 inventory나 일괄 플러그인 적용은 추가하지 않는다.

#### 결과 해석과 검토 책임

- PR 작성자는 매 실행의 analyzer/guard/upload outcome과 로그·보고서를 확인하고, 백엔드 리뷰어가
  결과와 후속 처리를 검토한다. 새 경고는 실제 결함·의도된 동작/오탐·추가 조사 필요로 사람이 분류하고
  PR에 근거 또는 후속 이슈를 남긴다. 분석 오류·보고서 누락을 경고 0건으로 보고하지 않는다.
- guard 종료 코드는 `0=필수 보고서 근거상 발견 없음`, `1=발견 사항 있음`, `2=분석 불완전`이다.
  API pair는 필수이고, 재귀적으로 발견한 각 모듈 pair의 파일·XML·입력·오류·missing class를 확인한다.
  하나의 모듈이라도 문제가 있으면 실패한다. 분석 실패 후에도 취소되지 않았다면 guard·업로드·summary를 시도한다.
- guard가 성공해도 analyzer가 실패했다면 check는 실패 상태다. 구분 근거가 부족하면 수동 검토한다.
  Gradle 종료 코드나 XML `total_classes` 하나만으로 완전성을 판단하지 않는다. HTML/XML 동시 출력에서는
  XML 클래스 통계가 0인 사례가 있어 실제 입력·실행 로그·HTML/XML을 함께 확인한다.
- 경고 0건에는 검토 후 억제한 항목이 포함될 수 있으며 결함 없음이나 보안 안전을 뜻하지 않는다.
- 보고서가 전혀 없는 미래 모듈은 파일 검색만으로 탐지할 수 없다. 신규 모듈 도입 PR에서 검증하고,
  필요성이 확인되면 expected-task inventory를 별도로 검토한다. 서로 독립인 분석이 늘면 시간·메모리도 늘 수 있다.

#### 공개·보관·fork 정책

공개 저장소의 로그·summary와 HTML/XML에 클래스명·메서드·경로·결함 의심 정보가 노출될 수 있음을
전제로 운영한다. artifact 다운로드는 GitHub 로그인과 저장소 읽기 접근이 필요하며 익명 다운로드를 보장하지 않는다.
artifact에는 `**/build/reports/spotbugs/main.html`, `**/build/reports/spotbugs/main.xml`만 포함하고
workspace·소스 사본·환경파일·키·캐시·로컬 문서는 업로드하지 않는다.
artifact 보관은 7일이며 저장소·조직·enterprise 정책 상한을 확인한다. 로그 보관은 별도 Actions 정책을 따른다.

실행은 secrets 없는 `pull_request`, `contents: read`, checkout 자격 증명 미보존, 읽기 전용 Gradle 캐시로
제한한다. fork PR은 승인 정책에 따라 대기할 수 있으며 실제 실행·업로드 가능 여부는 hosted run에서 확인한다.
이를 위해 `pull_request_target`, secrets, OIDC 또는 쓰기 권한을 추가하지 않는다.
PR별 concurrency로 이전 실행을 취소하며 취소된 실행에서는 후속 artifact·summary가 없을 수 있다.

#### 최초 PR에서 남은 검증

- 실제 check 이름·App·SHA와 branch protection/ruleset의 required checks를 확인한다. 별도 workflow라는
  이유만으로 비차단이라고 단정하지 않는다. 자연 실패가 없다면 같은 job에 임시 `exit 1`을 넣어
  실패 check가 필수가 아닌지, merge UI의 다른 차단 사유와 분리해 확인한다. 실패 상태로 merge/bypass하지
  않고 임시 코드를 제거한 최종 SHA를 재실행한다. 이 검증은 분석 오류 분류 테스트를 대체하지 않는다.
- 기존 CI conclusion과 CI 기반 CD에 영향이 없는지, 실제 PR 업데이트 취소와 fork 승인·업로드를 확인한다.
  확인하지 못한 항목은 미검증으로 남긴다.
- artifact의 모듈 경로·파일 목록·접근·실제 만료, runner/JDK/Gradle/엔진 버전과 실행 시간·캐시 효과를 기록한다.
  읽기 전용 캐시가 중복 컴파일을 없앤다고 가정하지 않는다. 비용이나 잡음이 크면 수동·정기 실행을 재검토한다.
- 공개·보관·검토 책임을 유지할 수 없거나 check가 필수가 됨, CI/CD 결합, 권한 확대 또는 불완전 분석의
  성공 처리가 필요해지면 활성화를 중단하고 정책을 다시 검토한다.

### 비밀정보 검사

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

### Git hooks (macOS / Windows)

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

## 5. PR·리뷰와 머지

PR에는 변경 이유·관련 이슈, 주요 설계 판단·변경 영향, 실행한 검증의 명령·결과, 미실행 검증·남은 위험을
기록합니다. API·DB·배포·보안 영향도 해당되는 경우 적습니다. 동시성·N+1·성능·문서 항목이 해당하지
않으면 해당 없음과 이유를 기록합니다. 민감한 값이나 비공개 로컬 문서를 공유하지 않습니다.

- 일반 CI가 실패한 PR은 원인을 확인하고 필요한 수정·재검증을 마친 뒤 머지합니다. 해결이 어려운 예외는
  안전한 범위의 영향·후속 조치를 PR에 기록하고 팀에서 판단합니다. 이는 수동 운영 기준이며,
  별도 SpotBugs advisory와 구분합니다.
- CodeRabbit 지적은 현재 코드·요구사항을 기준으로 검토합니다. 유효한 지적은 반영하고, 미반영하거나
  후속 처리하는 항목은 이유·계획을 PR에 남깁니다. 모든 제안을 무조건 수용하지 않습니다.
- 가능하면 CodeRabbit 리뷰를 확인·처리한 뒤 사람에게 리뷰를 요청합니다. 지연되거나 실행되지 않으면
  상태를 남기고 필요에 따라 먼저 사람 리뷰를 요청할 수 있습니다. 완료를 새 필수 병합 조건으로 삼지 않습니다.
- 변경의 위험도와 관련 영역을 고려해 리뷰를 요청합니다. 리뷰에서는 요구사항, 책임·의존 방향,
  실패 처리와 테스트 근거를 확인하고, 사람 리뷰 의견에도 처리 결과·미반영 사유·후속 계획을 공유합니다.
- 팀은 `Create a merge commit` 방식으로 머지합니다. 필수 승인 0개가 리뷰를 생략한다는 의미는 아닙니다.

작성 시 확인한 실제 설정과 팀 운영 기준은 다음처럼 구분합니다. 설정이 바뀌면 함께 갱신합니다.

- main의 활성 ruleset은 PR을 요구하고, 필수 승인 수는 0개이며 리뷰 대화 해결을 요구합니다.
  force push를 제한하고, 현재 required status check 규칙과 bypass 대상은 없습니다.
- 저장소 설정은 merge commit·squash·rebase를 모두 허용합니다. 그중 merge commit을 사용하는 것은
  팀의 운영 기준이며, 나머지 방식이 시스템에서 차단되어 있다는 뜻은 아닙니다.

배포 영향과 절차는 [DEV Cloud Run CD 안내](docs/deployment/cloud-run-cd.md)를 참고합니다.

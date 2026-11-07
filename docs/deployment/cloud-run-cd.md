# DEV Cloud Run CD

GitHub Actions는 `main`에 merge되어 push된 커밋의 CI가 성공한 경우에만 별도 CD workflow를 시작해 기존 DEV Cloud Run 서비스에 배포합니다. CI는 PR과 `main` push에서 gitleaks·빌드를 수행하며, CD는 CI 성공 결과만 소비합니다. 이 문서는 **기존 리소스를 사용하는 DEV CD의 준비·관찰·복구 절차**를 다룹니다. PROD 승격과 리소스 생성은 범위에 포함하지 않습니다.

> 기준 값 `kirikick` / `asia-northeast3` / `kirikick-api`는 기존 수동 배포에서 사용한 값입니다. 실행 전에 실제 DEV 프로젝트·리전·서비스를 다시 확인하세요.

## 배포 흐름

1. PR 또는 `main` push에서 CI가 gitleaks와 빌드를 수행합니다.
2. 별도 CD workflow는 `workflow_run` 이벤트가 가리키는 CI run이 성공했고, 그 run이 `main`의 `push`에서 시작됐으며 같은 저장소에서 발생했을 때만 실행합니다. PR·fork run과 CI artifact는 소비하지 않습니다.
3. CD는 `workflow_run.head_sha`를 `SOURCE_SHA`로 고정해 명시적으로 checkout합니다. `workflow_run` 자체의 `GITHUB_SHA`는 기본 브랜치 최신 SHA일 수 있으므로 배포 source로 사용하지 않습니다.
4. Buildpacks 이미지가 기존 Artifact Registry에 `${SOURCE_SHA}-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}` tag로 게시됩니다. 게시 후 Artifact Registry의 fully-qualified digest를 조회하며, Cloud Run에는 tag가 아닌 이 digest를 전달합니다.
5. 기존 서비스에 트래픽 없는 후보 revision과 후보 tag를 만듭니다.
6. 후보 revision의 generation이 observed generation과 같고 `Ready=True`이며 image digest가 일치하는지 확인합니다. 이어 후보 tag URL의 liveness·readiness가 모두 HTTP 200인지 확인합니다.
7. 후보 revision으로 트래픽을 100% 이동합니다.
8. 기본 서비스 URL의 liveness·readiness를 다시 확인하고, 최종 트래픽을 조회합니다.

후보 검증 실패는 기존 트래픽을 바꾸지 않습니다. 전환 명령이 timeout 또는 실패했더라도 후보에 트래픽이 일부 배정됐는지 다시 조회합니다. 후보의 배정이 확인되면 전환 전의 **구체 revision별 비율**로만 복귀를 시도합니다. 관련 없는 수동 변경이 섞이면 자동으로 덮어쓰지 않고 실패를 기록합니다.

CD는 다음을 변경하지 않습니다.

- 서비스 환경 변수, Secret 참조, runtime service account
- 메모리·CPU·확장·네트워크·프로브·공개 접근 설정
- GCP 리소스, IAM/WIF 바인딩, GitHub 변수 또는 Secret
- PROD, 프런트엔드, GHCR 패키지
- 인증·OAuth·브라우저 API smoke

## 최초 설정

초기 설정은 배포 workflow가 생성하거나 수정하지 않습니다. 설정한 뒤 첫 배포 전에는 기존 서비스와 Artifact Registry 저장소가 존재하는지 확인합니다.

### GitHub Variables

저장소 Actions variables에 다음 비밀이 아닌 식별자를 설정합니다.

| Variable | 예시 | 용도 |
| --- | --- | --- |
| `GCP_PROJECT_ID` | `kirikick` | DEV Google Cloud 프로젝트 |
| `GCP_REGION` | `asia-northeast3` | Cloud Run·Artifact Registry 리전 |
| `CLOUD_RUN_SERVICE` | `kirikick-api` | 기존 Cloud Run 서비스 이름 |
| `AR_REPOSITORY` | `<artifact-registry-repository>` | 기존 Artifact Registry 저장소 이름 |
| `WIF_PROVIDER` | `projects/<number>/locations/global/workloadIdentityPools/<pool>/providers/<provider>` | GitHub OIDC Workload Identity Provider 리소스 |
| `DEPLOY_SERVICE_ACCOUNT` | `<service-account>@<project>.iam.gserviceaccount.com` | DEV 배포 전용 service account |

값, Secret, 서비스 계정 키 파일을 workflow 로그·저장소·GitHub variables에 넣지 않습니다. 이 값은 GitHub Environment가 아니라 **repository-level Actions variables**에 설정합니다. GitHub OIDC와 Workload Identity Federation을 사용하며, 장기 service-account key를 만들지 않습니다.

### Workload Identity Federation과 권한

다음은 사용자가 Cloud Console에서 한 번 수행하는 최소 설정입니다. workflow가 pool, provider, IAM 정책을 만들거나 고치지 않습니다.

1. **IAM & Admin → Workload Identity Federation**에서 pool과 GitHub OIDC provider를 만듭니다. issuer는 `https://token.actions.githubusercontent.com`입니다.
2. provider attribute mapping에 다음을 넣습니다.

   | 대상 attribute | OIDC claim 식 |
   | --- | --- |
   | `google.subject` | `assertion.sub` |
   | `attribute.repository_id` | `assertion.repository_id` |

3. provider attribute condition은 실제 숫자 ID로 다음 네 조건을 모두 제한합니다. `<REPOSITORY_ID>`와 `<OWNER_ID>`는 GitHub 이름이 아닌 immutable numeric ID입니다.

   ```text
   assertion.repository_id == '<REPOSITORY_ID>' &&
   assertion.repository_owner_id == '<OWNER_ID>' &&
   assertion.ref == 'refs/heads/main' &&
   assertion.event_name == 'workflow_run' &&
   assertion.workflow_ref == '<OWNER>/<REPOSITORY>/.github/workflows/cd.yml@refs/heads/main'
   ```

4. 기존 `DEPLOY_SERVICE_ACCOUNT`의 권한 탭에서 다음 principal set에만 `roles/iam.workloadIdentityUser`를 부여합니다. `<PROJECT_NUMBER>`와 `<POOL_ID>`는 WIF pool의 실제 값으로 바꿉니다.

   ```text
   principalSet://iam.googleapis.com/projects/<PROJECT_NUMBER>/locations/global/workloadIdentityPools/<POOL_ID>/attribute.repository_id/<REPOSITORY_ID>
   ```

CI의 **upstream event**는 `push`여야 하지만, OIDC token은 CD의 `workflow_run`에서 발급됩니다. 따라서 Cloud Console의 provider condition에는 `assertion.event_name == 'workflow_run'`을 사용합니다. `id-token: write` 권한은 DEV 배포 job에만 부여합니다. PR·fork job은 Google 인증이나 배포를 수행하지 않아야 합니다. `workflow_ref`의 `<OWNER>/<REPOSITORY>`는 사람이 읽는 repository 이름을 쓰는 유일한 조건 항목이며, repository·owner 자체의 신뢰는 위 numeric ID 조건으로 결정합니다. IAM 역할은 workflow가 어떤 Cloud Run 필드를 바꿀 수 있는지까지 제한하지 않으므로, workflow의 image·revision·traffic-only 명령 제한도 함께 유지합니다.

DEV 배포 service account에는 기존 리소스에 한정해 다음 권한을 검토합니다.

| 대상 | 필요한 역할 | 목적 |
| --- | --- | --- |
| 기존 Artifact Registry 저장소 | Artifact Registry Writer | Buildpacks 이미지 게시·조회 |
| 기존 Cloud Run 서비스 | Cloud Run Developer | 새 revision과 traffic 변경 |
| 기존 runtime service account | Service Account User | 기존 runtime identity를 유지한 revision 배포 |
| DEV 배포 service account | Workload Identity User binding | GitHub OIDC 주체의 impersonation |

배포 service account와 애플리케이션 runtime service account는 분리합니다. Cloud Run의 image pull identity 권한은 별도로 실제 이미지 pull 경로에서 확인합니다. 역할의 프로젝트 단위 부여 대신, 가능한 경우 위의 기존 리소스에 범위를 제한합니다.

현재 workflow는 다음의 고정 commit SHA action을 사용합니다.

- `google-github-actions/auth@7c6bc770dae815cd3e89ee6cdf493a5fab2cc093`
- `google-github-actions/setup-gcloud@aa5489c8933f4cc7a4f7d45035b3b1440c9c10db`

공식 참조:

- [GitHub Actions OIDC for Google Cloud](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-google-cloud-platform)
- [Google Cloud deployment pipelines WIF](https://cloud.google.com/iam/docs/workload-identity-federation-with-deployment-pipelines)
- [Cloud Run IAM roles](https://cloud.google.com/run/docs/reference/iam/roles)
- [Artifact Registry access control](https://cloud.google.com/artifact-registry/docs/access-control)

## 배포 관찰

workflow summary에서 다음만 기록·확인합니다.

- `SOURCE_SHA`와 Artifact Registry image digest
- 후보 revision 이름, 후보 `Ready`/digest 검증 결과, 후보 tag URL의 liveness·readiness 결과
- 전환 전(`prior_traffic`)·관측된(`observed_traffic`)·최종 revision별 traffic map
- 현재 phase, rollback armed/attempted/succeeded, 후보 ready, endpoint별 health 결과, tag cleanup 결과

응답 본문, cookie, token, Secret, 환경 변수, 전체 Cloud Run 서비스 YAML은 summary에 기록하지 않습니다.
상태 조회가 실패하면 `observed_traffic`은 `null`입니다. 이전 조회값을 현재 트래픽으로 간주하지 말고 수동으로 확인합니다.
`rollback_succeeded`는 직전 트래픽 비율의 복원 확인이며, 애플리케이션·DB의 정상 복구까지 보장하지 않습니다.

CI의 기존 concurrency 정책은 CI에만 적용됩니다. CD는 별도 DEV concurrency group에서 진행 중인 rollout을 자동 취소하지 않습니다. GitHub concurrency는 FIFO가 아니므로 빠르게 연속 push하면 모든 중간 SHA가 DEV에 배포되지 않을 수 있습니다. 이미지 게시 전과 Cloud Run 변경 직전에 현재 `main` SHA와 `SOURCE_SHA`를 확인하며, 오래된 실행은 Cloud Run을 변경하지 않고 `SUPERSEDED_NOOP`으로 성공 종료합니다.

후보 URL은 후보 revision을 검증하는 용도이고, 전환 후 검증은 기본 서비스 URL에서 수행합니다. 후보 revision suffix와 후보 tag는 각각 `gh-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}` 형식을 사용합니다. 후보 tag cleanup만 실패한 경우 서비스는 이미 정상 revision으로 전환된 상태일 수 있습니다. 이 경우 workflow는 실패로 보고하고 tag 수동 정리를 요구하지만 정상 traffic을 이전 revision으로 되돌리지는 않습니다.

## 수동 traffic 복구

자동 복귀가 실패했거나 runner가 강제 종료된 경우에만 사용합니다. 자동 CD 또는 다른 수동 rollout이 진행 중이면 먼저 중지하거나 완료를 확인합니다. `LATEST`와 `--to-latest`는 사용하지 마세요. 현재 또는 이후 revision을 가리킬 수 있어 복구 대상이 고정되지 않습니다.

현재 traffic map을 먼저 확인합니다.

```sh
gcloud run services describe "$CLOUD_RUN_SERVICE" \
  --project="$GCP_PROJECT_ID" \
  --region="$GCP_REGION" \
  --format='yaml(status.traffic)'
```

전환 전 기록한 **이름이 있는 revision과 비율**로 복구합니다. 다음은 `revision-a` 90%, `revision-b` 10%였던 서비스의 예시입니다.

```sh
gcloud run services update-traffic "$CLOUD_RUN_SERVICE" \
  --project="$GCP_PROJECT_ID" \
  --region="$GCP_REGION" \
  --to-revisions='revision-a=90,revision-b=10'
```

명령 뒤에는 다시 traffic map을 조회하고, 기본 서비스 URL의 두 health endpoint를 확인합니다.

```sh
SERVICE_URL="$(gcloud run services describe "$CLOUD_RUN_SERVICE" \
  --project="$GCP_PROJECT_ID" \
  --region="$GCP_REGION" \
  --format='value(status.url)')"

curl --fail --silent --show-error "$SERVICE_URL/actuator/health/liveness"
curl --fail --silent --show-error "$SERVICE_URL/actuator/health/readiness"
```

위 `update-traffic` 구문은 설치된 gcloud CLI 도움말로 확인한 `--to-revisions=REVISION=PERCENTAGE` 형식입니다. 실제 Cloud Run·네트워크·자격 증명에 대한 실행 검증은 이 문서 작성 환경에서 수행하지 않았습니다. traffic 복귀는 Flyway migration, DB schema/data, 외부 Secret 변경 또는 다른 외부 부작용을 되돌리지 않습니다.

## 첫 실제 도입 확인

로컬 코드 검증은 실제 클라우드 도입 완료가 아닙니다. 최초 Actions 실행에서 다음을 확인합니다.

1. workflow가 배포한 commit SHA와 이미지 digest가 일치한다.
2. 후보 revision이 `Ready=True`이고 후보 URL의 liveness·readiness가 200이다.
3. 전환 뒤 기본 서비스 URL의 liveness·readiness가 200이고, 최종 traffic map이 후보 revision 100%다.
4. 필요한 필드 비교로 환경 변수·Secret 참조·runtime service account·메모리·확장·프로브·공개 접근이 기존과 같은지 확인한다.

실제 카카오 로그인, 브라우저 cookie 동작, 인증·프로필 API 동작은 이 CD health 확인 범위가 아닙니다. 별도 CI 및 수동 E2E 검증으로 다룹니다. 자동 복귀 실패를 검증하기 위해 실제 DEV에 장애를 의도적으로 만들 필요는 없습니다.

## 한계

- 수동 취소, runner 손실, 프로세스 강제 종료는 복귀 handler를 실행하지 못할 수 있습니다.
- 후보 readiness와 health 확인은 개별 API·OAuth·브라우저 E2E를 보증하지 않습니다.
- traffic 복귀는 revision traffic만 변경하며 데이터나 외부 상태를 원자적으로 되돌리지 않습니다.

관련 공식 문서:

- [Cloud Run rollout, rollback, traffic migration](https://cloud.google.com/run/docs/rollouts-rollbacks-traffic-migration)
- [`gcloud run services update-traffic`](https://cloud.google.com/sdk/gcloud/reference/run/services/update-traffic)
- [Spring Boot OCI image packaging](https://docs.spring.io/spring-boot/gradle-plugin/packaging-oci-image.html)

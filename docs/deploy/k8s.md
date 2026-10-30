# k8s manifest

상태: EC2 k3s에 `cluster`·`platform`(2026-10-29, #374)과 Envoy Gateway·cert-manager(2026-10-30, #372)를 적용했다. 외부 진입은 ServiceLB → Envoy다. FE `web`은 배포됐고, member-app·lecture-app은 ConfigMap·Deployment만 올라가 외부 route가 없다(2026-10-30, [릴리스 전 배포](#릴리스-전-배포)). 나머지 업무 앱은 미배포다. 기존 Docker Compose는 2026-10-30에 볼륨까지 지웠다(#440, [배포 개요](README.md)).

앱들이 함께 쓰는 인프라는 레포 루트의 `k8s/`, 앱 manifest는 앱마다 `apps/<앱>/k8s/`에 두고 로컬 k3d와 EC2 k3s에 같은 구성으로 적용한다. 구조와 설치 방식을 고른 근거는 [최종 인프라 설계 3장](../architecture/infrastructure/k3s.md#manifest-관리--kustomize와-helm), node 배치는 [AWS 2노드 k3s 배치](aws-k3s.md)를 따른다.

## 구조

| 경로 | namespace | 내용 | 적용 |
| --- | --- | --- | --- |
| `k8s/cluster` | kube-system 외 | namespace(`hapbang-prod`는 Pod Security `baseline` 강제), `local-path-retain` StorageClass, HelmChart(ECK operator, Sealed Secrets) | 수동 |
| `k8s/gateway/{install,base,overlays}` | envoy-gateway-system·cert-manager 외 | 공용 Envoy·TLS·Grafana·IMDS 차단 정책. [Gateway 절차](gateway.md) | 수동 |
| `k8s/components/core-data` | - | `hapbang.io/pool: core-data` label에 large required node affinity를 붙이는 Kustomize Component | platform이 사용 |
| `k8s/platform/{base,overlays/local,overlays/prod}` | hapbang-platform | PostgreSQL(HelmChart), Kafka·Redis(StatefulSet), Elasticsearch(ECK CR, nori는 init container로 설치), 관측성 HelmChart 5개, Kafka UI(Redpanda Console) | 수동 |
| `apps/<앱>/k8s/{base,overlays/local,overlays/prod}` | hapbang-prod | 앱 Deployment·Service·ConfigMap·HTTPRoute·SecurityPolicy·NetworkPolicy. overlay는 이미지와 Secret | prod는 [운영 앱 배포](#운영-앱-배포) |
| `k8s/local/k3d.yaml` | - | 로컬 k3d cluster 설정 | - |

- **앱은 앱 디렉터리만으로 배포할 수 있게 둔다.** 모노레포지만 레포를 나눌 수 있어야 하므로(AGENTS.md 3장) 앱 manifest는 Dockerfile 옆에 두고 루트 `k8s/`를 참조하지 않는다. 앱이 공용 인프라를 아는 것은 `<앱>-config`의 접속 주소(`*.hapbang-platform.svc.cluster.local`)뿐이다.
- 앱은 자기 node 배치(affinity), limit, 배포 전략을 직접 적는다. 앱 전체의 node별 합계는 [자원 예산](../architecture/infrastructure/k3s.md#ec2-2노드-자원-예산)과 아래 메모리 예산 확인으로 맞춘다.
- Helm CLI는 쓰지 않는다. 차트는 k3s 내장 helm-controller의 `HelmChart`(`helm.cattle.io/v1`)로 설치하고 values는 `valuesContent`에 적는다([k3s Helm](https://docs.k3s.io/add-ons/helm)).
- platform의 StatefulSet은 `hapbang.io/pool: core-data` label을 붙이면 component가 affinity를 넣는다. HelmChart와 Elasticsearch CR에는 component가 닿지 않으므로 values·`podTemplate`에 같은 affinity를 직접 적는다.
- 앱은 지금 있는 6개만 둔다. notification·search는 각 앱 티켓에서 더한다. lecture·customer-support는 #433, appearance는 #442부터 혼자 기동해 1이다.
- 공용 Envoy Gateway·cert-manager와 IMDS 차단 정책은 `k8s/gateway/`에 있다([Gateway 구성](gateway.md), #372). 앱별 HTTPRoute·SecurityPolicy·NetworkPolicy는 각 앱의 `apps/<앱>/k8s/base/`에 있다. 앱 외부 8080·내부 8081을 분리한다. platform(PostgreSQL·Kafka·Redis·Elasticsearch·관측성)에는 ingress NetworkPolicy를 두지 않는다.

## 버전

| 구성 요소 | 차트·이미지 | 버전 |
| --- | --- | --- |
| k3s | `rancher/k3s` (k3d), EC2 설치 | v1.36.5+k3s1 |
| Envoy Gateway | `oci://docker.io/envoyproxy/gateway-helm` | v1.9.3 |
| cert-manager | `oci://quay.io/jetstack/charts/cert-manager` | v1.21.3 |
| ECK operator | helm.elastic.co `eck-operator` | 3.5.0 |
| Elasticsearch | 공식 이미지 + init container의 analysis-nori 설치 | 9.5.5 |
| PostgreSQL | `oci://registry-1.docker.io/cloudpirates/postgres` | 0.21.2 (postgres 18.6) |
| Sealed Secrets | bitnami.github.io/sealed-secrets `sealed-secrets` | 2.22.0 (controller 0.41.2) |
| Prometheus | prometheus-community `prometheus` | 29.39.0 |
| Loki | grafana-community `loki` | 20.0.2 |
| Tempo | grafana-community `tempo` | 3.2.1 |
| Grafana | grafana-community `grafana` | 14.2.0 |
| Alloy | grafana `alloy` | 1.14.4 |
| Kafka | `apache/kafka` | 4.4.0 |
| Redis | `redis` | 8.10.3 |

버전을 올릴 때는 이 표와 manifest를 함께 고친다. Elasticsearch는 Dockerfile의 `FROM`과 CR의 `spec.version`이 같아야 한다.

## Secret

평문 Secret은 커밋하지 않는다. prod는 `kubeseal`로 만든 SealedSecret을 각 prod overlay에 커밋한다(#374). local overlay는 같은 이름의 가짜 값 Secret을 쓴다. 이름에 hash를 붙이지 않으므로 prod와 이름이 같다.

Secret은 앱마다 하나다. 앱은 자기 Secret만 envFrom으로 받으므로 다른 앱의 DB 비밀번호를 참조할 수 없고, 레포를 나눌 때 자기 Secret만 가져가면 된다.

| Secret | namespace | 키 | 위치 |
| --- | --- | --- | --- |
| `member-app-secrets` | hapbang-prod | `DB_PASSWORD`, `HAPBANG_INTERNAL_API_KEY`, `S3_BUCKET`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `CHZZK_CLIENT_ID`, `CHZZK_CLIENT_SECRET`, `CHZZK_REDIRECT_URI` | `apps/member-app/k8s/overlays/prod` |
| `billing-app-secrets` | hapbang-prod | `DB_PASSWORD`, `HAPBANG_INTERNAL_API_KEY`, 선택: `TOSS_PAYMENTS_SECRET_KEY`, `BILLING_PAYMENT_KEY_ENCRYPTION_KEY`, `BILLING_PAYMENT_KEY_HMAC_KEY`(없으면 충전 API만 503) | `apps/billing-app/k8s/overlays/prod` |
| `lecture-app-secrets` | hapbang-prod | `DB_PASSWORD`, `HAPBANG_INTERNAL_API_KEY`, `S3_BUCKET` | `apps/lecture-app/k8s/overlays/prod` |
| `appearance-app-secrets`, `chat-app-secrets`, `customer-support-app-secrets` | hapbang-prod | `DB_PASSWORD`, `HAPBANG_INTERNAL_API_KEY` | 각 앱의 `k8s/overlays/prod` |
| `postgres-credentials` | hapbang-platform | `postgres`(superuser), `member`, `billing`, `appearance`, `lecture`, `chat`, `customersupport` | `k8s/platform/overlays/prod` |
| `ghcr-pull` | hapbang-prod | `.dockerconfigjson`(type `kubernetes.io/dockerconfigjson`). BE 앱 6개와 FE가 함께 쓴다 | `k8s/registry` ([GHCR pull 인증](#ghcr-pull-인증)) |

- `postgres-credentials`의 앱별 값은 각 앱 Secret의 `DB_PASSWORD`와 같아야 한다. PostgreSQL이 첫 기동 때 이 값으로 앱 role을 만든다.
- `HAPBANG_INTERNAL_API_KEY`는 모든 앱이 같은 값을 쓴다.

앱 SealedSecret은 `k8s/tools/seal-app-secrets.sh`로 만든다. 값은 화면에 출력하지 않는다.

- `DB_PASSWORD`는 EC2 cluster의 `postgres-credentials`에서 읽고, `HAPBANG_INTERNAL_API_KEY`는 `~/.hapbang/internal-api-key`를 쓴다. 파일이 없으면 멈추고, 처음 설치할 때만 앱 이름 없이 `HAPBANG_NEW_INTERNAL_API_KEY=1`로 실행해 새 키로 앱 6개를 함께 봉인한다. 빈 `DB_PASSWORD`, billing 결제 키 한쪽만 있는 경우도 멈춘다.
- 그 밖의 값은 레포 밖 `~/.hapbang/prod-secrets.env`에 `KEY=VALUE`로 적는다. 처음 값은 기존 Compose 서버의 `prod.env`에서 옮겼다. 값 전체를 감싼 따옴표 한 쌍은 뗀다. 필수: `S3_BUCKET`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `CHZZK_CLIENT_ID`, `CHZZK_CLIENT_SECRET`, `CHZZK_REDIRECT_URI`. 선택: `TOSS_PAYMENTS_SECRET_KEY`, `BILLING_PAYMENT_KEY_ENCRYPTION_KEY`, `BILLING_PAYMENT_KEY_HMAC_KEY`.
- 필수 값이 없으면 키 이름만 알리고 멈춘다. 만든 파일은 각 앱의 `overlays/prod/<앱>-app-secrets.yaml`이고 kustomization `resources`에 더한다.
- `~/.hapbang`의 파일은 팀 비밀 보관소에도 둔다. 내부 API 키를 다시 만들면 앱 6개를 모두 다시 봉인한다.

Sealed Secrets controller 키를 잃으면 위 SealedSecret을 모두 다시 만들어야 한다. 백업과 복구는 [AWS 2노드 k3s 배치](aws-k3s.md#sealed-secrets-키-백업)를 따른다.

### GHCR pull 인증

GHCR 패키지는 조직 정책으로 private이고 public으로 바꿀 수 없다. BE 앱 이미지(`ghcr.io/prgrms-be-adv-devcourse/beadv8_8_lms_be/<앱>`)와 FE 이미지(`ghcr.io/prgrms-be-adv-devcourse/beadv8_8_lms_fe`)를 `hapbang-prod`의 공유 Secret `ghcr-pull` 하나로 받는다(#460).

| 항목 | 값 |
| --- | --- |
| 인증 | kaameo의 classic PAT, 권한은 `read:packages` 하나. GHCR은 fine-grained PAT를 받지 않는다 |
| 만료 | 90일. 지금 토큰은 2026-12-29 만료다. **2026-12-15까지 교체한다**(만료 14일 전). 봉인 스크립트는 만료가 있는지만 검사하고 원래 수명은 알 수 없으므로, 90일은 PAT를 만드는 사람이 고른다 |
| 담당 | kaameo. 생성·봉인·적용·교체·폐기를 한다. 토큰 원문은 레포·이슈·채팅에 쓰지 않는다 |
| 참조 | BE 앱 6개 prod overlay의 `imagePullSecrets` patch, FE 레포 `k8s/overlays/prod` |
| 적용 | `k8s/registry`를 손으로 적용한다. 앱·FE 배포는 확인만 하고 만들거나 지우지 않는다 |

- 인증은 셋으로 나뉜다. 발행은 Publish images job의 `GITHUB_TOKEN`(`packages: write`), Deploy job의 이미지 존재 확인은 러너의 `GITHUB_TOKEN`(`packages: read`), node의 pull은 `ghcr-pull`이다. job 토큰은 job이 끝나면 만료되므로 node pull에 쓰지 않고, 토큰을 SSM 명령으로 서버에 보내지 않는다.
- PAT의 `read:packages`는 그 계정이 읽을 수 있는 모든 package에 적용되고, cluster 관리자는 Secret을 읽을 수 있다. 짧은 만료와 교체로 노출 기간을 줄인다.
- k8s 1.35부터 kubelet은 node에 있는 이미지도 pull 인증을 확인한다(`KubeletEnsureSecretPulledImages` 기본 켜짐, 정책 `NeverVerifyPreloadedImages`, [Kubernetes images](https://kubernetes.io/docs/concepts/containers/images/#ensureimagepullcredentialverification)). 같은 Secret으로 받은 적이 있는 이미지는 registry에 묻지 않고 통과하지만, Secret이 없거나 내용이 바뀌면 registry에서 다시 받는다. 그래서 Secret을 배포 동안만 두지 않고 계속 둔다.
  - 토큰이 만료돼도 Secret을 그대로 두면 이미 받은 이미지로는 pod가 다시 뜬다. 새 이미지 배포, node에 없는 이전 버전 롤백, image GC 뒤 재기동은 실패한다.
  - 교체 뒤에는 새로 뜨는 pod마다 registry에서 한 번 다시 받는다. 이미 실행 중인 컨테이너는 Secret 교체로 재시작되지 않는다.

**처음 설치**(Sealed Secrets controller가 뜬 뒤, 첫 앱·FE 배포 전)

1. `k8s/tools/seal-ghcr-pull.sh`를 실행한다. 아이디와 토큰(화면에 보이지 않음)을 입력받는다. 토큰 권한이 `read:packages` 하나가 아니거나 만료가 없으면, 또는 BE·FE 패키지 중 읽을 수 없는 것이 있으면 멈춘다. 발행 전 패키지는 알리고 넘어간다. 결과 `k8s/registry/ghcr-pull.yaml`과 위 표의 만료일을 커밋한다.
2. `kubectl --context hapbang-k3s apply -k k8s/registry` 뒤 `kubectl --context hapbang-k3s -n hapbang-prod get secret ghcr-pull -o jsonpath='{.type}'`이 `kubernetes.io/dockerconfigjson`인지 본다.
3. 두 node에서 실제로 받아지는지 본다. `k8s/tools/ghcr-pull-check.sh`는 지정한 pool에 `imagePullPolicy: Always` 검증 pod를 띄워 버전 명령만 실행하고 지운다. 층까지 새로 받았는지 보려면 먼저 그 node에서 `sudo k3s crictl rmi <이미지>`로 지운다(node 재시작은 캐시를 지우지 않는다).

   ```bash
   k8s/tools/ghcr-pull-check.sh ghcr.io/prgrms-be-adv-devcourse/beadv8_8_lms_fe:sha-<FE 커밋> core-data
   k8s/tools/ghcr-pull-check.sh ghcr.io/prgrms-be-adv-devcourse/beadv8_8_lms_fe:sha-<FE 커밋> app-observe
   # Secret 없이는 실패해야 한다(패키지가 private인지 확인)
   SECRET= k8s/tools/ghcr-pull-check.sh ghcr.io/prgrms-be-adv-devcourse/beadv8_8_lms_fe:sha-<FE 커밋> app-observe
   ```

**교체**(만료 14일 전, 또는 계정 권한 회수·토큰 유출 때 바로)

1. 새 PAT를 만든다(`read:packages`, 90일). 이전 PAT는 아직 지우지 않는다.
2. `k8s/tools/seal-ghcr-pull.sh`로 다시 봉인해 PR로 커밋하고, `kubectl --context hapbang-k3s apply -k k8s/registry`로 적용한다. 이름이 같으므로 앱·FE manifest는 바꾸지 않는다.
3. 두 node에서 `ghcr-pull-check.sh`가 성공하는지 본다.
4. GitHub에서 이전 PAT를 삭제하고, 3을 다시 실행해 새 토큰만으로 받아지는지 본다.
5. 위 표의 만료일을 고친다. 실행 중인 pod를 다시 띄울 필요는 없다.

**만료·회수 뒤 복구**: 새 pod가 `ImagePullBackOff`이고 배포 로그에 "GHCR 인증에 실패했습니다(401·403)"가 나오면 토큰이 만료됐거나 권한이 없다. 교체 1~3단계로 같은 이름의 Secret에 새 토큰을 넣는다. backoff 중인 pod는 kubelet이 다시 시도하고, 기다리기 싫으면 그 pod를 지운다. 토큰을 다시 넣기 전에는 롤백도 같은 이유로 실패한다.

## PostgreSQL database

PostgreSQL은 앱들이 함께 쓰는 인프라라 `hapbang-platform`에 둔다. 그래서 앱 배포가 PostgreSQL을 건드리지 않는다. instance 1개에 앱마다 database와 같은 이름의 소유 role을 두고 `REVOKE CONNECT ... FROM PUBLIC`으로 다른 role의 접속을 막는다([10장](../architecture/infrastructure/database.md)). 앱의 Flyway가 database 안에 자기 schema를 만든다.

- database·role은 차트의 initdb script(`k8s/platform/base/postgres.yaml`)가 **데이터 디렉터리가 비어 있을 때만** 만든다.
- 이미 초기화된 instance에 database를 더할 때(notification·search 앱 추가 등)는 script를 고치고, 실행 중인 instance에도 같은 SQL을 superuser로 실행한다.
- 기존 Compose 운영 DB는 이전하지 않고 2026-10-30에 지웠다(#440). 필요 없는 데이터만 있어 k3s PostgreSQL에서 새로 시작한다.

## 적용 순서

`cluster`가 설치하는 CRD(Elasticsearch, SealedSecret)가 있어야 `platform`·prod overlay가 적용된다. HelmChart는 helm-controller가 Job으로 설치하므로 CRD가 생길 때까지 기다린다.

```bash
kubectl apply -k k8s/cluster
kubectl wait --for=create crd/elasticsearches.elasticsearch.k8s.elastic.co --timeout=5m
kubectl wait --for=condition=Established crd/elasticsearches.elasticsearch.k8s.elastic.co

kubectl apply -k k8s/platform/overlays/local     # prod: overlays/prod

# prod만: 앱·FE보다 먼저 GHCR pull Secret을 둔다. 로컬은 k3d에 넣은 이미지를 쓴다.
kubectl apply -k k8s/registry

# 앱은 하나씩 적용한다. prod는 배포 workflow(#374)가 앱마다 overlays/prod를 적용한다.
for app in apps/*-app; do kubectl apply -k "$app/k8s/overlays/local"; done
```

## 운영 앱 배포

prod 앱은 GitHub Actions가 배포한다(#374). 앱 이미지는 private GHCR 패키지라 node가 공유 Secret `ghcr-pull`로 받는다([GHCR pull 인증](#ghcr-pull-인증)). 배포 스크립트는 적용 전에 그 Secret이 있고 형식이 맞는지 확인하고, rollout이 실패하면 원인을 이미지 없음·GHCR 인증 실패·앱 기동 실패로 나눠 알린다(#461).

```
main push → Publish images(publish.yml)
  바뀐 앱만 테스트(ci.yml) → ghcr.io/prgrms-be-adv-devcourse/beadv8_8_lms_be/<앱>:sha-<그 앱을 마지막으로 바꾼 main 커밋>

Actions → Deploy(deploy.yml) → Run workflow(main), 입력 sha(비우면 main 최신)·apps(비우면 자동)
  지금 도는 이미지와 비교해 바뀐 앱을 고른다 → 이미지가 모두 GHCR에 있는지 확인
  → 앱마다 순서대로(member → billing → appearance → lecture → chat → customer-support)
     SSM Run Command로 large에서 .github/scripts/k3s-deploy.sh apply
       ghcr-pull Secret 확인 → 해당 커밋의 apps/<앱>/k8s/overlays/prod를 받아 이미지 태그를 넣고 apply → rollout status
       실패하면 원인을 알리고 그 앱만 rollout undo, 남은 앱은 배포하지 않음
```

- **바뀐 앱**: `apps/<앱>/`가 바뀌면 그 앱, `common/`·`gradle/`·루트 `build.gradle.kts`·`settings.gradle.kts`가 바뀌면 전체다. 앱 목록과 배포 순서는 `.github/scripts/apps.sh` 한 곳에 있고, `apps/*-app`과 다르면 CI(`verify`)가 실패한다. 새 앱은 여기에 추가한다.
- **롤백**: Deploy를 `apps`에 앱, `sha`에 되돌릴 main 커밋을 넣어 실행한다. `rollout undo`는 Deployment만 되돌리고 ConfigMap 외의 리소스(HTTPRoute·Secret 등)는 되돌리지 않는다.
  - Deploy는 그 커밋의 overlay 전체를 적용하므로 SealedSecret도 그 커밋 것으로 돌아간다. 비밀번호·내부 API 키를 교체한 커밋보다 이전으로 롤백하면 새로 뜨는 pod가 무효한 자격 증명을 쓴다. 그런 경우 이전 커밋의 SealedSecret을 지금 값으로 다시 봉인한 커밋을 만들어 배포한다.
  - Flyway 마이그레이션은 되돌리지 않는다. 이전 이미지가 지금 스키마에서 동작하는지 먼저 확인한다.
- **cluster 접근**: GitHub OIDC로 IAM role을 받아 SSM Run Command로 large에서 root로 `k3s kubectl`을 실행한다. 6443과 SSH를 쓰지 않는다. role trust는 GitHub Environment `prod`의 job만 허용하고, `prod`는 `main` 브랜치만 허용한다. `prod` Environment Secrets는 `AWS_DEPLOY_ROLE_ARN`, `DEPLOY_INSTANCE_ID`다.
  - 이 레포는 GitHub OIDC의 immutable subject를 쓴다. trust의 `sub`는 `repo:<org>@<org id>/<repo>@<repo id>:environment:prod` 형식이라 레포를 옮기거나 다시 만들면 바꿔야 한다.
  - large의 인스턴스 역할에 `AmazonSSMManagedInstanceCore`가 있어야 SSM agent가 등록된다.
- 공개 레포라 Actions 로그도 공개다. 배포 스크립트는 node 이름·IP가 나오는 출력(`-o wide`, `describe`)을 하지 않는다.
- `k8s/cluster`·`k8s/platform`·`k8s/gateway`·`k8s/registry`는 자동 배포하지 않는다.
- 서버 스크립트는 로컬 k3d에서 시험할 수 있다: `KUBECTL="kubectl --context k3d-hapbang" ROLLOUT_TIMEOUT=60s .github/scripts/k3s-deploy.sh apply <앱> <GitHub에 있는 커밋> <태그 커밋>`. k3d에는 `ghcr-pull`이 없어 Secret 확인에서 멈추므로, 가짜 값으로 `kubectl create secret docker-registry ghcr-pull --docker-server=ghcr.io ...`를 만들어 둔다(pull은 401·403으로 실패해 원인 구분을 시험할 수 있다). 시험 뒤 `apps/<앱>/k8s/overlays/local`을 다시 적용하고, 함께 생긴 prod SealedSecret(`kubectl delete sealedsecret <앱>-secrets`)과 가짜 `ghcr-pull`을 지운다.

### 릴리스 전 배포

dev → main 병합 전에 일부 앱을 먼저 운영에 올릴 때 쓴다(#461). 이미지는 사람이 `write:packages` PAT로 직접 빌드해 올린다. Publish images(Actions)는 main push에서만 돈다.

1. **테스트와 jar**: origin/dev를 받은 깨끗한 작업 폴더(커밋하지 않은 변경 없음)에서 `./gradlew :<앱>:check :<앱>:bootJar`. 변경이 남아 있으면 jar와 태그 커밋이 달라진다.
2. **이미지 빌드**: EC2는 amd64다. 태그는 jar를 만든 dev 커밋으로 붙인다. 라벨은 패키지를 이 레포에 연결한다.

   ```bash
   test -z "$(git status --porcelain)" || { echo '커밋하지 않은 변경이 있다'; exit 1; }
   T=$(git rev-parse HEAD)
   docker buildx build --platform linux/amd64 --load \
     --label org.opencontainers.image.source=https://github.com/prgrms-be-adv-devcourse/beadv8_8_LMS_BE \
     -t "ghcr.io/prgrms-be-adv-devcourse/beadv8_8_lms_be/<앱>:sha-${T}" \
     -f apps/<앱>/Dockerfile apps/<앱>/build/libs
   ```

3. **push**: classic PAT(`write:packages`, 만료 1일)를 만들고 별도 터미널에서 `docker login ghcr.io -u <GitHub 아이디>`(Password 칸에 붙여 넣는다) → `docker push <이미지>` → `docker logout ghcr.io` → PAT 삭제. 토큰은 채팅·이슈에 붙이지 않는다.
   - 패키지가 없으면 첫 push가 패키지를 만든다. 2026-10-30 member-app·lecture-app은 첫 push가 패키지만 만들고 `denied`로 끝났고, 다시 로그인해 push하자 올라갔다.
   - **사람이 만든 패키지는 main의 Publish images(`GITHUB_TOKEN`)가 push하지 못한다.** 패키지 페이지 → Package settings → Manage Actions access에 `beadv8_8_LMS_BE`를 Write로 더한다. member-app·lecture-app이 이 경우다.
4. **pull 확인**: `k8s/tools/ghcr-pull-check.sh <이미지> <pool>`([GHCR pull 인증](#ghcr-pull-인증)).
5. **배포**: Deploy workflow는 main 커밋만 받으므로 로컬에서 한다. prod kubeconfig(context `hapbang-k3s`)가 필요하다. 앱은 배포 순서(member → billing → appearance → lecture → chat → customer-support)를 지킨다.

   ```bash
   KUBECTL="kubectl --context hapbang-k3s" .github/scripts/k3s-deploy.sh apply <앱> <dev 커밋 40자> <태그 커밋 40자>
   ```

   이 스크립트는 overlay 전체(Service·HTTPRoute·SecurityPolicy·NetworkPolicy 포함)를 적용해 Envoy로 외부에 연다. 처음 올리는 앱이 뜨는지만 볼 때는 ConfigMap·NetworkPolicy·Deployment만 적용한다. NetworkPolicy는 빼지 않는다. 빼면 다른 pod가 앱 포트에 바로 닿고, `hapbang-prod`의 `deny-metadata-egress`가 IMDS를 막아 S3를 쓰는 앱(member·appearance·lecture)이 자격 증명을 받지 못한다. 이미 Service·HTTPRoute가 있는 앱에 이 방식으로 올리면 새 이미지가 바로 요청을 받으므로 쓰지 않는다.

   ```bash
   # kustomize는 절대 경로 resource를 받지 않아 레포 안에 임시 폴더를 둔다.
   d=$(mktemp -d "$PWD/.k3s-min.XXXXXX")
   cat > "$d/kustomization.yaml" <<'YAML'
   resources: [../apps/<앱>/k8s/overlays/prod]
   images: [{name: <앱>, newName: ghcr.io/prgrms-be-adv-devcourse/beadv8_8_lms_be/<앱>, newTag: sha-<태그 커밋>}]
   YAML
   kubectl kustomize "$d" | yq 'select(.kind == "ConfigMap" or .kind == "NetworkPolicy" or .kind == "Deployment")' > "$d/min.yaml"
   kubectl --context hapbang-k3s apply -f "$d/min.yaml"
   rm -rf "$d"
   ```

- 로컬 배포와 Deploy workflow를 동시에 돌리지 않는다. Deploy의 동시 실행 제한(`concurrency`)은 workflow끼리만 막는다. 겹치면 한쪽의 apply·`rollout undo`가 다른 쪽 배포를 덮어쓰거나 되돌린다.
- 먼저 올린 앱의 커밋이 main에 들어가기 전에는 Deploy를 입력 없이 실행하지 않는다. 자동 판단이 그 앱을 main의 더 오래된 이미지로 되돌릴 수 있다.
- 릴리스 뒤에는 먼저 올린 앱을 Deploy의 `apps`에 적어 다시 배포한다. 입력 없이 돌리면 앱 내용이 그대로인 앱은 건너뛰어, 사람이 올린 이미지와 최소 배포에서 뺀 리소스(Service·HTTPRoute 등)가 그대로 남는다.
- main 릴리스 리뷰와 CI를 거치지 않은 이미지다. 어떤 앱을 어느 커밋으로 언제 올렸는지 해당 티켓에 남긴다.
- 2026-10-30 기록: member-app·lecture-app을 이 방식으로 올렸다(#461). 처음에는 ConfigMap·Deployment만 적용해 NetworkPolicy가 빠졌다. lecture-app은 384Mi·448Mi limit에서 기동 중 OOMKilled돼 640Mi로 올렸고, manifest와 [k3s 예산](../architecture/infrastructure/k3s.md#ec2-2노드-자원-예산)도 640Mi로 고쳤다.

### 첫 릴리스

main은 2026-10-28 이후 병합되지 않아 Publish images가 main에 없다. 앱 이미지 패키지는 member-app·lecture-app만 있다(2026-10-30 [릴리스 전 배포](#릴리스-전-배포)로 사람이 만들었다). 이 두 패키지는 Manage Actions access에 `beadv8_8_LMS_BE` Write가 있어야 첫 Publish가 push된다. 나머지 4개는 첫 Publish가 만든다. 처음 한 번은 아래 순서로 한다. 이후 릴리스는 1·2·4단계만 한다.

1. **dev → main 병합**: base `main` ← `dev`(또는 `release/review-N`) PR을 만들어 병합한다. 다른 브랜치에서 열면 `PR source / source-branch` 체크가 실패한다(#180).
2. **발행 확인**: Actions → Publish images의 main run이 성공했는지 본다. 발행한 앱은 run 요약의 "발행 대상"에 있다. 실패하면 원인을 고쳐 다시 병합한다.
3. **pull 확인(앱마다 처음 한 번)**: 패키지는 private 그대로 둔다. 발행된 앱 이미지를 그 앱이 뜨는 pool에서 `ghcr-pull`로 받아 본다([GHCR pull 인증](#ghcr-pull-인증)). 태그는 Publish images run 요약에 있다. pool은 member·billing·appearance가 `core-data`, lecture·chat·customer-support가 `app-observe`다. 발행한 패키지를 kaameo 계정이 읽지 못하면 실패하므로, 패키지 설정의 Manage Actions access·권한을 확인한다.

   ```bash
   k8s/tools/ghcr-pull-check.sh ghcr.io/prgrms-be-adv-devcourse/beadv8_8_lms_be/<앱>:sha-<태그 커밋> <pool>
   ```

4. **배포**: Actions → Deploy → Run workflow(main)를 입력 없이 실행한다. 앱 SealedSecret과 `ghcr-pull`은 `hapbang-prod`에 이미 있다([Secret](#secret)).

## 로컬 k3d 검증

EC2에는 prod만 있으므로 manifest는 로컬 k3d에서 먼저 띄워 본다. 실제 로그인·결제·S3 업로드는 하지 않고, pod 기동과 node별 메모리만 확인한다.

### 준비

- Docker Desktop 메모리를 14 GiB 이상으로 둔다. k3d node 두 개에 EC2와 같은 메모리 제한(7,600 MiB·3,700 MiB)을 건다.
- k3d v5.9.0, kubectl이 필요하다.

```bash
k3d cluster create --config k8s/local/k3d.yaml
kubectl get nodes -L hapbang.io/pool       # server: core-data, agent: app-observe

# 앱 이미지를 빌드해 cluster에 넣는다
./gradlew bootJar
for app in apps/*-app; do
  docker build -t "hapbang/$(basename "$app"):local" -f "$app/Dockerfile" "$app/build/libs"
done
k3d image import -c hapbang \
  $(for app in apps/*-app; do echo "hapbang/$(basename "$app"):local"; done)
```

그다음 [적용 순서](#적용-순서)대로 local overlay를 적용한다. 끝나면 `k3d cluster delete hapbang`으로 지운다. Retain StorageClass의 데이터는 k3d node container와 함께 지워진다.

### 확인

| 확인 | 명령 | 기대 결과 |
| --- | --- | --- |
| node 배치 | `kubectl get pods -A -o wide` | Kafka·Redis·Elasticsearch·PostgreSQL·member·billing은 server, 관측성·ECK operator·Sealed Secrets·chat은 agent |
| HelmChart 설치 | `kubectl get helmcharts -A`, `kubectl get jobs -A` | `helm-install-*` Job이 모두 Complete |
| PVC | `kubectl get pvc -A` | 모두 Bound, StorageClass `local-path-retain` |
| Redis AOF | `kubectl -n hapbang-platform exec redis-0 -- redis-cli CONFIG GET appendonly` | `yes`. pod를 지운 뒤에도 키가 남아 있다 |
| Kafka consumer group | pod 안에서 `kafka-topics.sh --create`, `kafka-console-producer.sh`, `kafka-console-consumer.sh --group` | 메시지를 받고 `__consumer_offsets` topic이 생긴다(내부 topic replication factor 1) |
| Elasticsearch | `kubectl -n hapbang-platform get elasticsearch`, `_cat/plugins`, `_analyze`(`tokenizer: nori_tokenizer`) | HEALTH green, `analysis-nori`, 한국어가 형태소로 나뉜다 |
| 관측성 | `kubectl -n hapbang-platform port-forward svc/grafana 3000:80` → datasource 3개 test | Prometheus·Loki·Tempo 모두 성공 |
| database 분리 | postgres pod에서 `psql -h 127.0.0.1 -U lecture -d member` | `permission denied for database "member"` |
| 앱 | `kubectl -n hapbang-prod get deploy` | member·billing·chat `1/1`, 나머지 `0/0` |
| 배포 전략 | `kubectl -n hapbang-prod rollout restart deploy/member-app deploy/chat-app` 중 `kubectl get pods -w` | member는 새 pod가 Ready가 된 뒤 기존 pod가 내려가고, chat은 기존 pod가 먼저 내려간다 |
| 메모리 예산 | `kubectl describe node <node>`의 Allocated resources(memory limits), `kubectl top node` | limit 합계에 `replicas: 0`인 앱의 limit을 더해도 3장 예산 안이고, 10분 뒤 OOMKilled가 없다 |

## 관측성 접속

**Grafana는 외부에서 접속할 수 있게 열어야 한다.** Envoy Gateway의 HTTPS(443) `/grafana/` route와 Grafana 자체 로그인으로 연다([Gateway 구성](gateway.md), #372). security group에 Grafana 3000을 직접 열지는 않는다([AWS 2노드 k3s 배치](aws-k3s.md#network와-외부-노출)).

운영 전환 전에는 ClusterIP Service에 `kubectl port-forward`로 접속한다. Gateway local overlay를 적용했다면 Grafana root URL은 `https://hapbang.local:8443/grafana/`이므로 Gateway의 443을 local 8443으로 포워딩해 접속한다.

```bash
CTX=k3d-hapbang   # EC2는 hapbang-k3s(6443이 허용된 IP에서만)

# Grafana: http://localhost:3000, 사용자 admin
kubectl --context "$CTX" -n hapbang-platform get secret grafana -o jsonpath='{.data.admin-password}' | base64 -d; echo
kubectl --context "$CTX" -n hapbang-platform port-forward svc/grafana 3000:80

# Prometheus UI: http://localhost:9090
kubectl --context "$CTX" -n hapbang-platform port-forward svc/prometheus-server 9090:80

# Kafka UI(Redpanda Console): http://localhost:8080
kubectl --context "$CTX" -n hapbang-platform port-forward svc/redpanda-console 8080:8080
```

- admin 비밀번호는 차트가 처음 설치할 때 만든 Secret(`grafana`)에 있다. 레포·채팅에 옮기지 않는다.
- Loki(3100)·Tempo(3200)는 Grafana datasource로 조회한다.
- Redpanda Console은 자체 로그인이 없고 메시지 발행·토픽 삭제도 할 수 있다. Gateway를 설치하면 `https://<도메인>/kafka/`에서 Envoy Basic Auth만 거쳐 접속한다([Gateway 구성](gateway.md)). 그 전에는 port-forward로 접속한다.
- prod `/kafka/`에서 토픽 생성은 HTTP 405로 실패한다(#484, 조회는 된다). 쓰기는 port-forward로 한다: `kubectl --context hapbang-k3s -n hapbang-platform port-forward svc/redpanda-console 18080:8080` 뒤 `http://localhost:18080`.

## CI

`ci.yml`의 `manifests` job이 PR마다 `k8s/`와 `apps/<앱>/k8s`의 overlay별로 `kubectl kustomize` → `kubeconform -strict`를 실행한다. Kubernetes schema는 k3s와 같은 1.36.5, CRD(HelmChart·Elasticsearch) schema는 [CRDs-catalog](https://github.com/datreeio/CRDs-catalog)의 고정 커밋을 쓴다.

kubeconform은 형식만 검사한다. `valuesContent` 안의 차트 values, Service selector와 Pod label의 일치, 이미지 존재, 앱 기동은 검사하지 못하므로 manifest를 바꾸면 로컬 k3d로 확인한다.

`-strict`는 Kubernetes 기본 리소스와 HelmChart의 모르는 필드(오타)를 잡는다. Elasticsearch CR은 CRDs-catalog schema가 모르는 필드를 허용해 `spec` 아래 오타를 잡지 못한다(2026-10-29 확인).

PR마다 k3d에 적용하는 smoke는 앱이 모두 단독 기동한 뒤 더한다.

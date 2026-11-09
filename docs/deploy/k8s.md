# k8s manifest

상태: EC2 k3s에 `cluster`·`platform`(2026-10-29, #374)과 Envoy Gateway·cert-manager(2026-10-30, #372)를 적용했다. 외부 진입은 ServiceLB → Envoy다. FE `web`은 배포됐다. 업무 앱 6개(member·billing·appearance·lecture·chat·customer-support)는 [릴리스 전 배포](#릴리스-전-배포)로 처음 올라가 route·JWT 검증까지 연결됐고(2026-11-02, member #500, chat #545, 나머지 넷 #541), 같은 날 [dev 자동 배포](#dev-자동-배포-전환)의 첫 run이 6개 앱을 Actions가 발행한 이미지로 다시 배포했다(#561, #571). 이후 dev에 병합하면 바뀐 앱이 자동으로 배포된다. 기존 Docker Compose는 2026-10-30에 볼륨까지 지웠다(#440, [배포 개요](README.md)).

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

## 앱 사이 내부 호출 주소

앱은 다른 앱의 내부 API(`/api/v1/internal/**`)를 그 앱 Service의 내부 포트 8081로 부른다(D62·D63, #372). 주소는 비밀이 아니므로 각 앱 `k8s/base/kustomization.yaml`의 ConfigMap(`<앱>-config`)에 두고, 앱의 `application.yaml`이 `hapbang.clients.<제공 앱>.base-url`로 읽는다. 인증은 모든 앱이 같은 `HAPBANG_INTERNAL_API_KEY`([Secret](#secret))로 한다. 받는 앱의 NetworkPolicy는 `hapbang.io/internal-api-client: 'true'` 라벨이 붙은 pod만 8081로 받는다. 로컬은 각 앱의 `application-local.yaml`이 `http://localhost:<앱 포트>`를 쓴다.

| 호출하는 앱 | 환경 변수 → Spring 키 | 값 | 쓰는 곳 |
| --- | --- | --- | --- |
| lecture-app | `BILLING_API_BASE_URL` → `hapbang.clients.billing.base-url` | `http://billing-app:8081` | HP 보류·반환·지급 (#275~#277) |
| appearance-app | `BILLING_API_BASE_URL` → `hapbang.clients.billing.base-url`, `MEMBER_API_BASE_URL` → `hapbang.clients.member.base-url` | `http://billing-app:8081`, `http://member-app:8081` | HP 보류·반환·지급·위약금 (#274), 회원 Backfill (#394) |
| billing-app | `MEMBER_API_BASE_URL`, `LECTURE_API_BASE_URL`, `APPEARANCE_API_BASE_URL` → `hapbang.clients.{member,lecture,appearance}.base-url` | `http://member-app:8081`, `http://lecture-app:8081`, `http://appearance-app:8081` | 회원 Backfill, 판매 수익 조회 (#295) |
| chat-app | `MEMBER_API_BASE_URL`, `APPEARANCE_API_BASE_URL` → `hapbang.clients.{member,appearance}.base-url` | `http://member-app:8081`, `http://appearance-app:8081` | 회원 백필 (#280에서 ConfigMap에 추가), 모집용 단체방의 모집글·지원 이력 조회 (#584) |
| customer-support-app | `LECTURE_API_BASE_URL` → `hapbang.clients.lecture.base-url` | `http://lecture-app:8081` | 이의 제기 접수 (#279) |
| member-app | `APPEARANCE_API_BASE_URL`, `LECTURE_API_BASE_URL`, `BILLING_API_BASE_URL` → `hapbang.clients.{appearance,lecture,billing}.base-url` | `http://appearance-app:8081`, `http://lecture-app:8081`, `http://billing-app:8081` | 받은 출연 리뷰 목록 (#555), 회원 탈퇴 확인·지갑 닫기·다시 열기 (#654) |

- chat-app 외의 앱은 `application.yaml`에 기본값이 없으므로 값을 꼭 넣는다. chat-app은 기본값이 `http://localhost:8081`이라 ConfigMap에 값이 없으면 회원 앱이 아니라 chat 자신의 내부 포트를 부른다.
- 제공 앱을 새로 부르면 이 표, 호출하는 앱의 ConfigMap, 제공 앱 NetworkPolicy의 허용 라벨을 함께 확인한다.

## Secret

평문 Secret은 커밋하지 않는다. prod는 `kubeseal`로 만든 SealedSecret을 각 prod overlay에 커밋한다(#374). local overlay는 같은 이름의 가짜 값 Secret을 쓴다. 이름에 hash를 붙이지 않으므로 prod와 이름이 같다. member의 `JWT_PRIVATE_KEY`는 가짜 값도 커밋하지 않고, local overlay가 `HAPBANG_JWT_ALLOW_EPHEMERAL_KEY=true`로 시작할 때마다 임시 서명 키를 만들게 한다(#500).

Secret은 앱마다 하나다. 앱은 자기 Secret만 envFrom으로 받으므로 다른 앱의 DB 비밀번호를 참조할 수 없고, 레포를 나눌 때 자기 Secret만 가져가면 된다.

| Secret | namespace | 키 | 위치 |
| --- | --- | --- | --- |
| `member-app-secrets` | hapbang-prod | `DB_PASSWORD`, `HAPBANG_INTERNAL_API_KEY`, `S3_BUCKET`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `CHZZK_CLIENT_ID`, `CHZZK_CLIENT_SECRET`, `CHZZK_REDIRECT_URI`, `JWT_PRIVATE_KEY`(JWT 서명 키, #500), `MAIL_USERNAME`, `MAIL_PASSWORD`(이메일 인증 코드 발송용 Gmail 계정·앱 비밀번호, #557) | `apps/member-app/k8s/overlays/prod` |
| `billing-app-secrets` | hapbang-prod | `DB_PASSWORD`, `HAPBANG_INTERNAL_API_KEY`, 선택: `TOSS_PAYMENTS_SECRET_KEY`, `BILLING_PAYMENT_KEY_ENCRYPTION_KEY`, `BILLING_PAYMENT_KEY_HMAC_KEY`(없으면 충전 API만 503) | `apps/billing-app/k8s/overlays/prod` |
| `lecture-app-secrets` | hapbang-prod | `DB_PASSWORD`, `HAPBANG_INTERNAL_API_KEY`, `S3_BUCKET` | `apps/lecture-app/k8s/overlays/prod` |
| `appearance-app-secrets`, `chat-app-secrets`, `customer-support-app-secrets` | hapbang-prod | `DB_PASSWORD`, `HAPBANG_INTERNAL_API_KEY` | 각 앱의 `k8s/overlays/prod` |
| `auxiliary-app-secrets` | hapbang-prod | `DB_PASSWORD`(role `auxiliary`), `HAPBANG_INTERNAL_API_KEY`, 선택: `OPENAI_API_KEY`, `ES_API_KEY`(챗봇 API key `auxiliary-app-chatbot`, 2026-11-06 생성, 2027-02-04 만료. **2027-01-21까지 교체한다**). 선택 키는 챗봇 FAQ(#715) 전까지 없어도 된다 | `apps/auxiliary-app/k8s/overlays/prod` |
| `postgres-credentials` | hapbang-platform | `postgres`(superuser), `member`, `billing`, `appearance`, `lecture`, `chat`, `customersupport`, `auxiliary`(#726) | `k8s/platform/overlays/prod` |
| `ghcr-pull` | hapbang-prod | `.dockerconfigjson`(type `kubernetes.io/dockerconfigjson`). BE 앱 7개와 FE가 함께 쓴다 | `k8s/registry` ([GHCR pull 인증](#ghcr-pull-인증)) |
| `grafana-alerting` | hapbang-platform | `SLACK_WEBHOOK_URL`(Grafana alert를 보내는 Slack Incoming Webhook, #781). `k8s/tools/seal-grafana-alerting.sh`가 `~/.hapbang/prod-secrets.env`의 `GRAFANA_SLACK_WEBHOOK_URL`로 봉인한다 | `k8s/platform/overlays/prod` ([Alert](../architecture/infrastructure/observability.md#alert-정책-629)) |

- `postgres-credentials`의 앱별 값은 각 앱 Secret의 `DB_PASSWORD`와 같아야 한다. PostgreSQL이 첫 기동 때 이 값으로 앱 role을 만든다.
- `HAPBANG_INTERNAL_API_KEY`는 모든 앱이 같은 값을 쓴다.

앱 SealedSecret은 `k8s/tools/seal-app-secrets.sh`로 만든다. 값은 화면에 출력하지 않는다.

- `DB_PASSWORD`는 EC2 cluster의 `postgres-credentials`에서 읽고, `HAPBANG_INTERNAL_API_KEY`는 `~/.hapbang/internal-api-key`를 쓴다. 파일이 없으면 멈추고, 처음 설치할 때만 앱 이름 없이 `HAPBANG_NEW_INTERNAL_API_KEY=1`로 실행해 새 키로 앱 7개를 함께 봉인한다. 빈 `DB_PASSWORD`, billing 결제 키 한쪽만 있는 경우도 멈춘다.
- 그 밖의 값은 레포 밖 `~/.hapbang/prod-secrets.env`에 `KEY=VALUE`로 적는다. 처음 값은 기존 Compose 서버의 `prod.env`에서 옮겼다. 값 전체를 감싼 따옴표 한 쌍은 뗀다. 필수: `S3_BUCKET`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `CHZZK_CLIENT_ID`, `CHZZK_CLIENT_SECRET`, `CHZZK_REDIRECT_URI`, `JWT_PRIVATE_KEY`, `MAIL_USERNAME`, `MAIL_PASSWORD`. 선택: `TOSS_PAYMENTS_SECRET_KEY`, `BILLING_PAYMENT_KEY_ENCRYPTION_KEY`, `BILLING_PAYMENT_KEY_HMAC_KEY`, `OPENAI_API_KEY`, `ES_API_KEY`.
- 필수 값이 없으면 키 이름만 알리고 멈춘다. 만든 파일은 각 앱의 `overlays/prod/<앱>-app-secrets.yaml`이고 kustomization `resources`에 더한다.
- `~/.hapbang`의 파일은 팀 비밀 보관소에도 둔다. 내부 API 키를 다시 만들면 앱 7개를 모두 다시 봉인한다.
- `JWT_PRIVATE_KEY`는 RSA 2048 PKCS#8 PEM을 줄바꿈 없이 한 줄로 적는다(`openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 | tr -d '\n'`). env 파일은 여러 줄 값을 받지 못하고, member-app은 공백·줄바꿈을 지우고 읽는다. 이 키는 팀 비밀 보관소에 두지 않는다. 잃으면 새로 만들어 member만 다시 봉인한다. refresh token은 서명 키와 무관해 그대로 쓰이고, 이미 발급한 access token(최대 15분)만 무효가 된다(#500).

Sealed Secrets controller 키를 잃으면 위 SealedSecret을 모두 다시 만들어야 한다. 백업과 복구는 [AWS 2노드 k3s 배치](aws-k3s.md#sealed-secrets-키-백업)를 따른다.

### GHCR pull 인증

GHCR 패키지는 조직 정책으로 private이고 public으로 바꿀 수 없다. BE 앱 이미지(`ghcr.io/prgrms-be-adv-devcourse/beadv8_8_lms_be/<앱>`)와 FE 이미지(`ghcr.io/prgrms-be-adv-devcourse/beadv8_8_lms_fe`)를 `hapbang-prod`의 공유 Secret `ghcr-pull` 하나로 받는다(#460).

| 항목 | 값 |
| --- | --- |
| 인증 | kaameo의 classic PAT, 권한은 `read:packages` 하나. GHCR은 fine-grained PAT를 받지 않는다 |
| 만료 | 90일. 지금 토큰은 2026-12-29 만료다. **2026-12-15까지 교체한다**(만료 14일 전). 봉인 스크립트는 만료가 있는지만 검사하고 원래 수명은 알 수 없으므로, 90일은 PAT를 만드는 사람이 고른다 |
| 담당 | kaameo. 생성·봉인·적용·교체·폐기를 한다. 토큰 원문은 레포·이슈·채팅에 쓰지 않는다 |
| 참조 | BE 앱 7개 prod overlay의 `imagePullSecrets` patch, FE 레포 `k8s/overlays/prod` |
| 적용 | `k8s/registry`를 손으로 적용한다. 앱·FE 배포는 확인만 하고 만들거나 지우지 않는다 |

- 인증은 셋으로 나뉜다. 발행은 Publish and deploy `build` job의 `GITHUB_TOKEN`(`packages: write`), Deploy job의 이미지 존재 확인은 러너의 `GITHUB_TOKEN`(`packages: read`), node의 pull은 `ghcr-pull`이다. job 토큰은 job이 끝나면 만료되므로 node pull에 쓰지 않고, 토큰을 SSM 명령으로 서버에 보내지 않는다.
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
- 이미 초기화된 instance에 database를 더할 때는 script를 고치고, 실행 중인 instance에도 같은 SQL을 superuser로 실행한다(아래 절차). auxiliary-app의 `auxiliary`도 이렇게 더한다(#726).
- 기존 Compose 운영 DB는 이전하지 않고 2026-10-30에 지웠다(#440). 필요 없는 데이터만 있어 k3s PostgreSQL에서 새로 시작한다.
- `max_connections` 100에서 슈퍼유저 예약 3을 뺀 97개를 앱이 쓴다. HikariCP 풀은 앱 5개 × 10 + chat-app 20 + auxiliary-app 5 = 75이고, large rolling surge pod 1개(10)를 더하면 85다(2026-11-06 운영 `hikaricp_connections_max`, #726). billing-app의 pool 밖 실행 락 연결(pod당 1개, 배포 중 2개)까지 넣으면 87이다([database 분리](../architecture/infrastructure/database.md)). 앱을 더하거나 풀 크기를 바꾸면 다시 계산한다.

### 실행 중인 instance에 database 더하기

비밀번호는 화면·명령 인자에 남기지 않는다. `<db>`는 database·role·`postgres-credentials` 키 이름이다(예: `auxiliary`).

1. PR에서 `postgres-credentials`에 키를 더해 다시 봉인한다. `--merge-into`는 기존 키의 암호문을 그대로 두고 새 키만 더한다. 같은 PR에서 initdb 목록(`k8s/platform/base/postgres.yaml`)과 local overlay의 가짜 값도 더한다. local에 값이 없으면 새 로컬 cluster의 initdb가 `set -u`로 멈춘다.

   ```bash
   openssl rand -hex 24 | tr -d '\n' \
     | kubectl create secret generic postgres-credentials -n hapbang-platform --from-file=<db>=/dev/stdin --dry-run=client -o yaml \
     | kubeseal --context hapbang-k3s --format yaml --merge-into k8s/platform/overlays/prod/postgres-credentials.yaml
   ```

2. 병합 뒤 SealedSecret만 적용하고, 풀린 Secret에 키가 생겼는지 본다. platform overlay 전체를 적용하지 않는다(ECK 기본값 diff로 Elasticsearch가 다시 뜰 수 있다).

   ```bash
   kubectl --context hapbang-k3s apply -f k8s/platform/overlays/prod/postgres-credentials.yaml
   kubectl --context hapbang-k3s -n hapbang-platform get sealedsecret postgres-credentials -o jsonpath='{.metadata.generation} {.status.observedGeneration} {.status.conditions[0].status}{"\n"}'
   kubectl --context hapbang-k3s -n hapbang-platform get secret postgres-credentials -o go-template='{{range $k, $v := .data}}{{$k}} {{end}}{{"\n"}}'
   ```

3. superuser로 role과 database를 만든다. 비밀번호는 Secret에서 읽어 psql의 표준 입력으로만 넘긴다. pod 안의 psql은 local socket(trust)으로 접속한다.

   ```bash
   db=<db>
   pw=$(kubectl --context hapbang-k3s -n hapbang-platform get secret postgres-credentials -o jsonpath="{.data.${db}}" | base64 -d)
   { printf '\\set db %s\n\\set pw %s\n' "$db" "$pw"; cat <<'SQL'; } | kubectl --context hapbang-k3s -n hapbang-platform exec -i postgres-0 -c postgres -- psql -v ON_ERROR_STOP=1 -U postgres -d postgres
   CREATE ROLE :"db" LOGIN PASSWORD :'pw';
   CREATE DATABASE :"db" OWNER :"db";
   REVOKE CONNECT ON DATABASE :"db" FROM PUBLIC;
   SQL
   unset pw
   ```

4. 결과를 본다. owner가 `<db>`이고, PUBLIC에 CONNECT 권한이 없어야 한다(`datacl`에 `=Tc/`가 아니라 `=T/`).

   ```bash
   kubectl --context hapbang-k3s -n hapbang-platform exec postgres-0 -c postgres -- psql -U postgres -d postgres -Atc "select datname, pg_get_userbyid(datdba), datacl from pg_database where datname = '<db>'"
   ```

앱 Secret의 `DB_PASSWORD`는 `k8s/tools/seal-app-secrets.sh`가 cluster의 `postgres-credentials`에서 읽어 봉인한다([Secret](#secret)). schema는 앱의 Flyway가 만든다.

## Elasticsearch 앱 접근

Elasticsearch는 `hapbang-platform`의 ECK CR `hapbang`이다. 앱은 모듈마다 role 하나와 API key 하나로 자기 index pattern에만 접근한다([검색 설계](../architecture/infrastructure/search.md#고객센터-rag와의-관계)). elastic superuser 비밀번호는 앱에 주지 않는다.

| 항목 | 값 |
| --- | --- |
| 주소 | `https://hapbang-es-http.hapbang-platform.svc:9200`. ECK HTTP Service이고 서버 인증서 SAN에 있다. 비밀이 아니므로 앱 ConfigMap에 둔다 |
| CA | ECK 자체 서명 CA. `hapbang-platform`의 Secret `hapbang-es-http-certs-public` 키 `ca.crt`. 앱은 다른 namespace의 Secret을 마운트할 수 없으므로 `hapbang-prod`의 ConfigMap `elasticsearch-ca`(키 `ca.crt`)로 복사해 둔다. 2026-10-29 생성, 2027-10-29 만료(ECK 기본 1년, 만료 전에 ECK가 새로 만든다) |

| 모듈 (앱) | role | index pattern | 권한 | API key 이름 | 앱 Secret 키 |
| --- | --- | --- | --- | --- | --- |
| chatbot (auxiliary-app) | `chatbot` | `chatbot-*` (`chatbot-<종류>-<공급자>-<차원>`) | index `all`, cluster `monitor` | `auxiliary-app-chatbot` | `ES_API_KEY` |

API key 만료는 GHCR PAT와 같이 90일로 하고, 만든 날과 만료일을 [Secret](#secret) 표에 적는다. 만료 14일 전에 새 key를 만들어 앱 Secret을 다시 봉인하고, 배포 뒤 이전 key를 무효로 한다.

### CA를 앱 namespace로 복사

공개 인증서라 Secret이 아니라 ConfigMap으로 둔다. cluster를 다시 만들었거나 ECK가 CA를 새로 만들었으면 다시 실행하고 ES를 쓰는 앱을 다시 띄운다. 로컬 k3d는 `--context k3d-hapbang`으로 같은 명령을 실행한다.

```bash
kubectl --context hapbang-k3s -n hapbang-platform get secret hapbang-es-http-certs-public -o jsonpath='{.data.ca\.crt}' | base64 -d \
  | kubectl --context hapbang-k3s -n hapbang-prod create configmap elasticsearch-ca --from-file=ca.crt=/dev/stdin --dry-run=client -o yaml \
  | kubectl --context hapbang-k3s apply -f -
kubectl --context hapbang-k3s -n hapbang-prod get configmap elasticsearch-ca -o jsonpath='{.data.ca\.crt}' | openssl x509 -noout -subject -enddate
```

마지막 줄은 `CN=hapbang-http`와 만료일을 출력해야 한다.

### role과 API key 만들기

elastic superuser로 port-forward를 거쳐 만든다. 한 터미널에서 port-forward를 띄워 둔다.

```bash
kubectl --context hapbang-k3s -n hapbang-platform port-forward svc/hapbang-es-http 9200:9200
```

다른 터미널에서 접속 함수를 만든다. 비밀번호·key는 curl 설정 파일 형식(`-K`)으로 넘겨 명령 인자에 남기지 않는다. 인증서 이름을 맞추려고 Service 이름을 127.0.0.1로 돌린다.

```bash
es_pw=$(kubectl --context hapbang-k3s -n hapbang-platform get secret hapbang-es-elastic-user -o go-template='{{.data.elastic | base64decode}}')
ca=$(mktemp)
kubectl --context hapbang-k3s -n hapbang-platform get secret hapbang-es-http-certs-public -o jsonpath='{.data.ca\.crt}' | base64 -d > "$ca"
es() { local p=$1; shift; curl -sS --cacert "$ca" --resolve hapbang-es-http.hapbang-platform.svc:9200:127.0.0.1 -K <(printf 'user = "elastic:%s"\n' "$es_pw") -H 'Content-Type: application/json' "https://hapbang-es-http.hapbang-platform.svc:9200${p}" "$@"; }
```

먼저 조회한다. role이 없으면 `{}`, 같은 이름의 유효한 key가 없으면 빈 목록이다.

```bash
es /_security/_authenticate | jq '{username, roles}'
es /_security/role/chatbot
es '/_security/api_key?name=auxiliary-app-chatbot&active_only=true' | jq '[.api_keys[] | {id, name, expiration: (.expiration / 1000 | todate)}]'
```

role과 API key를 만든다. key 값(`encoded`)은 화면에 내지 않고 `~/.hapbang/prod-secrets.env`의 `ES_API_KEY` 줄로 바로 쓴다. API key의 권한은 만들 때 넣은 `role_descriptors`로 고정되므로 role을 바꾸면 key를 다시 만든다.

```bash
desc='{"cluster":["monitor"],"indices":[{"names":["chatbot-*"],"privileges":["all"]}]}'
es /_security/role/chatbot -X PUT -d "$desc"
resp=$(es /_security/api_key -X POST -d "{\"name\":\"auxiliary-app-chatbot\",\"expiration\":\"90d\",\"role_descriptors\":{\"chatbot\":${desc}},\"metadata\":{\"app\":\"auxiliary-app\",\"issue\":726}}")
printf '%s' "$resp" | jq '{id, name, expiration: (.expiration / 1000 | todate)}'
key=$(printf '%s' "$resp" | jq -r '.encoded // empty')
f=~/.hapbang/prod-secrets.env
[ -n "$key" ] && ( umask 077; { grep -v '^ES_API_KEY=' "$f"; printf 'ES_API_KEY=%s\n' "$key"; } > "$f.tmp" ) && mv "$f.tmp" "$f" && grep -c '^ES_API_KEY=' "$f"
unset resp key
```

마지막 줄은 `1`을 출력해야 한다. 기존 `ES_API_KEY` 줄을 지우고 새 줄 하나만 남기므로 `seal-app-secrets.sh`와 아래 확인 단계가 새 key를 읽는다. `sed -i`는 macOS와 Linux의 문법이 달라 쓰지 않는다.

key로 권한을 확인한다. 기대 결과는 순서대로 `api_key`, 200, 200, 200, 403이다. 확인용 index는 바로 지운다.

```bash
k=$(grep -m1 '^ES_API_KEY=' ~/.hapbang/prod-secrets.env | cut -d= -f2-)
esk() { local p=$1; shift; curl -sS --cacert "$ca" --resolve hapbang-es-http.hapbang-platform.svc:9200:127.0.0.1 -K <(printf 'header = "Authorization: ApiKey %s"\n' "$k") "https://hapbang-es-http.hapbang-platform.svc:9200${p}" "$@"; }
esk /_security/_authenticate | jq -r .authentication_type
esk /_cluster/health -o /dev/null -w '%{http_code}\n'
esk /chatbot-check-726 -X PUT -o /dev/null -w '%{http_code}\n'
esk /chatbot-check-726 -X DELETE -o /dev/null -w '%{http_code}\n'
esk /search-check-726 -X PUT -o /dev/null -w '%{http_code}\n'
unset k es_pw; rm -f "$ca"
```

이전 key를 무효로 할 때는 `es /_security/api_key -X DELETE -d '{"ids":["<id>"]}'`를 쓴다.

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

#480 이전에 만든 cluster는 Alloy 설정이 `k8s/gateway`의 HelmChartConfig `alloy`에 남아 있다. 이 값이 platform HelmChart의 `alloy.configMap.content`를 덮어써 pod log pipeline이 돌지 않는다. `kubectl apply -k`는 파일에서 빠진 리소스를 지우지 않으므로, alloy HelmChart를 먼저 적용한 뒤 직접 지운다. 먼저 지우면 새 HelmChart를 적용하기 전까지 OTLP 수신이 없어 trace가 빈다.

```bash
# 새 HelmChart가 적용됐는지 본다. 1이어야 한다
kubectl -n hapbang-platform get helmchart alloy -o jsonpath='{.spec.valuesContent}' | grep -c loki.source.kubernetes
kubectl -n hapbang-platform delete helmchartconfig alloy --ignore-not-found
```

## 운영 앱 배포

prod 앱은 dev에 병합하면 GitHub Actions가 발행부터 배포까지 자동으로 한다(#374, #559). 서버는 prod 한 벌이고 **main에는 서버가 없다**. main은 `release/review-N` → main 리뷰 제출용으로만 병합하고, main push에는 workflow가 돌지 않는다. 앱 이미지는 private GHCR 패키지라 node가 공유 Secret `ghcr-pull`로 받는다([GHCR pull 인증](#ghcr-pull-인증)). 배포 스크립트는 적용 전에 그 Secret이 있고 형식이 맞는지 확인하고, rollout이 실패하면 원인을 이미지 없음·GHCR 인증 실패·앱 기동 실패로 나눠 알린다(#461).

```
dev push → Publish and deploy(publish.yml)
  images  앱마다 이 커밋에서 쓸 이미지 :sha-<그 앱을 마지막으로 바꾼 dev 커밋>이 GHCR에 있는지 보고 없는 앱을 고른다
  ci      고른 앱만 테스트(ci.yml)
  build   고른 앱을 ghcr.io/prgrms-be-adv-devcourse/beadv8_8_lms_be/<앱>:sha-<태그 커밋>으로 발행
  deploy  deploy.yml 호출. 차례가 왔을 때 이 커밋이 dev 최신이 아니면 건너뛴다(뒤 push의 run이 배포)
    지금 도는 이미지와 비교해 바뀐 앱을 고른다 → 이미지가 모두 GHCR에 있는지 확인
    → 앱마다 순서대로(member → billing → appearance → lecture → chat → customer-support)
       SSM Run Command로 large에서 .github/scripts/k3s-deploy.sh apply
         ghcr-pull Secret 확인 → 해당 커밋의 apps/<앱>/k8s/overlays/prod를 받아 이미지 태그를 넣고 apply → rollout status
         실패하면 원인을 알리고 그 앱만 rollout undo, 남은 앱은 배포하지 않음

수동 Deploy(deploy.yml, 브랜치 dev): 입력 sha(비우면 dev 최신)·apps(비우면 자동). 따라잡기·롤백에 쓴다
```

- **바뀐 앱**: `apps/<앱>/`가 바뀌면 그 앱, `common/`·`gradle/`·루트 `build.gradle.kts`·`settings.gradle.kts`가 바뀌면 전체다. 앱 목록과 배포 순서는 `.github/scripts/apps.sh` 한 곳에 있고, `apps/*-app`과 다르면 CI(`verify`)가 실패한다. 새 앱은 여기에 추가한다.
- **발행할 앱**: 이번 push에서 바뀐 앱은 태그가 새 커밋이라 항상 발행한다. 앞 run이 실패했거나 아직 발행 중이라 이미지가 없는 앱도 다시 발행하므로, run이 성공하면 그 커밋의 모든 앱 이미지가 GHCR에 있다. 연달아 병합하면 두 run이 같은 태그를 함께 올릴 수 있지만, 같은 앱 내용을 테스트한 jar라 어느 쪽이 남아도 같다.
- **dev 최신만 배포**: 배포 job은 자동·수동 모두 한 번에 하나씩 돈다(concurrency `deploy-k3s-prod`, `queue: max`). 기다리는 run은 취소되지 않고 들어온 순서대로 돈다. 기본값(`single`)이면 늦게 끝난 앞 커밋의 run이 기다리던 dev 최신 run이나 수동 롤백을 밀어내 취소한다. 자동 배포는 차례가 왔을 때 dev 최신 커밋이 아니면 건너뛴다. 늦게 끝난 앞 run이 뒤 커밋의 배포를 되돌리지 않게 하기 위해서다. 그래서 dev 최신 커밋의 테스트·발행이 실패하면 그 앞 커밋도 배포되지 않는다. 고친 커밋을 병합하거나, 앞 커밋을 `sha`에 넣어 Deploy를 수동으로 실행한다.
- **흔들린 테스트로 실패했을 때**: dev 최신 커밋의 run이 코드와 무관한 이유(Maven Central 403, 가끔 실패하는 테스트)로 실패하면, 그 run에서 실패한 job만 다시 돌린다(`gh run rerun <run id> --failed`). 테스트가 통과하면 발행·배포까지 이어진다. 다시 돌리기 전에 그 run이 dev 최신 커밋의 run인지 본다. 앞 커밋의 run을 다시 돌리면 배포 단계에서 "dev 최신이 아니다"로 건너뛴다.
- **서버가 꺼진 시간**: EC2는 09:00~18:00에만 켜져 있다([배포 개요](README.md)). 그 밖에 병합하면 배포 job이 SSM에서 실패한다. 다음 병합의 run이 밀린 앱까지 배포한다. 기다리지 않으려면 서버가 켜진 뒤 Deploy를 입력 없이 실행한다.
- **수동 실행**: Actions → Deploy → Run workflow(브랜치 dev), 또는 `gh workflow run deploy.yml --ref dev`(입력은 `-f sha=<dev 커밋> -f apps="<앱> <앱>"`). 기본 브랜치가 `dev`라(2026-11-02) 버튼은 dev의 `deploy.yml`을 따른다. 최근 run은 `gh run list --workflow publish.yml --limit 3`으로 본다.
- **롤백**: Deploy를 `apps`에 앱, `sha`에 되돌릴 dev 커밋을 넣어 실행한다. `rollout undo`는 Deployment만 되돌리고 ConfigMap 외의 리소스(HTTPRoute·Secret 등)는 되돌리지 않는다. 롤백 뒤 다음 dev 병합의 자동 배포는 지금 도는 이미지와 dev 최신을 비교해 그 앱을 다시 올린다. 문제를 고친 커밋을 병합할 때까지 dev 병합을 멈추거나, 되돌리는 커밋(revert)을 병합한다.
  - Deploy는 그 커밋의 overlay 전체를 적용하므로 SealedSecret도 그 커밋 것으로 돌아간다. 비밀번호·내부 API 키를 교체한 커밋보다 이전으로 롤백하면 새로 뜨는 pod가 무효한 자격 증명을 쓴다. 그런 경우 이전 커밋의 SealedSecret을 지금 값으로 다시 봉인한 커밋을 만들어 배포한다.
  - Flyway 마이그레이션은 되돌리지 않는다. 이전 이미지가 지금 스키마에서 동작하는지 먼저 확인한다.
- **첫 배포 실패**: 새 앱의 첫 배포가 rollout에서 실패하면 이전 revision이 없어 `rollout undo`가 실패하고, 실패한 이미지 태그가 Deployment에 남는다. 그 뒤의 자동 배포와 입력 없이 실행한 Deploy는 지금 도는 이미지(실패한 태그)와 dev 최신 사이에 앱 내용이 그대로면 그 앱을 건너뛴다. 원인(이미지 pull 권한, Secret 등)을 고친 뒤 `apps`에 그 앱을 넣어 Deploy를 실행한다. `apps`를 적으면 비교 없이 배포한다.
- **cluster 접근**: GitHub OIDC로 IAM role을 받아 SSM Run Command로 large에서 root로 `k3s kubectl`을 실행한다. 6443과 SSH를 쓰지 않는다. role trust는 GitHub Environment `prod`의 job만 허용하고, `prod`는 `dev` 브랜치만 허용한다(#559 전에는 `main`). `prod` Environment Secrets는 `AWS_DEPLOY_ROLE_ARN`, `DEPLOY_INSTANCE_ID`다. 자동 배포는 `publish.yml`이 이 두 secret을 이름으로 `deploy.yml`에 넘긴다. reusable workflow는 호출된 job에 `environment`가 있어도 넘겨받은 secret만 보고, 넘기지 않으면 빈 값이다(#570).
  - 이 레포는 GitHub OIDC의 immutable subject를 쓴다. trust의 `sub`는 `repo:<org>@<org id>/<repo>@<repo id>:environment:prod` 형식이라 레포를 옮기거나 다시 만들면 바꿔야 한다. 브랜치는 `sub`에 들어가지 않으므로 배포 브랜치를 바꿀 때 role은 그대로다.
  - large의 인스턴스 역할에 `AmazonSSMManagedInstanceCore`가 있어야 SSM agent가 등록된다.
- 공개 레포라 Actions 로그도 공개다. 배포 스크립트는 node 이름·IP가 나오는 출력(`-o wide`, `describe`)을 하지 않는다.
- `k8s/cluster`·`k8s/platform`·`k8s/gateway`·`k8s/registry`는 자동 배포하지 않는다.
- 로컬에서 prod에 앱을 직접 적용하지 않는다. 자동 배포와 겹치면 한쪽의 apply·`rollout undo`가 다른 쪽 배포를 덮어쓰거나 되돌린다. 장애 대응으로 불가피하면 그동안 dev 병합을 멈춘다.
- 서버 스크립트는 로컬 k3d에서 시험할 수 있다: `KUBECTL="kubectl --context k3d-hapbang" ROLLOUT_TIMEOUT=60s .github/scripts/k3s-deploy.sh apply <앱> <GitHub에 있는 커밋> <태그 커밋>`. k3d에는 `ghcr-pull`이 없어 Secret 확인에서 멈추므로, 가짜 값으로 `kubectl create secret docker-registry ghcr-pull --docker-server=ghcr.io ...`를 만들어 둔다(pull은 401·403으로 실패해 원인 구분을 시험할 수 있다). 시험 뒤 `apps/<앱>/k8s/overlays/local`을 다시 적용하고, 함께 생긴 prod SealedSecret(`kubectl delete sealedsecret <앱>-secrets`)과 가짜 `ghcr-pull`을 지운다.

### 릴리스 전 배포

dev 자동 배포(#559) 전에 쓰던 방식이다. 이제 쓰지 않는다. main 병합 전에 일부 앱을 먼저 운영에 올리려고, 사람이 origin/dev에서 `./gradlew :<앱>:check :<앱>:bootJar`로 jar를 만들고 `write:packages` 1일 PAT로 이미지를 push한 뒤 로컬에서 `k3s-deploy.sh apply`나 최소 배포(NetworkPolicy → ConfigMap·Deployment)로 올렸다(#461). 태그는 jar를 만든 dev 커밋이라 dev 자동 배포의 태그 규칙과 같다. 아래는 그때 확인한 사실이다.

- 패키지가 없으면 첫 push가 패키지를 만든다. 2026-10-30 member-app·lecture-app은 첫 push가 패키지만 만들고 `denied`로 끝났고, 다시 로그인해 push하자 올라갔다. 2026-11-02 appearance-app은 첫 push에 바로 올라갔고, billing-app은 첫 push가 `denied`로 끝나 다시 push하자 올라갔다.
- 사람이 만든 패키지에 Publish and deploy(`GITHUB_TOKEN`)가 push하려면 레포 Actions에 쓰기 권한이 있어야 한다. 이미지 라벨 `org.opencontainers.image.source`로 패키지를 레포에 연결하고, 패키지 페이지 → Package settings → Inherited access에서 "Inherit access from source repository"를 켠다. Manage Actions access에 `beadv8_8_LMS_BE`가 보이는지 확인한다. 조직이 상속을 막아 항목이 없으면 Manage Actions access → Add Repository → `beadv8_8_LMS_BE`(Write)로 더한다([GitHub 문서](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility)). member-app·lecture-app은 2026-10-30, appearance-app·customer-support-app·chat-app·billing-app은 2026-11-02 상속을 켰다.
- 최소 배포는 overlay를 `kubectl kustomize`로 렌더링해 NetworkPolicy를 먼저, 그다음 ConfigMap·Deployment만 적용하는 방식이다. Service·HTTPRoute가 없어 외부에 열리지 않는다. NetworkPolicy를 빼면 다른 pod가 앱 포트에 바로 닿고, `hapbang-prod`의 `deny-metadata-egress`가 IMDS를 막아 S3를 쓰는 앱(member·appearance·lecture)이 자격 증명을 받지 못한다. kustomize 출력은 Deployment가 앞이라, 한 번에 적용하면 pod가 정책 없이 먼저 뜬다.
- 2026-10-30 기록: member-app·lecture-app을 이 방식으로 올렸다(#461). 처음에는 ConfigMap·Deployment만 적용해 NetworkPolicy가 빠졌다. lecture-app은 384Mi·448Mi limit에서 기동 중 OOMKilled돼 640Mi로 올렸고, manifest와 [k3s 예산](../architecture/infrastructure/k3s.md#ec2-2노드-자원-예산)도 640Mi로 고쳤다.
- 2026-11-02 기록: appearance-app을 dev `5d66992`로 이 방식(NetworkPolicy → ConfigMap·Deployment)으로 올렸다(#504). Flyway가 `appearance` 스키마를 만들고 회원 이벤트 consumer가 `member.events.v1`을 받았다. route는 billing-app 배포 뒤 연다. billing-app이 없으면 요청서·모집글이 보류 요청 실패로 `HOLD_PENDING`에 남기 때문이다. 448Mi limit에서 기동 3분 뒤 RSS 425Mi로 한도에 붙어 512Mi로 올렸고, 다시 뜬 뒤 4분에 RSS 414Mi였다([k3s 예산](../architecture/infrastructure/k3s.md#ec2-2노드-자원-예산)).
- 2026-11-02 기록: lecture-app과 customer-support-app을 dev `e85ae26f`(#525, 이의 제기 연동)로 이 방식으로 올렸다. lecture-app은 이미지와 함께 Service를 더 적용했다(고객센터 → 강의 내부 API 8081용, HTTPRoute 없음). customer-support-app은 처음 배포라 NetworkPolicy → ConfigMap·Deployment 순서로 적용했고, 이미지 패키지도 이때 처음 만들었다. Flyway가 강의 `V202611021128`, 고객센터 4개(`V202611021129`까지)를 적용했고, `lecture-app.objection-command`·`customer-support-app.objection-result`가 상대 토픽의 partition 3개씩을 받았다. 레이블을 붙인 임시 pod에서 강의 내부 API가 키 없이 401로 응답해 Service·NetworkPolicy를 확인했다(새 pod는 정책이 붙기까지 몇 초 걸리므로 `sleep 10` 뒤 요청한다). customer-support-app은 384Mi limit에서 기동 5분 뒤 RSS 353Mi라 448Mi로 올렸다(#527).
- 2026-11-02 기록(#541): member-app을 dev `6ec33161`(#526 쿼리 로그인 삭제 포함)로 `k3s-deploy.sh apply`(overlay 전체)로 올렸다. 적용 전 `kubectl diff`로 바뀌는 것이 Deployment 이미지뿐임을 확인했다. 기동 때 역할별 초기 계정 3개가 생겼다. `POST /api/v1/members/local-login`은 400(컨트롤러 있음)에서 401(컨트롤러와 permitAll 없음)로 바뀌었고, `admin@hapbang.local`의 비밀번호 로그인 → 재발급 → `/me`가 ADMIN으로 응답했다. 같은 날 lecture-app은 이미지를 바꾸지 않고 `lecture-app-swagger` HTTPRoute 하나만 적용해 강의 Swagger를 먼저 열었다(`GET /api/v1/lectures`는 404). 이 route는 아래 billing-app 배포 기록에서 강의 API route를 열며 지웠다.
- 2026-11-02 기록: chat-app을 dev `6ec33161`로 이 방식(NetworkPolicy → ConfigMap·Deployment)으로 처음 올리고 이미지 패키지도 이때 만들었다(#545). 로컬 `:chat-app:check`는 `ChatPipelineDeliveryE2ETest` 1개가 앞 테스트의 알림을 받아 두 번 다 실패했고 단독 실행은 통과해, 테스트 격리 문제로 보고 진행했다(#547). Flyway가 `chat` 스키마를 만들었고 `chat-app.member-replica`·`chat-app.appearance-notification`이 partition 3개씩을 받았다. 384Mi limit에서는 기동 직후 OOMKilled(5회 재시작)돼 512Mi로 올렸고, 기동 5분 뒤 working set 404Mi, 25분 뒤 430Mi였다. route는 공용 Gateway에 `x-user-token-exp` 헤더 제거(#534)를 먼저 적용한 뒤 Service·SecurityPolicy·BackendTrafficPolicy·HTTPRoute 순서로 열었다. 두 route가 Accepted·ResolvedRefs였고, 외부에서 토큰 없는 요청, 위조한 `X-User-Id`·`X-User-Role` 요청, 티켓 없는 WebSocket 업그레이드는 앱의 401로, 잘못된 Bearer는 Envoy의 401로 응답했다. 최소 배포 뒤 512Mi를 반영한 overlay와 운영의 diff는 없다.
- 2026-11-02 기록(#541): billing-app을 처음 올리고 강의·출연·고객센터 API route를 열었다. 직전에 #528이 member·appearance·lecture·customer-support를 dev `bb79d097`(관리 포트 분리)로 route 없이 올렸다(기록은 #528). `bb79d097`과 dev `fe610951`은 앱 overlay가 같고 코드는 출연(#554)만 달라, 네 앱은 이미지를 그대로 두었다.
  - billing-app(`fe610951`): NetworkPolicy를 먼저 적용하고 `k3s-deploy.sh apply`로 overlay 전체를 적용했다. Flyway가 빈 `billing` 스키마에 15개를 적용했고, `billing-app.member-replica`·`billing-app.wallet-creation`이 `member.events.v1`을 받았다. 이어서 관리자 토큰으로 Gateway를 거쳐 회원 Backfill(`POST /api/v1/settlements/admin/billing-users/backfill?afterId=0&limit=5000`)을 실행했다. 한 번에 `completed=true`였고, 15명 모두 가입 이벤트로 이미 반영돼 `ignoredStale`, `failed` 0이었다. 448Mi limit에서 기동 15분 뒤 working set은 391Mi였다.
  - chat-app(`fe610951`): 이미지만 바꿨다. 출연(`bb79d097`)이 발행하는 리뷰 공개 이벤트(#538) 클래스가 이전 chat 이미지(`6ec33161`)에 없어서다(ADR 0001, 새 타입은 소비 앱 먼저).
  - customer-support·lecture·appearance: `bb79d097` 그대로 overlay 전체를 적용했다. Deployment·ConfigMap·NetworkPolicy는 `unchanged`라 재시작이 없었고, Service(고객센터·출연)와 HTTPRoute·SecurityPolicy·BackendTrafficPolicy만 생겼다. 그 뒤 `lecture-app-swagger`를 지웠다.
  - 확인: 새 route는 모두 Accepted·ResolvedRefs였다. 6개 모듈 Swagger는 `swagger-ui.html`이 302 → `index.html` 200, `v3/api-docs`가 200이었고, 회원 Swagger 상단 목록 6개가 모두 열렸다. 앱마다 토큰 없는 요청과 위조한 `X-User-Id`·`X-User-Role` 요청은 앱의 401, 잘못된 Bearer는 Envoy의 401로 응답했다. 역할이 맞지 않으면 403이었고(관리자의 `/wallets/me`도 설계대로 403), `/api/v1/internal/**`는 외부에서 404였다. 레이블을 붙인 임시 pod에서 지갑 내부 API는 키 없이 401, 8080은 연결되지 않았다. Kafka DLT는 모두 비었고 소비 lag은 0이었다. 6개 앱 overlay와 운영의 diff는 없다.

### billing-app 첫 배포 (#552)

billing-app은 처음 뜰 때 기존 회원의 지갑 회원(회원 복제본)과 지갑이 없다. `member.events.v1`은 72시간만 보존되므로(`KAFKA_LOG_RETENTION_HOURS`) 그보다 먼저 가입한 회원의 가입 이벤트는 이미 없다. 이 상태로 route를 열면 기존 회원의 충전·환불·환전 신청이 404 `WALLET_MEMBER_NOT_FOUND`(#537)이고, 지갑이 없는 회원의 충전 주문과 강의·출연의 보류·지급이 `WALLET_NOT_FOUND`다. 그래서 route를 열기 전에 회원 Backfill을 실행한다. Backfill은 지갑 회원을 반영하면서 관리자가 아닌 회원의 지갑도 만든다(BD-132).

데이터 이관은 없다. Compose 운영 DB는 #440에서 지웠고, Flyway가 빈 `billing` database에 스키마를 처음부터 만든다([AWS k3s](aws-k3s.md#전환-순서) 9항).

1. **최소 배포**: [릴리스 전 배포](#릴리스-전-배포)의 최소 배포(NetworkPolicy → ConfigMap·Deployment) 순서로 올린다. Service·HTTPRoute는 아직 적용하지 않는다.
2. **기동 확인**: Flyway가 `billing` 스키마를 만들었는지, 소비 그룹 `billing-app.member-replica`·`billing-app.wallet-creation`이 `member.events.v1`의 partition 3개씩을 받는지 본다. RSS도 확인한다. Prometheus 지표와 관리 포트로 메모리가 늘 수 있다([관측성](../architecture/infrastructure/observability.md)).
3. **회원 Backfill**: 관리자 API도 Gateway route 뒤에 있으므로, route를 열기 전에는 port-forward로 외부 API 포트(8080)에 직접 붙어 관리자 사용자 헤더를 싣는다. 다른 터미널에서 `kubectl --context hapbang-k3s -n hapbang-prod port-forward deploy/billing-app 18080:8080`을 띄운다.

   ```bash
   # 관리자 회원 ID는 member-app의 실제 관리자 계정 ID를 쓴다. completed가 true가 될 때까지 nextAfterId를 afterId로 넘겨 반복한다.
   curl -sS -X POST "http://127.0.0.1:18080/api/v1/settlements/admin/billing-users/backfill?afterId=0&limit=5000" \
     -H "X-User-Id: <관리자 회원 ID>" -H "X-User-Role: ADMIN"
   ```

   - 응답의 `failed`가 0이고 `completed`가 true면 끝이다. `walletsEnsured`는 지갑을 확인한(없으면 만든) 관리자가 아닌 회원 수다. 실패가 있으면 처음부터(`afterId=0`) 다시 실행한다. 복제본은 회원 버전 비교로, 지갑은 회원당 1개 UNIQUE로 결과가 같다.
   - 503 `MEMBER_SOURCE_*`면 응답의 `resumeAfterId`부터 다시 실행한다. member-app 내부 API(8081)와 내부 API 키가 맞는지 확인한다.
   - port-forward는 NetworkPolicy와 Gateway를 거치지 않아 사용자 헤더를 검증하는 곳이 없다. prod kubeconfig가 있는 사람만 할 수 있고, 끝나면 바로 닫는다. 이 방식은 route를 열기 전 운영 작업에만 쓴다.
4. **확인**: `billing.billing_user` 행 수가 member-app 회원 수와 같고, `billing.wallet`(MEMBER)이 관리자를 뺀 회원 수와 같은지 본다.
5. **route 오픈**: billing-app의 Service·SecurityPolicy·BackendTrafficPolicy·HTTPRoute를 적용한다. 그 뒤 appearance-app route를 연다(#504 기록 참고).

- 2026-11-02 로컬 k3d 확인(#552): 개발 PC 메모리(15.8 GiB)로는 [로컬 k3d 검증](#로컬-k3d-검증)의 platform 전체를 올릴 수 없어, 메모리 제한을 뺀 2-node 클러스터(k3s v1.36.5)에 PostgreSQL·Kafka·Redis·Prometheus와 member-app·billing-app만 올렸다. Envoy Gateway CRD가 없어 앱은 NetworkPolicy → ConfigMap·Secret·Service·Deployment만 적용했다.
  - probe가 관리 포트(8082)에서 통과했다. 라벨 없는 pod에서 billing pod의 8080·8082는 둘 다 시간 초과로 막혔다. Prometheus target `http://<pod>:8082/actuator/prometheus`가 `up`이고 `hikaricp_connections_max`가 10이었다.
  - billing을 Kafka 리스너를 끈 상태(`SPRING_KAFKA_LISTENER_AUTO_STARTUP=false`)로 띄워 가입 이벤트를 놓친 기존 회원 6명(관리자 1명 포함)을 재현했다. 위 3단계대로 port-forward로 Backfill을 실행해 `scanned 6`·`created 6`·`walletsEnsured 5`·`failed 0`이었고, 지갑 회원 6명·지갑 5개(관리자 제외)가 생겼다. 다시 실행해도 `created 0`·`ignoredStale 6`이고 지갑은 5개 그대로였다.
  - 리스너를 켜고 다시 띄우자 두 소비 그룹이 partition 3개씩을 받아 이벤트를 끝까지 읽었다(lag 0, ERROR 0). 지갑 회원·지갑 수는 그대로였다. 새 Kafka에서 처음 합류할 때 `NotCoordinatorException` 재시도가 1초쯤 이어졌다(`__consumer_offsets` 생성 중).
  - billing-app 메모리는 기동 1분과 리스너를 켠 뒤 모두 376~380Mi(limit 448Mi), 재시작 0이었다. 여유가 약 70Mi라 prod에서 RSS를 더 오래 지켜본다.
  - 로컬 Secret에 Toss 결제 키가 없어 충전 주문은 지갑 회원 확인 전에 503 `CHARGE_UNAVAILABLE`이었다. 지갑 회원 확인은 이 환경에서 따로 보지 않았다(통합 테스트로 검증).

### billing-app 회원 이벤트 DLT 복구 (#564)

billing-app은 `member.events.v1`을 두 소비 그룹으로 받는다. 처리에 실패하면 1초부터 최대 10초까지 3번 재시도한 뒤 그룹별 DLT로 보내고, 읽을 수 없는 메시지(역직렬화 실패)는 재시도 없이 바로 보낸다(`BillingKafkaConfig`). DLT로 보낸 뒤에는 offset을 커밋해 뒤 메시지는 계속 처리된다. DLT는 7일 보존한다.

| 그룹 | DLT | 이 그룹이 만드는 데이터 |
| --- | --- | --- |
| `billing-app.member-replica` | `member.events.v1.billing-app.member-replica.DLT` | 지갑 회원(`billing.billing_user`) |
| `billing-app.wallet-creation` | `member.events.v1.billing-app.wallet-creation.DLT` | 회원 지갑(`billing.wallet`) |

DLT 메시지를 원래 리스너에 다시 넣는 도구는 두지 않는다(BD-136). 두 그룹이 만드는 데이터는 관리자 회원 Backfill이 회원 앱의 현재 정보로 다시 맞추고(지갑 회원은 회원 버전 비교, 지갑은 회원당 1개), 다시 실행해도 결과가 같기 때문이다(BD-123·BD-132).

1. **DLT 확인**: 메시지가 있는지와 실패 원인을 본다. 헤더 `kafka_dlt-exception-fqcn`·`kafka_dlt-exception-message`가 예외, `kafka_dlt-original-topic`·`kafka_dlt-original-consumer-group`이 원래 위치이고, key는 회원 ID다. `kafka_dlt-exception-stacktrace` 헤더는 길어서 아래처럼 필요한 것만 고른다.

   ```bash
   # 파티션별 마지막 offset. 0이 아니면 메시지가 있다
   kubectl --context hapbang-k3s -n hapbang-platform exec kafka-0 -- sh -c      '/opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic member.events.v1.billing-app.member-replica.DLT'
   # 헤더·key·값. 읽기만 하며 소비 그룹을 쓰지 않는다
   kubectl --context hapbang-k3s -n hapbang-platform exec kafka-0 -- sh -c      '/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092         --topic member.events.v1.billing-app.member-replica.DLT --from-beginning --timeout-ms 8000         --formatter-property print.headers=true --formatter-property print.key=true'      | tr ',' '
' | grep -aE '^kafka_dlt-(exception-fqcn|exception-message|original-topic|original-consumer-group)'
   ```

2. **원인 수정**: 역직렬화 실패면 발행 앱(member-app)과 계약(`shared.member.event`)을, 처리 실패면 billing-app 로그(같은 시각의 예외)와 DB 상태를 본다. 고치는 배포가 필요하면 먼저 배포한다.
3. **Backfill로 다시 맞춘다**: route가 열려 있으면 관리자 토큰으로 Gateway를 거쳐, 열기 전이면 [billing-app 첫 배포](#billing-app-첫-배포-552) 3단계처럼 port-forward로 `POST /api/v1/settlements/admin/billing-users/backfill`을 실행한다. `failed` 0·`completed` true면 끝이다. 실패 회원은 `failedIds`에 있다.
4. **확인**: 실패한 메시지의 key(회원 ID)가 `billing.billing_user`와 `billing.wallet`(관리자 제외)에 있는지 본다. DLT 메시지는 지우지 않는다. 7일 뒤 보존 기간이 지나면 사라진다.

- 회원 이벤트 중 지갑이 쓰는 것은 가입·변경뿐이다. 로그아웃 등 다른 이벤트는 리스너가 무시하므로(`@KafkaHandler(isDefault = true)`) DLT로 가지 않는다.
- 2026-11-02 로컬 k3d 확인(#564, 위 [billing-app 첫 배포](#billing-app-첫-배포-552)와 같은 최소 구성): 가입한 회원 4를 key로 읽을 수 없는 메시지(`{"broken":`)를 `member.events.v1`에 넣자 두 그룹의 DLT partition 1에 한 건씩 들어갔다. 헤더는 `DeserializationException`·`failed to deserialize`·원래 토픽·그룹이었고 두 그룹 lag은 0으로 막히지 않았다. 회원 4의 지갑 회원·지갑 행을 지워 반영 실패를 흉내 낸 뒤 Backfill을 실행하자 `created 1`·`walletsEnsured 3`·`failed 0`이었고 두 행이 다시 생겼다.

### dev 자동 배포 전환

2026-11-02 #561(#559)로 배포 브랜치를 main에서 dev로 바꿨다. 같은 날 Environment `prod`의 허용 브랜치와 레포 기본 브랜치도 `dev`로 바꿨다. 전환 때 업무 앱 6개는 [릴리스 전 배포](#릴리스-전-배포)로 모두 떠 있었고 route도 열려 있어, 첫 run이 새로 연 route는 없다.

첫 run은 세 번째에 배포까지 끝났다.

| run | 커밋 | 결과 |
| --- | --- | --- |
| 41409954330 | `e6e43b0`(#561) | chat-app 테스트 job이 Maven Central 403으로 의존성을 받지 못해 실패했다. dev가 기본 브랜치가 된 뒤 처음 도는 run이라 Gradle 캐시가 없어, Gradle job 7개가 의존성을 동시에 받았다. 실패한 job을 다시 돌려 테스트·발행은 성공했지만, 그사이 #565가 병합돼 배포는 "dev 최신이 아니다"로 건너뛰었다 |
| 41410678543 | `9639d7aa`(#565) | `ChatRedisLongOutageE2ETest`가 가끔 실패하는 문제로 chat-app 테스트가 실패했다(죽인 Redis가 0.5초 만에 같은 포트로 다시 응답). 다시 돌려 발행까지 성공했지만, deploy job이 `configure-aws-credentials`에서 실패했다. 호출된 workflow에 Environment secret을 넘기지 않아 `role-to-assume`이 빈 값이었다(#570) |
| 41414306223 | `1d891a95`(#571) | 이미지가 모두 있어 테스트·발행을 건너뛰고, 6개 앱을 member → billing → appearance → lecture → chat → customer-support 순서로 배포했다. 08:38~08:43 UTC에 모두 `successfully rolled out`, 재시작 0 |

- 6개 앱 모두 배포된 이유: #561에 함께 넣은 테스트 수정이 `common/shared` 아래라 모든 앱의 태그 커밋이 바뀌었다. 이미지는 billing-app이 `sha-9639d7a`(#565가 `apps/billing-app/` 문서를 바꿈), 나머지가 `sha-e6e43b0`이다.
- 배포 5~10분 뒤(08:48 UTC) working set은 member 368Mi(limit 512Mi), billing 388Mi(448Mi), appearance 459Mi(576Mi), lecture 524Mi(640Mi), chat 425Mi(512Mi), customer-support 354Mi(448Mi)였다. node 메모리는 61%·68%였다. billing이 limit의 87%로 가장 높아 계속 지켜본다.
- 외부에서 6개 모듈의 `/api/v1/<모듈>/v3/api-docs`가 모두 200이었다.
- 이제 운영 이미지는 모두 Actions가 검증한 산출물로 만든 것이다. 사람이 올린 이미지는 남아 있지 않다. 산출물은 JVM 앱이 테스트한 jar(`Dockerfile`), customer-support-app이 2026-11-04부터 Native Image 실행 파일(`Dockerfile.native`, #609)이다.

## 로컬 k3d 검증

EC2에는 prod만 있으므로 manifest는 로컬 k3d에서 먼저 띄워 본다. 실제 로그인·결제·S3 업로드는 하지 않고, pod 기동과 node별 메모리만 확인한다.

### 준비

- Docker Desktop 메모리를 14 GiB 이상으로 둔다. k3d node 두 개에 EC2와 같은 메모리 제한(7,600 MiB·3,700 MiB)을 건다.
- k3d v5.9.0, kubectl이 필요하다.

```bash
k3d cluster create --config k8s/local/k3d.yaml
kubectl get nodes -L hapbang.io/pool       # server: core-data, agent: app-observe

# 앱 이미지를 빌드해 cluster에 넣는다. 로컬은 customer-support-app도 JVM Dockerfile을 쓴다(native 이미지는 운영만, #609)
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
| node 배치 | `kubectl get pods -A -o wide` | Kafka·Redis·Elasticsearch·PostgreSQL·member·billing·Envoy Gateway controller·ECK operator·Kafka UI·cert-manager·Sealed Secrets는 server, 관측성·chat은 agent |
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
- `/kafka/`에서는 토픽 생성이 HTTP 405로 실패한다. Console 프론트엔드가 생성 요청을 base path 없이 보내는 upstream 문제라 고치지 않는다(원인과 결정은 [Gateway 구성](gateway.md), #484). 토픽 생성은 port-forward로 한다: `kubectl --context hapbang-k3s -n hapbang-platform port-forward svc/redpanda-console 18080:8080` 뒤 `http://localhost:18080`.

## CI

`ci.yml`의 `manifests` job이 PR마다 `k8s/`와 `apps/<앱>/k8s`의 overlay별로 `kubectl kustomize` → `kubeconform -strict`를 실행한다. 필수 체크 `verify`가 이 결과도 모으므로 실패하면 병합할 수 없다. dev 발행(`publish.yml`)도 이 job이 실패하면 build·deploy를 건너뛴다(#792). GitHub에서 받는 파일(kubeconform, Gateway·인증서 CRD schema, kubeconform이 실행 중 받는 Kubernetes·CRDs-catalog schema)은 Actions cache에 두고, 버전(kubeconform·Kubernetes·CRDs-catalog 커밋·`prepare-schemas.py`)이 같으면 다시 받지 않는다. cache가 없을 때 받는 다운로드는 5xx·끊김을 몇 번 다시 시도한다(2026-11-09 GitHub 504로 발행이 멈춘 적이 있다, #792). cache는 job이 성공해야 저장되고, dev 실행이 만든 cache를 PR이 읽는다. 그래도 GitHub 장애로 실패하면 그 job만 다시 실행한다. 렌더링 step은 `shell: bash`(pipefail)라 `kubectl kustomize`가 실패하면 job이 실패한다. Kubernetes schema는 k3s와 같은 1.36.5, CRD(HelmChart·Elasticsearch) schema는 [CRDs-catalog](https://github.com/datreeio/CRDs-catalog)의 고정 커밋을 쓴다.

kubeconform은 형식만 검사한다. `valuesContent` 안의 차트 values, Service selector와 Pod label의 일치, 이미지 존재, 앱 기동은 검사하지 못하므로 manifest를 바꾸면 로컬 k3d로 확인한다.

`-strict`는 Kubernetes 기본 리소스와 HelmChart의 모르는 필드(오타)를 잡는다. Elasticsearch CR은 CRDs-catalog schema가 모르는 필드를 허용해 `spec` 아래 오타를 잡지 못한다(2026-10-29 확인).

PR마다 k3d에 적용하는 smoke는 앱이 모두 단독 기동한 뒤 더한다.

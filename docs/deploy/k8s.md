# k8s manifest

상태: EC2 k3s에 `cluster`·`platform`(2026-10-29, #374)과 Envoy Gateway·cert-manager(2026-10-30, #372)를 적용했다. 외부 진입은 ServiceLB → Envoy이며 업무 앱·FE는 아직 미배포다. 기존 Compose 데이터는 보존하고 edge nginx·certbot은 중지했다([배포 개요](README.md)).

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

- `postgres-credentials`의 앱별 값은 각 앱 Secret의 `DB_PASSWORD`와 같아야 한다. PostgreSQL이 첫 기동 때 이 값으로 앱 role을 만든다.
- `HAPBANG_INTERNAL_API_KEY`는 모든 앱이 같은 값을 쓴다.

앱 SealedSecret은 `k8s/tools/seal-app-secrets.sh`로 만든다. 값은 화면에 출력하지 않는다.

- `DB_PASSWORD`는 EC2 cluster의 `postgres-credentials`에서 읽고, `HAPBANG_INTERNAL_API_KEY`는 `~/.hapbang/internal-api-key`를 쓴다. 파일이 없으면 멈추고, 처음 설치할 때만 앱 이름 없이 `HAPBANG_NEW_INTERNAL_API_KEY=1`로 실행해 새 키로 앱 6개를 함께 봉인한다. 빈 `DB_PASSWORD`, billing 결제 키 한쪽만 있는 경우도 멈춘다.
- 그 밖의 값은 레포 밖 `~/.hapbang/prod-secrets.env`에 `KEY=VALUE`로 적는다. 서버 `prod.env`의 줄을 그대로 옮기면 되고, 값 전체를 감싼 따옴표 한 쌍은 뗀다. 필수: `S3_BUCKET`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `CHZZK_CLIENT_ID`, `CHZZK_CLIENT_SECRET`, `CHZZK_REDIRECT_URI`. 선택: `TOSS_PAYMENTS_SECRET_KEY`, `BILLING_PAYMENT_KEY_ENCRYPTION_KEY`, `BILLING_PAYMENT_KEY_HMAC_KEY`.
- 필수 값이 없으면 키 이름만 알리고 멈춘다. 만든 파일은 각 앱의 `overlays/prod/<앱>-app-secrets.yaml`이고 kustomization `resources`에 더한다.
- `~/.hapbang`의 파일은 팀 비밀 보관소에도 둔다. 내부 API 키를 다시 만들면 앱 6개를 모두 다시 봉인한다.

앱 이미지(`ghcr.io/prgrms-be-adv-devcourse/beadv8_8_lms_be/<앱>`)는 공개 패키지로 두어 pull secret을 쓰지 않는다. 레포가 공개라 이미지 공개로 더 드러나는 정보가 없다. GHCR 패키지는 처음 발행될 때 비공개이고 레포 공개 여부를 이어받지 않으므로, 앱마다 첫 발행 뒤 패키지 페이지 → Package settings → Change visibility → Public으로 바꾼다. 한 번 공개하면 되돌릴 수 없다. org의 Settings → Packages → Package Creation에서 Public이 막혀 있으면 org owner가 먼저 허용한다.

Sealed Secrets controller 키를 잃으면 위 SealedSecret을 모두 다시 만들어야 한다. 백업과 복구는 [AWS 2노드 k3s 배치](aws-k3s.md#sealed-secrets-키-백업)를 따른다.

## PostgreSQL database

PostgreSQL은 앱들이 함께 쓰는 인프라라 `hapbang-platform`에 둔다. 그래서 앱 배포가 PostgreSQL을 건드리지 않는다. instance 1개에 앱마다 database와 같은 이름의 소유 role을 두고 `REVOKE CONNECT ... FROM PUBLIC`으로 다른 role의 접속을 막는다([10장](../architecture/infrastructure/database.md)). 앱의 Flyway가 database 안에 자기 schema를 만든다.

- database·role은 차트의 initdb script(`k8s/platform/base/postgres.yaml`)가 **데이터 디렉터리가 비어 있을 때만** 만든다.
- 이미 초기화된 instance에 database를 더할 때(notification·search 앱 추가 등)는 script를 고치고, 실행 중인 instance에도 같은 SQL을 superuser로 실행한다.
- 기존 Compose 운영 DB의 데이터 이전은 #338이다.

## 적용 순서

`cluster`가 설치하는 CRD(Elasticsearch, SealedSecret)가 있어야 `platform`·prod overlay가 적용된다. HelmChart는 helm-controller가 Job으로 설치하므로 CRD가 생길 때까지 기다린다.

```bash
kubectl apply -k k8s/cluster
kubectl wait --for=create crd/elasticsearches.elasticsearch.k8s.elastic.co --timeout=5m
kubectl wait --for=condition=Established crd/elasticsearches.elasticsearch.k8s.elastic.co

kubectl apply -k k8s/platform/overlays/local     # prod: overlays/prod

# 앱은 하나씩 적용한다. prod는 배포 workflow(#374)가 앱마다 overlays/prod를 적용한다.
for app in apps/*-app; do kubectl apply -k "$app/k8s/overlays/local"; done
```

## 운영 앱 배포

prod 앱은 GitHub Actions가 배포한다(#374). 앱 이미지는 공개 패키지라 cluster에 pull secret이 없다. 앱마다 첫 발행 뒤 패키지를 Public으로 바꾸기 전에는 배포가 이미지 pull에서 실패하고 되돌려진다([Secret](#secret) 아래 이미지 공개 설명).

```
main push → Publish images(publish.yml)
  바뀐 앱만 테스트(ci.yml) → ghcr.io/prgrms-be-adv-devcourse/beadv8_8_lms_be/<앱>:sha-<그 앱을 마지막으로 바꾼 main 커밋>

Actions → Deploy(deploy.yml) → Run workflow(main), 입력 sha(비우면 main 최신)·apps(비우면 자동)
  지금 도는 이미지와 비교해 바뀐 앱을 고른다 → 이미지가 모두 GHCR에 있는지 확인
  → 앱마다 순서대로(member → billing → appearance → lecture → chat → customer-support)
     SSM Run Command로 large에서 .github/scripts/k3s-deploy.sh apply
       해당 커밋의 apps/<앱>/k8s/overlays/prod를 받아 이미지 태그를 넣고 apply → rollout status
       실패하면 그 앱만 rollout undo, 남은 앱은 배포하지 않음
```

- **바뀐 앱**: `apps/<앱>/`가 바뀌면 그 앱, `common/`·`gradle/`·루트 `build.gradle.kts`·`settings.gradle.kts`가 바뀌면 전체다. 앱 목록과 배포 순서는 `.github/scripts/apps.sh` 한 곳에 있고, `apps/*-app`과 다르면 CI(`verify`)가 실패한다. 새 앱은 여기에 추가한다.
- **롤백**: Deploy를 `apps`에 앱, `sha`에 되돌릴 main 커밋을 넣어 실행한다. `rollout undo`는 Deployment만 되돌리고 ConfigMap 외의 리소스(HTTPRoute·Secret 등)는 되돌리지 않는다.
- **cluster 접근**: GitHub OIDC로 IAM role을 받아 SSM Run Command로 large에서 root로 `k3s kubectl`을 실행한다. 6443과 SSH를 쓰지 않는다. role trust는 GitHub Environment `prod`의 job만 허용하고, `prod`는 `main` 브랜치만 허용한다. `prod` Environment Secrets는 `AWS_DEPLOY_ROLE_ARN`, `DEPLOY_INSTANCE_ID`다.
  - 이 레포는 GitHub OIDC의 immutable subject를 쓴다. trust의 `sub`는 `repo:<org>@<org id>/<repo>@<repo id>:environment:prod` 형식이라 레포를 옮기거나 다시 만들면 바꿔야 한다.
  - large의 인스턴스 역할에 `AmazonSSMManagedInstanceCore`가 있어야 SSM agent가 등록된다.
- 공개 레포라 Actions 로그도 공개다. 배포 스크립트는 node 이름·IP가 나오는 출력(`-o wide`, `describe`)을 하지 않는다.
- `k8s/cluster`·`k8s/platform`·`k8s/gateway`는 자동 배포하지 않는다.
- 서버 스크립트는 로컬 k3d에서 시험할 수 있다: `KUBECTL="kubectl --context k3d-hapbang" ROLLOUT_TIMEOUT=60s .github/scripts/k3s-deploy.sh apply <앱> <GitHub에 있는 커밋> <태그 커밋>`. 시험 뒤 `apps/<앱>/k8s/overlays/local`을 다시 적용한다.

### 첫 릴리스

main은 2026-10-28 이후 병합되지 않아 Publish images가 main에 없고, 앱 이미지 패키지도 아직 없다. 처음 한 번은 아래 순서로 한다. 이후 릴리스는 1·2·5단계만 한다.

1. **dev → main 병합**: base `main` ← `dev`(또는 `release/review-N`) PR을 만들어 병합한다. 다른 브랜치에서 열면 `PR source / source-branch` 체크가 실패한다(#180).
2. **발행 확인**: Actions → Publish images의 main run이 성공했는지 본다. 발행한 앱은 run 요약의 "발행 대상"에 있다. 실패하면 원인을 고쳐 다시 병합한다.
3. **패키지 공개(앱마다 처음 한 번)**: org Settings → Packages → Package Creation에서 Public이 허용됐는지 org owner가 먼저 확인한다. 그다음 `https://github.com/orgs/prgrms-be-adv-devcourse/packages/container/package/beadv8_8_lms_be%2F<앱>`(예: `member-app`) → Package settings → Danger Zone → Change visibility → Public으로 바꾼다. 확인 칸에는 `beadv8_8_lms_be/<앱>`을 넣는다. 되돌릴 수 없다. 이미지는 `apps/<앱>/build/libs`의 테스트한 jar만 담고, 비밀값은 실행할 때 Secret으로 주입하므로 이미지에 들어가지 않는다. 주소가 404면 그 앱은 아직 발행되지 않았다.
4. **공개 확인**: 로그인 없이 태그 목록을 읽을 수 있으면(200) 공개된 것이다. 401·403·404는 비공개이거나 발행 전이다.

   ```bash
   for app in member-app billing-app appearance-app lecture-app chat-app customer-support-app; do
     t=$(curl -s "https://ghcr.io/token?scope=repository:prgrms-be-adv-devcourse/beadv8_8_lms_be/$app:pull" | jq -r '.token // empty')
     echo "$app $(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $t" "https://ghcr.io/v2/prgrms-be-adv-devcourse/beadv8_8_lms_be/$app/tags/list")"
   done
   ```

5. **배포**: Actions → Deploy → Run workflow(main)를 입력 없이 실행한다. 앱 SealedSecret은 `hapbang-prod`에 이미 있다([Secret](#secret)).

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

## CI

`ci.yml`의 `manifests` job이 PR마다 `k8s/`와 `apps/<앱>/k8s`의 overlay별로 `kubectl kustomize` → `kubeconform -strict`를 실행한다. Kubernetes schema는 k3s와 같은 1.36.5, CRD(HelmChart·Elasticsearch) schema는 [CRDs-catalog](https://github.com/datreeio/CRDs-catalog)의 고정 커밋을 쓴다.

kubeconform은 형식만 검사한다. `valuesContent` 안의 차트 values, Service selector와 Pod label의 일치, 이미지 존재, 앱 기동은 검사하지 못하므로 manifest를 바꾸면 로컬 k3d로 확인한다.

`-strict`는 Kubernetes 기본 리소스와 HelmChart의 모르는 필드(오타)를 잡는다. Elasticsearch CR은 CRDs-catalog schema가 모르는 필드를 허용해 `spec` 아래 오타를 잡지 못한다(2026-10-29 확인).

PR마다 k3d에 적용하는 smoke는 앱이 모두 단독 기동한 뒤 더한다.

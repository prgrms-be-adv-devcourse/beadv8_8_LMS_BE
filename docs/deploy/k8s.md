# k8s manifest

상태: manifest 작성(#373). EC2 k3s에는 아직 적용하지 않았고, 운영은 Docker Compose다([배포 개요](README.md)).

앱들이 함께 쓰는 인프라는 레포 루트의 `k8s/`, 앱 manifest는 앱마다 `apps/<앱>/k8s/`에 두고 로컬 k3d와 EC2 k3s에 같은 구성으로 적용한다. 구조와 설치 방식을 고른 근거는 [최종 인프라 설계 3장](../architecture/infrastructure/k3s.md#manifest-관리--kustomize와-helm), node 배치는 [AWS 2노드 k3s 배치](aws-k3s.md)를 따른다.

## 구조

| 경로 | namespace | 내용 | 적용 |
| --- | --- | --- | --- |
| `k8s/cluster` | kube-system 외 | namespace, `local-path-retain` StorageClass, HelmChart(ECK operator, Sealed Secrets) | 수동 |
| `k8s/components/core-data` | - | `hapbang.io/pool: core-data` label에 large required node affinity를 붙이는 Kustomize Component | platform이 사용 |
| `k8s/platform/{base,overlays/local,overlays/prod}` | hapbang-platform | PostgreSQL(HelmChart), Kafka·Redis(StatefulSet), Elasticsearch(ECK CR과 nori Dockerfile), 관측성 HelmChart 5개 | 수동 |
| `apps/<앱>/k8s/{base,overlays/local,overlays/prod}` | hapbang-prod | 앱 Deployment·Service·ConfigMap. overlay는 이미지와 Secret | prod는 앱마다 배포 workflow(#374) |
| `k8s/local/k3d.yaml` | - | 로컬 k3d cluster 설정 | - |

- **앱은 앱 디렉터리만으로 배포할 수 있게 둔다.** 모노레포지만 레포를 나눌 수 있어야 하므로(AGENTS.md 3장) 앱 manifest는 Dockerfile 옆에 두고 루트 `k8s/`를 참조하지 않는다. 앱이 공용 인프라를 아는 것은 `<앱>-config`의 접속 주소(`*.hapbang-platform.svc.cluster.local`)뿐이다.
- 앱은 자기 node 배치(affinity), limit, 배포 전략을 직접 적는다. 앱 전체의 node별 합계는 [자원 예산](../architecture/infrastructure/k3s.md#ec2-2노드-자원-예산)과 아래 메모리 예산 확인으로 맞춘다.
- Helm CLI는 쓰지 않는다. 차트는 k3s 내장 helm-controller의 `HelmChart`(`helm.cattle.io/v1`)로 설치하고 values는 `valuesContent`에 적는다([k3s Helm](https://docs.k3s.io/add-ons/helm)).
- platform의 StatefulSet은 `hapbang.io/pool: core-data` label을 붙이면 component가 affinity를 넣는다. HelmChart와 Elasticsearch CR에는 component가 닿지 않으므로 values·`podTemplate`에 같은 affinity를 직접 적는다.
- 앱은 지금 있는 6개만 둔다. notification·search는 각 앱 티켓에서 더한다. appearance·lecture·customer-support는 다른 앱 프로세스에만 있는 Port 구현 때문에 단독으로 기동하지 못해 `replicas: 0`이다(#280).
- Envoy Gateway·cert-manager·HTTPRoute·SecurityPolicy·NetworkPolicy는 #372에서 더한다. 그때까지 Kafka·Redis는 인증 없이 cluster 안에서만 열린다.

## 버전

| 구성 요소 | 차트·이미지 | 버전 |
| --- | --- | --- |
| k3s | `rancher/k3s` (k3d), EC2 설치 | v1.36.5+k3s1 |
| ECK operator | helm.elastic.co `eck-operator` | 3.5.0 |
| Elasticsearch | `k8s/platform/base/elasticsearch/Dockerfile` (공식 이미지 + analysis-nori) | 9.5.5 |
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
| `ghcr-pull` | hapbang-prod | `.dockerconfigjson` | 앱마다 두지 않고 namespace에 하나(#374) |

- `postgres-credentials`의 앱별 값은 각 앱 Secret의 `DB_PASSWORD`와 같아야 한다. PostgreSQL이 첫 기동 때 이 값으로 앱 role을 만든다.
- `HAPBANG_INTERNAL_API_KEY`는 모든 앱이 같은 값을 쓴다.

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

## 로컬 k3d 검증

EC2에는 prod만 있으므로 manifest는 로컬 k3d에서 먼저 띄워 본다. 실제 로그인·결제·S3 업로드는 하지 않고, pod 기동과 node별 메모리만 확인한다.

### 준비

- Docker Desktop 메모리를 14 GiB 이상으로 둔다. k3d node 두 개에 EC2와 같은 메모리 제한(7,600 MiB·3,700 MiB)을 건다.
- k3d v5.9.0, kubectl이 필요하다.

```bash
k3d cluster create --config k8s/local/k3d.yaml
kubectl get nodes -L hapbang.io/pool       # server: core-data, agent: app-observe

# 앱·Elasticsearch 이미지를 빌드해 cluster에 넣는다
./gradlew bootJar
for app in apps/*-app; do
  docker build -t "hapbang/$(basename "$app"):local" -f "$app/Dockerfile" "$app/build/libs"
done
docker build -t hapbang/elasticsearch-nori:9.5.5 k8s/platform/base/elasticsearch
k3d image import -c hapbang hapbang/elasticsearch-nori:9.5.5 \
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

## CI

`ci.yml`의 `manifests` job이 PR마다 `k8s/`와 `apps/<앱>/k8s`의 overlay별로 `kubectl kustomize` → `kubeconform -strict`를 실행한다. Kubernetes schema는 k3s와 같은 1.36.5, CRD(HelmChart·Elasticsearch) schema는 [CRDs-catalog](https://github.com/datreeio/CRDs-catalog)의 고정 커밋을 쓴다.

kubeconform은 형식만 검사한다. `valuesContent` 안의 차트 values, Service selector와 Pod label의 일치, 이미지 존재, 앱 기동은 검사하지 못하므로 manifest를 바꾸면 로컬 k3d로 확인한다.

`-strict`는 Kubernetes 기본 리소스와 HelmChart의 모르는 필드(오타)를 잡는다. Elasticsearch CR은 CRDs-catalog schema가 모르는 필드를 허용해 `spec` 아래 오타를 잡지 못한다(2026-10-29 확인).

PR마다 k3d에 적용하는 smoke는 앱이 모두 단독 기동한 뒤 더한다.

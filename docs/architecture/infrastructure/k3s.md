# 3. k3s on EC2 + Kustomize·Helm — 실제 서버의 Kubernetes 배포

상위 문서: [최종 프로젝트 인프라 설계](../final-project-infrastructure.md). 결정 기록은 #369다.

## k3s와 k3d의 역할

k3d는 k3s와 다른 배포판이 아니다. 같은 k3s를 Docker container 안에서 실행하는 wrapper다([k3d 공식 문서](https://k3d.io/)). 두 도구는 실행 위치에 따라 나눈다.

| 위치 | 도구 | 이유 |
| --- | --- | --- |
| EC2 large + medium | **k3s** server 1 + agent 1 | Ubuntu host에 직접 설치하고 systemd로 기동한다. node 역할은 [AWS 2노드 k3s 배치](../../deploy/aws-k3s.md)를 따른다 |
| 개발 PC (macOS) | k3d | k3s는 Linux용이다([k3s requirements](https://docs.k3s.io/installation/requirements)). k3d는 Docker만 있으면 같은 manifest를 띄운다. server 1(`core-data`) + agent 1(`app-observe`)에 EC2 node의 메모리 제한을 걸어 node별 예산까지 확인한다([k8s manifest](../../deploy/k8s.md)) |
| CI | 렌더링·schema 검사 | 지금은 `kubectl kustomize`와 kubeconform만 돈다(#373). PR마다 k3d에 적용하는 smoke/E2E는 앱이 모두 단독 기동한 뒤(#280·#339) 더한다 |
| node 장애 실험 | EC2 2노드 + 로컬 k3d multi-node | EC2에서는 medium 중지와 large 중지의 서로 다른 영향을 기록하고, 3개 이상 server가 필요한 control-plane HA는 로컬 실험으로 구분한다 |

EC2 cluster는 server 1대와 agent 1대라 **HA가 아니다**. embedded-etcd HA에는 server node 3대 이상이 필요하다([k3s quick start](https://docs.k3s.io/quick-start), [HA 문서](https://docs.k3s.io/datastore/ha-embedded)). medium이 내려가면 large의 모듈 앱과 stateful workload는 남지만 medium의 모듈 앱과 관측성이 멈춘다. large가 내려가면 control plane, 진입점(Envoy proxy), PostgreSQL과 Elasticsearch가 함께 사라져 서비스가 중단된다. 이 차이를 숨기지 않고 pod 장애와 node 장애 시연 결과를 분리한다.

## EC2 2노드 자원 예산

아래 예산은 cgroup limit 기준의 시작값이며 실제 배포 뒤 `kubectl top`, container RSS, JVM GC와 CPU credit으로 갱신한다. EC2에는 prod 한 벌만 둔다. 모듈 앱은 8개이고 앱마다 replica 1개다. 앱은 두 node에 네 개씩 나누고, stateful workload는 large에 고정한다. Envoy·cert-manager·ECK operator 값은 차트 기본값을 낮춘 추정값이라 설치 뒤 다시 잰다.

| t3.large `core-data` | 예산 |
| --- | ---: |
| OS | 350 MiB |
| k3s server + containerd + system pod (Traefik 끔) | 1,650 MiB |
| PostgreSQL | 768 MiB |
| Kafka | 700 MiB |
| Redis | 64 MiB |
| Elasticsearch | 1,536 MiB |
| 모듈 앱 4개(member·billing·appearance·search) × 448 MiB | 1,792 MiB |
| Envoy proxy | 256 MiB |
| **합계** | **7,116 MiB** |

사용 가능 메모리를 약 7.6 GiB로 보면 약 0.65 GiB가 남는다. 이 여유는 rolling surge pod 1개(448 MiB), filesystem cache와 오차에 쓴다. large의 앱은 `maxSurge: 1`, `maxUnavailable: 0`으로 무중단 배포한다. Elasticsearch는 heap만이 아니라 mmap·direct memory를 사용하므로 1.5 GiB limit에서 색인과 hybrid RAG 부하를 다시 측정한다. search-app은 ES와 같은 node에 둔다.

| t3.medium `app-observe` | 예산 |
| --- | ---: |
| OS | 350 MiB |
| k3s agent + containerd | 400 MiB |
| 모듈 앱 4개(lecture·chat·customer-support·notification) × 384 MiB | 1,536 MiB |
| Envoy Gateway controller | 128 MiB |
| ECK operator | 256 MiB |
| cert-manager (controller·webhook·cainjector) | 150 MiB |
| Sealed Secrets controller (#373) | 64 MiB |
| React 정적 서버 | 64 MiB |
| Prometheus·Loki·Tempo·Grafana·Alloy | 1,032 MiB |
| **합계** | **3,980 MiB** |

사용 가능 메모리를 약 3.7 GiB로 보면 **약 0.3 GiB가 넘친다.** 관측성은 처음 600 MiB로 잡았지만 로컬 k3d idle 실측(#373)에서 Grafana 13(258 MiB)과 차트 기본 scrape의 Prometheus(292 MiB)가 그 limit에서 OOMKilled되어 실측에 여유를 더한 값으로 올렸다. 지금은 notification-app·Envoy·cert-manager가 아직 없어 실제로는 들어간다. 그 셋이 올라가기 전에 Prometheus scrape 범위와 보존 한도를 줄여 다시 맞춘다(#343). surge pod를 받을 여유가 없으므로 medium의 앱은 `maxSurge: 0`, `maxUnavailable: 1`로 배포하고 **배포하는 동안 그 앱이 잠깐 멈추는 것을 허용**한다. notification-app은 WebSocket을 다루는 chat-app과 같은 node에 둔다. 관측성에는 별도 backend를 추가하지 않는다.

앱 limit은 448 MiB로 시작한다. JVM heap은 limit의 60%(`MaxRAMPercentage=60`)로 둔다. 75%에서는 chat-app이 384 MiB limit에서 OOMKilled됐다(#373 로컬 k3d). 앱별 RSS와 GC를 k6 부하에서 재 보고, 버티는 앱은 384 MiB까지 낮춘다. 그래도 부족하면 notification-app과 search-app은 시연할 때만 띄운다. EC2 instance 상향은 AGENTS.md 8장 범위 밖이라 팀과 운영진에게 먼저 묻는다.

### 과거 단일-node 측정 기록

다음 측정값은 t3.large 단일 node 시절의 원자료다. 구성 요소별 request/limit 근거로만 사용하고, 아래 시나리오 합계와 권고를 현재 배치 결정으로 사용하지 않는다.

t3.large는 8 GiB지만 kernel이 일부를 예약하므로 당시 계산은 **사용 가능 약 7.6 GiB**로 두었다.

### 측정값

2026-10-22에 로컬 Docker에서 쟀다.
- 앱: 현재 `dev` 브랜치의 `app.jar`, local profile, 빈 DB.
- 부하: k6 20 VU로 강의 목록 조회·health·OpenAPI 요청을 60~90초. 요청 실패는 0건이었다.
- 앱 JVM 옵션은 dev compose와 같다(`-XX:MaxRAMPercentage=75`).

| 구성 요소 | 대기 | 부하 후 | 비고 |
| --- | --- | --- | --- |
| Spring 앱, 제한 1 GiB | 670 MiB | 904 MiB | cgroup peak 984 MiB. 제한 가까이까지 늘어난다 |
| Spring 앱, 제한 768 MiB | - | 760 MiB | OOM 없음 |
| Spring 앱, 제한 512 MiB | - | 506 MiB | OOM 없음. 시작 7.7초 |
| PostgreSQL 18 | 63 MiB | 65 MiB | 데이터가 거의 없을 때 값 |
| Kafka 4.1 KRaft (heap 512 MiB) | 309 MiB | 315 MiB | Kafka에는 부하 없음 |
| Redis 8.2 | 12 MiB | 11 MiB | |
| Alloy 1.10 | 108 MiB | - | metrics 측정 때 값 |
| Prometheus 3.5 | 42 MiB | - | 3,613 series |
| Loki 3.5 / Tempo 2.8 / Grafana 12.2 | 55 / 33 / 112 MiB | 66 / 34 / 110 MiB | 수집 데이터 없음 |
| Traefik 3.5 | 14 MiB | 16 MiB | route 없음 |

측정에서 얻은 결론은 둘이다.
- **JVM pod는 limit 근처까지 메모리를 쓴다.** 부하가 가벼워도 heap 상한까지 늘어나므로, 예산은 사용량이 아니라 limit으로 잡는다.
- **현재 앱은 512 MiB로도 돈다.** 다만 빈 DB에 가벼운 조회만 한 결과다. Kafka client, Redis client, Spring AI가 들어가면 metaspace와 heap이 늘어나므로 최종 구성에서 다시 잰다.

k3s 자체는 측정하지 않고 공식 resource profiling 값을 쓴다. single-node server(SQLite)와 가벼운 workload에서 k3s와 containerd가 p95 **1,596 MiB**를 썼다. 이 값에는 OS와 pod가 들어가지 않는다([k3s resource profiling](https://docs.k3s.io/reference/resource-profiling)).

참고로 지금 prod compose의 `app`에는 `mem_limit`가 없다. 그래서 JVM 기본값(전체 메모리의 25%, 약 1.9 GiB)까지 heap이 커질 수 있다. 세미 프로젝트 서버에서도 prod 앱이 얼마나 쓰는지 `docker stats`로 확인할 필요가 있다.

### 과거 단일-node 예산

예산은 측정값에 여유를 더한 값이다. JVM은 limit으로 잡는다.

| 구성 요소 | 예산 | 근거 |
| --- | --- | --- |
| OS (Ubuntu, systemd, sshd, SSM agent 등) | 350 MiB | 추정. 서버에서 확인 |
| k3s + containerd | 1,500 MiB | 공식 profiling 1,428~1,596 MiB |
| kube-system pod (CoreDNS, metrics-server, local-path, Traefik) | 100 MiB | Traefik 측정 16 MiB + 추정 |
| edge nginx (+ Docker daemon) | 100 MiB | edge nginx를 Docker로 계속 띄우면 dockerd가 남는다 |
| Kafka (heap 512 MiB) | 700 MiB | heap + non-heap. 측정 315 MiB는 부하 없는 값 |
| Redis | 50 MiB | 측정 11 MiB |
| Alloy / Prometheus / Loki / Tempo / Grafana | 200 / 300 / 150 / 150 / 150 MiB | Prometheus는 수만 series를 가정해 측정값의 약 7배 |
| **공용 platform 소계** | **3,750 MiB (3.7 GiB)** | prod·dev가 함께 쓴다 |
| 환경별 PostgreSQL | 512 MiB (prod) / 256 MiB (dev) | 현재 dev compose limit 512m |
| 환경별 React web (nginx) | 20 MiB | |
| Spring 앱 pod 1개 | limit 768 MiB 또는 512 MiB | |

### 과거 시나리오 A — prod만 단일 EC2에 올린다

prod는 앱 pod 3개, pod limit은 768 MiB다.

| 상태 | 합계 | 7.6 GiB 대비 여유 |
| --- | --- | --- |
| 평상시 | 3,750 + 532 + 768×3 = **6,586 MiB (6.4 GiB)** | 약 1.2 GiB |
| rolling update (surge pod 1개) | 6,586 + 768 = **7,354 MiB (7.2 GiB)** | 약 0.4 GiB |

**들어간다.** 남는 0.4 GiB는 page cache와 kubelet eviction 기준(기본 `memory.available<100Mi`)에 쓰인다. pod limit을 512 MiB로 낮추면 평상시 5.7 GiB, rolling 때 6.2 GiB로 여유가 1.4 GiB 이상이 된다. 앱 기능이 다 들어간 뒤 512 MiB로 버티는지 재 보고 정한다.

### 과거 시나리오 B — prod와 dev를 단일 EC2에 함께 올린다

dev는 앱 pod 1개와 작은 PostgreSQL로 둔다. Kafka·Redis·관측 stack은 prod와 함께 쓴다(topic·key prefix, namespace label로 구분).

| 구성 | 평상시 | rolling update | 판단 |
| --- | --- | --- | --- |
| prod pod 768 × 3, dev pod 768 × 1, dev DB 512 | **7,886 MiB (7.7 GiB)** | 8,654 MiB | **들어가지 않는다** |
| prod pod 768 × 3, dev pod 512 × 1, dev DB 256 | **7,374 MiB (7.2 GiB)** | 8,142 MiB | 평상시만 겨우 들어가고 배포할 수 없다 |
| 모든 pod 512 (prod 3 + dev 1), dev DB 256 | **6,606 MiB (6.5 GiB)** | 7,118 MiB | 들어간다. 단 512 MiB가 최종 기능에서 버티는지가 전제 |
| 위 + Kafka heap 256 MiB | 약 **6.2 GiB** | 약 6.7 GiB | rolling 때도 여유 약 0.9 GiB. PostgreSQL instance는 환경마다 1개를 유지한다([10장](database.md)) |

**pod limit을 512 MiB로 줄이지 않으면 두 환경은 함께 들어가지 않는다.** 함께 올리려면 다음을 모두 받아들여야 한다.
- 모든 Spring pod를 512 MiB로 줄인다. 최종 기능에서 OOM이나 GC 지연이 생기면 바로 넘친다.
- dev에는 replica 1개뿐이라, rolling update 시연은 prod에서만 한다.
- k6 부하 test는 공용 Kafka·DB·관측 stack을 같이 흔든다. prod에 영향이 없는 시간에만 한다.

### 과거 단일-node Elasticsearch 측정 (#234, 9장)

ES 9.1.5를 단일 노드로 띄워 대기 상태만 쟀다. 요청은 보내지 않았다.

| 조건 | 결과 |
| --- | --- |
| heap 512 MiB, 컨테이너 제한 1 GiB | 대기 918 MiB. nori 설치 후 939 MiB(cgroup peak 939 MiB) |
| heap 384 MiB, 컨테이너 제한 768 MiB | cluster green, OOM 없음, 766 MiB(제한까지 채움) |

ES도 JVM처럼 제한까지 메모리를 채운다. heap 밖에 Lucene의 mmap과 direct memory를 쓰기 때문이다. 그래서 예산은 제한값으로 잡는다. 권장은 1 GiB(heap 512 MiB)이고, 최소는 768 MiB(heap 384 MiB)다. 768 MiB는 대기 상태만 확인한 값이라 색인·검색 부하에서 다시 잰다. ES 공식 문서도 heap을 컨테이너 메모리의 50% 이하로 두라고 한다([JVM settings](https://www.elastic.co/docs/reference/elasticsearch/jvm-settings)).

| 시나리오 | ES | 평상시 | rolling update | 판단 |
| --- | --- | --- | --- | --- |
| A, pod 768 MiB | 1,024 | 7,610 MiB (7.4 GiB) | 8,378 MiB | **들어가지 않는다** |
| A, pod 512 MiB | 1,024 | 6,842 MiB (6.7 GiB) | 7,354 MiB (7.2 GiB) | 들어간다. rolling 때 여유 약 0.4 GiB |
| A, pod 512 MiB | 768 | 6,586 MiB (6.4 GiB) | 7,098 MiB (6.9 GiB) | 들어간다. 여유 약 0.7 GiB |
| B 최소 구성(위 마지막 행) | 1,024 | 7,380 MiB (7.2 GiB) | 7,892 MiB | **들어가지 않는다** |
| B 최소 구성 | 768 | 7,124 MiB (7.0 GiB) | 7,636 MiB (7.5 GiB) | 여유 약 0.1 GiB. 사실상 불가 |

이 결과는 Elasticsearch가 단일 t3.large의 다른 workload와 경쟁했을 때의 기준선이다. 현재 2노드에서는 Elasticsearch를 large에 두고 application·관측성 일부를 medium으로 분리하므로 위 합계를 그대로 적용하지 않는다.

Elasticsearch는 검색과 RAG만 담당한다. logs·metrics·traces를 Elasticsearch에 넣지 않아 검색 index와 telemetry ingest가 heap·CPU·disk를 경쟁하지 않게 한다.

### 현재 권고

**EC2 k3s에는 prod(main) 한 벌만 둔다. dev 서버는 없애고, 개발 확인은 로컬과 PR k3d에서 한다.** 앱 8개는 위 예산 표대로 두 node에 나눈다.

dev 서버가 없어지면 프론트가 dev 서버로 API를 확인하던 경로(#332)도 사라진다. 전환 일정과 로컬 확인 방법을 프론트와 먼저 합의한다. 로컬에서는 BE 앱을 compose나 k3d로 띄우고, 역할별 테스트 계정 로그인은 local profile에서만 켠다. 서버에 올리기 전에 `free -m`, `kubectl top`과 선택한 관측성 UI의 node memory로 이 표를 실측값으로 바꾼다.

CPU는 2 vCPU의 standard credit이다. JVM pod 여러 개가 동시에 시작하면 credit을 소모하고 시작이 느려진다. node 재시작 때는 앱 4개가 한꺼번에 뜨므로 startupProbe 여유를 넉넉히 둔다.

large의 50 GB에는 container image, PostgreSQL, Kafka log와 Elasticsearch 검색/RAG index가 들어간다. medium의 50 GB에는 application image와 Prometheus·Loki·Tempo data가 들어간다. 각 저장소에 크기·기간 retention을 두고 Compose에서 k3s로 넘어가는 기간에는 Docker와 containerd가 image를 따로 저장하므로 node별 디스크 여유를 먼저 확인한다.

## EC2 운영 방식

- **진입점**: nginx는 쓰지 않는다. Envoy Gateway가 유일한 진입점이다. Envoy proxy Service는 `LoadBalancer`이고, k3s 내장 ServiceLB가 node의 80·443을 Envoy로 연결한다. ServiceLB는 large에만 `svccontroller.k3s.cattle.io/enablelb=true` label을 붙여 large에서만 켠다. 이 label이 붙은 node가 하나도 없으면 모든 node가 후보가 된다([k3s networking 문서](https://docs.k3s.io/networking/networking-services)). Envoy proxy Service는 `externalTrafficPolicy: Local`로 두어 Envoy가 실제 client IP를 보게 한다. DNS와 80·443 security group이 large EIP만 가리키기 때문이다. medium의 앱에는 Envoy가 cluster 내부 Service로 보낸다.
  - 지금 설치한 cluster는 Compose의 edge nginx가 80·443을 쓰므로 servicelb를 끄고, k3s 기본 Traefik은 켜진 채로 두었다([k3s 설치](../../deploy/aws-k3s.md#k3s-설치)). 첫 설치 때 svclb의 hostPort DNAT가 edge nginx보다 먼저 80·443을 가로챘다. 그래서 **edge nginx를 멈춘 뒤에** servicelb를 켜고 Traefik을 끈다. 이 전환 동안 외부 접속이 잠깐 끊긴다([전환 순서](../../deploy/aws-k3s.md#전환-순서)).
- **TLS**: cert-manager(공식 Helm 차트)가 Let's Encrypt에서 prod 도메인 인증서를 Gateway API HTTP-01 solver로 발급·갱신한다. 지금 edge nginx 옆의 certbot을 대신한다.
- **cluster API**: 6443은 같은 node security group에서 medium → large를 허용하고, 사람은 kubectl을 쓰는 사람의 IP만 `/32`로 허용해 EIP로 직접 접근한다([로컬 kubectl 접근](../../deploy/aws-k3s.md#로컬-kubectl-접근)). 배포는 GitHub Actions → SSH → large에서 `kubectl apply -k`와 `rollout status`, 실패 시 `rollout undo`로 한다(아래 "manifest 관리").
- **namespace**: `hapbang-prod`(모듈 앱·React), `hapbang-platform`(PostgreSQL·Kafka·Redis·Elasticsearch·관측성)과 Envoy Gateway·cert-manager·ECK operator의 system namespace를 둔다. dev namespace는 두지 않는다.
- **S3 권한**: 모듈 앱이 어느 node에 배치돼도 S3를 호출할 수 있도록 두 EC2에 같은 instance role과 IMDSv2 hop limit 2를 적용한다. pod별 IAM 권한 분리(EKS IRSA·Pod Identity)는 EKS 기능이라 쓸 수 없으므로 **node의 pod가 같은 S3 권한을 공유한다**는 한계를 문서에 남기고, NetworkPolicy로 앱 pod 외에는 metadata endpoint(169.254.169.254)로 나가지 못하게 막는다.
- **이미지**: GHCR private image를 `imagePullSecret`으로 받는다. 태그는 지금처럼 `sha-<커밋>`을 고정해 쓴다.
- **자동 중지**: 매일 18:00 instance 중지는 node shutdown이다. kubelet graceful node shutdown(`shutdownGracePeriod`, `shutdownGracePeriodCriticalPods`)은 kubelet CLI 플래그가 없으므로 k3s의 kubelet drop-in 설정 파일로 두고, Ubuntu logind의 `InhibitDelayMaxSec`도 함께 올린다(설정은 [AWS 2노드 k3s 배치](../../deploy/aws-k3s.md#k3s-설치)). 이 설정이 없으면 pod는 [4장](graceful-shutdown.md#종료-계약과-순서)의 종료 순서를 밟지 못하고 끝날 수 있다([Kubernetes node shutdown](https://kubernetes.io/docs/concepts/cluster-administration/node-shutdown/)). instance 중지 때 OS가 실제로 기다려 주는 시간 안에 drain이 끝나는지 실측한다.

## workload 구성

- 모듈 앱 8개(`member-app`, `billing-app`, `appearance-app`, `lecture-app`, `chat-app`, `customer-support-app`, `notification-app`, `search-app`): 앱마다 Deployment 1개, replica 1개. node 배치는 위 자원 예산 표를 따른다.
- React 정적 서버: FE 레포의 이미지를 그대로 pod로 띄운다. 이미지 안의 정적 서버도 nginx에서 static-web-server로 바꾼다(prgrms-be-adv-devcourse/beadv8_8_LMS_FE#126).
- PostgreSQL, Kafka, Redis, Elasticsearch: StatefulSet과 large의 local-path volume. `core-data` required node affinity를 사용하며 medium으로 volume이 이동한다고 가정하지 않는다.
- Envoy Gateway: proxy는 large, controller는 medium. cert-manager와 ECK operator는 medium.
- observability stack: `hapbang-platform` namespace에서 prod를 관측한다. 로컬·PR k3d 결과는 테스트 artifact로 남긴다.

replica가 1개라서 large의 앱은 새 pod가 ready가 된 뒤 기존 pod를 내리는 `maxSurge: 1`, `maxUnavailable: 0`으로 배포한다. medium의 앱은 surge 여유가 없어 `maxSurge: 0`, `maxUnavailable: 1`로 배포하고 잠깐 멈추는 것을 허용한다. `maxSurge`를 생략하면 기본값 25%가 1로 올림되어 surge pod가 생기므로 반드시 0으로 적는다. node가 멈추면 그 node의 앱은 node가 다시 뜰 때까지 멈춘다. 앱별 replica를 늘리는 것은 시연할 때만 하고, 그 전에 node 여유를 확인한다.

## manifest 관리 — Kustomize와 Helm

우리가 만든 앱과 직접 쓰는 workload는 평문 YAML로 두고 Kustomize로 환경별 차이만 덮어쓴다(`kubectl apply -k`). 잘 관리되는 공식·준공식 차트가 있는 component만 Helm으로 설치한다. Helm CLI는 쓰지 않고, k3s 내장 helm-controller의 `HelmChart` resource(`valuesContent`)를 manifest로 커밋한다.

앱 manifest는 앱마다 `apps/<앱>/k8s/`에 Dockerfile과 함께 둔다(#373). 모노레포지만 모듈을 그대로 서비스로 떼어낼 수 있어야 하므로(AGENTS.md 3장) 앱 디렉터리만으로 빌드·배포할 수 있게 하고, 루트 `k8s/`를 참조하지 않는다. 루트 `k8s/`에는 앱들이 함께 쓰는 인프라만 둔다.

```text
apps/<앱>/
  Dockerfile
  k8s/           hapbang-prod: Deployment, Service, ConfigMap(<앱>-config)
    base/
    overlays/    local(k3d), prod(이미지 태그, SealedSecret <앱>-secrets)
k8s/
  cluster/       namespace, Retain StorageClass, HelmChart(Envoy Gateway·cert-manager·ECK operator·Sealed Secrets), GatewayClass·Gateway, ClusterIssuer
  components/    Kustomize Component: platform의 core-data required affinity
  platform/      hapbang-platform: PostgreSQL(HelmChart), Kafka·Redis(직접 manifest), Elasticsearch(ECK CR·nori Dockerfile), 관측성(HelmChart)
    base/
    overlays/    local(k3d), prod
```

React 정적 서버, HTTPRoute, SecurityPolicy, NetworkPolicy의 위치는 진입점 작업(#372)에서 정한다.

CI 자동 배포는 앱마다 `apps/<앱>/k8s/overlays/prod`만 적용한다. `cluster/`와 `platform/`은 자주 바뀌지 않고 잘못 건드리면 데이터가 위험하므로 수동으로만 적용한다. 그래서 앱 배포·롤백이 PostgreSQL 같은 stateful workload를 건드리지 않는다.

| 대상 | 설치 방식 | 이유 |
| --- | --- | --- |
| Envoy Gateway | 공식 Helm 차트 | Envoy 프로젝트가 직접 관리한다 |
| cert-manager | 공식 Helm 차트 | CNCF Graduated |
| Elasticsearch | ECK operator(Elastic 공식 Helm 차트) + Elasticsearch CR | ES 9.x를 지원하면서 관리가 이어지는 유일한 경로다. 예전 `elastic/helm-charts`는 2023년에 보관(archived)됐고 9.x 차트가 없다. nori는 커스텀 이미지를 `spec.image`로 지정한다. ECK의 ES 기본 메모리 2Gi는 1.5Gi로, operator limit 1Gi는 256Mi로 낮춘다. ECK 자체는 Elastic License 2.0이며 자체 운영에는 제한이 없다 |
| PostgreSQL | CloudPirates `postgres` 차트 | 공식 `postgres` 이미지를 쓰는 일반 차트다. CloudNativePG(CNCF)는 instance가 1개라 failover를 쓸 수 없고 S3 백업도 범위 밖이라 operator 비용만 남는다 |
| 관측성 | prometheus-community `prometheus`, grafana-community `loki`·`tempo`·`grafana`, grafana `alloy` 차트 | 설정이 복잡해 차트의 이득이 크다. Loki·Tempo·Grafana의 OSS 차트는 2026년에 grafana 저장소에서 grafana-community로 옮겨졌다(각 차트 README). Prometheus는 부속 component가 예산에 없어 kube-prometheus-stack이 아니라 단독 차트를 쓴다 |
| Sealed Secrets | bitnami `sealed-secrets` 차트 (#373) | prod Secret을 암호화해 레포에 커밋한다. 서버에서 `kubectl create secret`으로 손으로 만들면 manifest와 서버 상태가 갈라진다. Bitnami 카탈로그 변경(`bitnamilegacy`) 대상이 아니다([공지](https://github.com/bitnami/containers/issues/83267)) |
| Kafka | StatefulSet 직접 작성(`apache/kafka`, single-node KRaft) | Apache 공식 차트가 없다. Strimzi operator는 기본 384Mi를 상시로 쓴다 |
| Redis | StatefulSet 직접 작성(`redis:8`, AOF + PVC) | Redis Ltd의 OSS용 공식 차트가 없다. OT redis-operator는 기본 500Mi로 Redis 예산(64 MiB)보다 크다 |

Bitnami 차트는 쓰지 않는다. 2025년 8월부터 버전별 무료 이미지가 `bitnamilegacy`로 옮겨져 보안 패치가 나오지 않는다. 모든 차트는 버전을 고정하고 resource·node affinity·retention을 values에 적는다.

앱 base(`apps/<앱>/k8s/base`)에 둘 것:
- Deployment, Service, `HTTPRoute`(모듈 경로별), `SecurityPolicy`
- startup/readiness/liveness probe, resource request/limit
- ConfigMap. Secret(JWT 개인 키, DB 계정, 내부 API key)은 앱마다 하나(`<앱>-secrets`)이고 평문으로 커밋하지 않는다. prod는 SealedSecret으로 암호화해 prod overlay에 커밋하고, local overlay는 가짜 값을 쓴다(#373)
- graceful shutdown([4장](graceful-shutdown.md))
- NetworkPolicy: 앱의 외부 API 포트는 Envoy만, 내부 API는 앱끼리만, metadata endpoint는 S3를 쓰는 앱만. 앱이 `X-User-*` 헤더를 믿으므로 이것이 헤더 위조를 막는 마지막 방어선이다. k3s 내장 network policy controller가 실제로 막는지 아래 완료 증거로 확인한다
- migration Job 또는 단일 migration 실행 전략
- 앱별 Kafka·Redis·PostgreSQL·ES endpoint와 member-app JWKS 주소 주입

overlay에서 바꿀 것: image tag(`kustomize edit set image`), resource limit, 환경별 ConfigMap 값. local overlay도 node affinity를 그대로 쓴다. 로컬 k3d를 EC2와 같은 2노드로 띄워 node별 예산을 확인하기 때문이다(#373).

배포와 롤백은 kubectl로 한다.
1. `kubectl apply -k apps/<앱>/k8s/overlays/prod`
2. `kubectl rollout status --timeout`으로 새 pod가 준비되기를 기다린다.
3. 실패하면 `kubectl rollout undo`로 Deployment를 직전 revision으로 되돌리고 배포를 실패로 표시한다.

Kustomize로 관리하는 앱에는 release 단위 롤백이 없다. 그래서 ConfigMap 변경은 이름에 hash가 붙는 `configMapGenerator`로 만든다. 그러면 `rollout undo`가 이전 ConfigMap까지 함께 되돌린다.

## CI 검증

- 지금(#363 이후): 필수 체크 `verify` job 하나가 `verifyModuleStructure` → 앱별 `check bootJar` → 앱별 이미지 빌드(push 안 함)를 실행한다. matrix 전환은 앱별 이미지 발행(#374)과 함께 판단한다.
- 배포: #363부터 `deploy.yml`의 자동 trigger가 꺼져 있다. 앱별 이미지 발행과 prod 배포는 #374에서 다시 작성한다.
- manifest(#373): `manifests` job이 `kubectl kustomize`로 overlay별 rendering → kubeconform `-strict`로 Kubernetes·CRD schema 검사. 필수 체크는 아니다
- PR(앱이 모두 단독 기동한 뒤): k3d cluster 생성 → 앱마다 `kubectl apply -k apps/<앱>/k8s/overlays/local` → migration → smoke/E2E → cluster 삭제
- PR: 앱 사이 JWT 헤더 인증·WebSocket·Kafka·Redis 흐름 검증
- prod k3s: 수동 dispatch로 승인된 main image를 `kubectl apply -k` → `rollout status` → 실패 시 `rollout undo`
- EC2: rolling update 중 HTTP 요청과 WebSocket 재연결 결과 기록
- EC2: 자원 사용량(`kubectl top`)이 예산 안인지 배포 후 기록

# 5. Grafana observability stack과 부하 테스트

상위 문서: [최종 프로젝트 인프라 설계](../final-project-infrastructure.md). 결정 기록은 #369다.

관측성은 **Prometheus + Alloy + Loki + Tempo + Grafana** 한 벌로 고정한다. 애플리케이션과 gateway는 Micrometer와 OpenTelemetry로 계측하고 metrics는 Prometheus, logs는 Loki, traces는 Tempo가 저장한다. Elasticsearch는 검색·Vector DB로만 사용한다.

같은 k6 시나리오로 ingest CPU·메모리·disk/day, 질의 p95, dashboard·alert와 trace-log 상관관계를 측정한다. Elasticsearch 관측성을 제외한 근거는 [Elasticsearch 통합 범위 조사](../elasticsearch-consolidation-research.md)를 따른다.

## 수집·저장 구성

비교 실험은 다음을 한 세트로 구성한다.

- Micrometer Prometheus-format endpoint: HTTP, JVM, HikariCP, Kafka lag, 채팅 outbox 적재 대기·재시도·DEAD, Stream 그룹 lag·PEL/idle·ACK 지연·회수·메모리, DB 발행 체크포인트·재구성, Redis Pub/Sub 발행 실패, HP task 노출
- Prometheus: `/actuator/prometheus`와 infrastructure exporter를 직접 scrape하고 local TSDB·PromQL·rule 평가 담당
- Grafana Alloy: pod log 수집과 OTLP trace 수신·전달 담당. Mimir로 바꿀 때만 metrics scrape·remote write까지 확장
- Loki: JSON log 저장; `traceId`, `correlationId`, 배포 version으로 연결
- Tempo: HTTP → 업무 transaction → Kafka publish/consume → chat delivery trace 저장
- Grafana: Prometheus·Loki·Tempo datasource와 API, Kafka, chat, HP, Kubernetes dashboard·alert
- k6: 일반 API, WebSocket, 돈 흐름 재시도 시나리오

기준선에서는 **Prometheus가 metrics를 직접 scrape**하고, Alloy는 logs·traces를 Loki·Tempo로 전달한다. Grafana는 세 backend를 조회한다. Prometheus remote-write receiver는 공식 문서가 일반 scrape의 대체 수단으로 적합하지 않다고 명시하므로, collector를 하나처럼 보이게 만들기 위해 `Alloy scrape → Prometheus remote write receiver`를 기본 구성으로 쓰지 않는다([Prometheus Remote Write Receiver](https://prometheus.io/docs/prometheus/latest/querying/api/#remote-write-receiver)). Prometheus와 Mimir를 동시에 장기 저장소로 두지 않는다.

## 현재 수집 상태 (#480)

- **지표**: 모듈 앱 6개(member·lecture·appearance·customer-support·billing·chat)가 관리 포트 8082에서 `/actuator/prometheus`를 연다(lecture #521, billing #552, chat #590, 나머지 #528). Prometheus 차트 기본 `kubernetes-pods` job이 pod annotation을 보고 1분마다 수집한다. label은 pod label을 옮긴 `app_kubernetes_io_name`과 `namespace`·`pod`다.
- **HTTP 응답 시간 bucket** (#617): 6개 앱이 `management.metrics.distribution.slo.http.server.requests`에 경계 10개(5·10·25·50·100·250·500·1000·2500·5000ms)를 같은 값으로 두어 `http_server_requests_seconds_bucket`을 낸다. p50·p95·p99는 `histogram_quantile`로 계산한다. `percentiles-histogram`은 bucket이 기본 69개(5ms~5s로 좁혀도 48개)라 쓰지 않았다. Prometheus memory limit이 384Mi이고 2026-11-03 사용량이 211Mi였기 때문이다. bucket은 uri·method·status 조합마다 11개(`+Inf` 포함)씩 생긴다. 2026-11-03 운영의 조합은 20개라 약 220 series가 늘어난다. 진행 중 요청 수(`http.server.requests.active`)는 접두어가 같아 bucket이 따라 붙으므로 빈 값으로 끈다.
- **로그**: Alloy가 `hapbang-prod`·`envoy-gateway-system`의 pod log를 Kubernetes API로 읽어 Loki로 보낸다(`k8s/platform/base/observability.yaml`). label은 `namespace`·`app`(`app.kubernetes.io/name`)·`pod`·`container`다. 형식은 앱 기본 평문 그대로이고 multiline·JSON 파싱은 하지 않는다. Envoy access log의 JSON 필드는 조회할 때 `| json`으로 꺼낸다.
- **trace**: Envoy가 요청의 10%를 샘플링해 OTLP gRPC로 Alloy(4317)에 보내고 Alloy가 Tempo로 넘긴다(#372). lecture-app은 Envoy가 넘긴 `traceparent`를 이어받아 server span과 지갑 내부 API client span을 같은 trace에 보낸다(#579, 아래 "앱 trace 계측"). 나머지 앱은 아직 계측하지 않아 그 앱의 요청은 Envoy span만 남는다.
- **dashboard**: Grafana `합방` 폴더에 다섯 개가 있다(#531, Kafka #597, 앱 개요 #601, 아래 "Dashboard").
- 구조화 로그(#582)와 alert는 아직 없다(#343). 계측한 앱의 평문 로그에는 Boot가 trace ID를 붙인다.

다른 앱이 지표를 열 때는 다음 다섯 가지를 더한다. `micrometer-registry-prometheus`는 루트 `build.gradle.kts`가 모든 앱에 넣는다. lecture-app이 예시다.

1. `application.yaml`: `management.endpoints.web.exposure.include: health,prometheus`와 위의 HTTP 응답 시간 bucket(`management.metrics.distribution.slo`)
2. `SecurityConfig`: `GET /actuator/prometheus`만 permitAll로 연다. 다른 actuator 경로는 계속 막는다.
3. `k8s/base/kustomization.yaml`의 ConfigMap: `MANAGEMENT_SERVER_PORT=8082`. actuator(헬스체크·지표)가 관리 포트에서만 열린다. 로컬 실행은 설정하지 않아 앱 포트에서 열린다.
4. `k8s/base/deployment.yaml`: `management` containerPort 8082, probe 포트를 `management`로, pod template annotation `prometheus.io/scrape: 'true'`, `prometheus.io/port: '8082'`, `prometheus.io/path: /actuator/prometheus`
5. `k8s/base/networkpolicy.yaml`: 8082를 `hapbang-platform`의 Prometheus server pod(`app.kubernetes.io/name: prometheus`, `app.kubernetes.io/component: server`)에 연다. 8080에는 열지 않는다.

외부 API 포트(8080)는 쓰지 않는다. 앱은 Gateway 사용자 헤더(`X-User-*`)를 믿으므로 8080에는 Envoy만 들어와야 한다. Prometheus pod가 장악되면 헤더를 붙여 아무 회원으로 행세할 수 있기 때문이다([API Gateway](api-gateway.md#헤더-신뢰-구조를-유지하는-이유-2026-11-02), #521). 내부 포트(8081)도 쓰지 않는다. `InternalApiPortValve`가 `/api/v1/internal` 밖 경로를 404로 막는다. `/actuator`는 HTTPRoute가 라우팅하지 않아 Gateway로 노출되지 않는다.

registry를 더하면 앱 메모리가 늘어나므로 limit을 다시 확인한다(member-app은 448 MiB에서 OOMKilled, [k3s 자원 예산](k3s.md#ec2-2노드-자원-예산)). 관리 포트는 Tomcat을 하나 더 띄운다. lecture-app은 로컬에서 RSS 약 +10MB, 스레드 +16이었다(1회 측정, #521).

### 앱 trace 계측 (#579)

lecture-app이 첫 적용이다. 요청 하나가 `Envoy ingress → Envoy router egress → lecture-app server → lecture-app 지갑 client` span으로 Tempo에 남는다.

**동작.**

- **샘플링**: Boot 기본 sampler(`PARENT_BASED_TRACE_ID_RATIO`)라 부모가 있으면 부모의 결정을 따른다. Envoy를 거친 요청은 Envoy가 `traceparent` flag에 실은 결정(10%)을 그대로 쓰고, flag가 `00`이면 앱도 span을 보내지 않는다. `management.tracing.sampling.probability`(0.1)는 배치(`@Scheduled`)처럼 앱 안에서 시작하는 trace에만 쓴다.
- **전송**: Alloy는 OTLP gRPC(4317)만 받는다. Boot 기본 transport는 HTTP라 `grpc`로 바꾼다. endpoint는 ConfigMap이 넣는다. endpoint가 없으면(로컬 실행, 테스트) exporter가 생기지 않아 아무것도 보내지 않는다.
- **로그**: tracing이 켜지면 Boot가 로그 줄의 스레드 이름 뒤에 `[<traceId 32자>-<spanId 16자>] `를 붙인다. trace 밖의 줄은 같은 폭의 공백이다. 같은 요청의 Envoy access log `trace_id`는 `traceparent` 전체(`00-<traceId>-<spanId>-<flag>`)라 가운데 32자가 앱 로그의 traceId와 같다.
- **Grafana 연결**: Tempo datasource의 trace → logs는 `{namespace=~"hapbang-prod|envoy-gateway-system"} |= "<traceId>"`로 span 앞뒤 5분의 앱·Envoy 로그를 찾는다. Loki datasource의 derived field 두 개(앱 로그 `traceId`, Envoy `trace_id`)가 로그 줄에서 Tempo trace로 가는 링크를 만든다(`k8s/platform/base/observability.yaml`).
- **관리 포트**: 관리 포트(8082)에는 HTTP 요청 observation이 없어 Spring Security filter chain observation이 부모 없이 시작된다. 그대로 두면 probe(5초마다 3종)와 Prometheus 수집마다 root trace가 생기므로 부모 없는 `spring.security.*` observation을 막는다(`ObservationConfig`). 업무 요청의 Security span은 HTTP 요청 span 아래에 그대로 남는다.

**다른 앱에 붙이는 절차.** lecture-app이 예시다.

1. `build.gradle.kts`: `implementation("org.springframework.boot:spring-boot-starter-opentelemetry")`. Micrometer Tracing OTel bridge, OTLP exporter와 함께 OTLP 지표 registry도 들어온다.
2. `application.yaml`:
   ```yaml
   management:
     tracing:
       sampling:
         probability: 0.1
     opentelemetry:
       tracing:
         export:
           otlp:
             transport: grpc
     otlp:
       metrics:
         export:
           enabled: false   # 지표는 Prometheus가 수집한다. 켜 두면 localhost:4318로 보내다 실패한다
   ```
3. `global/config/ObservationConfig`: 부모 없는 `spring.security.*` observation을 막는 `ObservationPredicate` 빈.
4. `k8s/base/kustomization.yaml`의 ConfigMap: `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://alloy.hapbang-platform.svc.cluster.local:4317`. service name은 `spring.application.name`이다. 앱 NetworkPolicy에는 egress 제한이 없어 정책은 바꾸지 않는다.
5. 스레드를 바꾸는 곳: trace context는 스레드 로컬이라 다른 스레드로 넘어가지 않는다. 앱이 직접 만든 executor로 HTTP를 부르면 `ContextExecutorService.wrap(executor, ContextSnapshotFactory.builder().build())`로 감싼다(`shared`의 `BillingApiClient`가 예다. `RestClient`는 Boot가 주입하는 `RestClient.Builder`로 만들어야 client span과 `traceparent`가 붙는다). `@Async`·`TaskExecutor`도 같은 문제가 있다.
6. 메모리: 적용 전후 working set을 재고 limit을 다시 본다. lecture-app은 k3d에서 같은 heap 상한(`-Xmx384m`, `-XX:ActiveProcessorCount=2`)으로 기동 10분 뒤(샘플링된 요청 300건 뒤) 685 → 703 MiB였다(non-heap 190 → 198 MiB, 스레드 64 → 73, 클래스 28,175 → 29,323, 각 1회 측정). 적용 전 운영 working set이 588 MiB(640 MiB의 92%)라 704 MiB로 올렸다([k3s 자원 예산](k3s.md#ec2-2노드-자원-예산)). k3d는 운영보다 working set이 커서(같은 이미지가 640 MiB에서 OOMKilled) 차이만 쓴다.
7. 확인: Grafana Explore → Tempo에서 TraceQL `{resource.service.name="<앱>"}`로 찾는다. 한 trace에 Envoy span 2개와 앱 server span이 있고, 앱 로그 줄의 traceId로 같은 trace가 열리는지 본다.

**지금 계측하지 않는 것.**

- Kafka producer·consumer context 전파와 `eventId` 연결(#630). listener 스레드의 로그에는 traceId가 없다.
- DB(JDBC) span. 요청 span의 시간에 DB 시간이 포함될 뿐 쿼리 단위로 나뉘지 않는다.
- 받는 앱의 server span: lecture-app이 지갑을 부르면 client span까지만 남는다. 나머지 5개 앱은 #631에서 한 번에 붙인다. 반대로 계측하지 않은 앱이 lecture 내부 API(8081)를 부르면 `traceparent`가 없어 lecture-app이 새 trace(10%)를 시작한다.

### Dashboard (#531, #597, #601, #617)

새 component(kube-state-metrics, node-exporter, DB·Kafka exporter)는 띄우지 않고 지금 수집하는 지표만 쓴다. kube-state-metrics는 medium node 메모리 예산 때문에 켜지 않았다.

| dashboard (uid) | 지표 | 내용 |
| --- | --- | --- |
| 앱 개요 (`hapbang-apps`) | 앱 Micrometer, cAdvisor | 앱별 요약 표(scrape 상태, 가동 시간, heap, 메모리/limit 현재·기간 최대, 요청 수, 5xx 비율, 평균·최대 응답, GC 정지 비율, DB 연결 대기·timeout, ERROR 로그, 열린 circuit breaker)와 앱별 비교 시계열. 앱 이름을 누르면 같은 시간 범위로 `hapbang-jvm`을 연다 |
| JVM·Spring Boot (`hapbang-jvm`) | 앱 Micrometer, cAdvisor | 한 앱의 heap·non-heap·buffer, 컨테이너 메모리와 limit, GC, 스레드·CPU, HTTP(상태별, URI별 평균·최대, 앱 전체 p50·p95·p99와 URI별 p95), HikariCP, 나가는 HTTP 호출(대상·URI별 요청·응답 시간), circuit breaker 상태·호출 결과·호출 시간과 retry 결과, `@Scheduled` 작업 실행·시간, Spring Data Repository 메서드별 호출, 로그 레벨별 건수와 Loki의 ERROR·WARN 로그. 지표를 여는 앱만 앱 목록에 나온다 |
| Kubernetes 자원 (`hapbang-kubernetes`) | cAdvisor, kubelet | node CPU·메모리·디스크(`/dev/root`)·네트워크, node별 memory limit 합계, namespace·pod별 CPU·메모리, limit 대비 사용률(현재·기간 최대), 컨테이너 재생성, 최근 시작한 컨테이너 |
| Gateway (Envoy) (`hapbang-gateway`) | Envoy proxy | HTTPS 요청률, 응답 코드 class, p50·p95·p99 지연, route별 요청률·5xx·p95, upstream 연결 실패·timeout·overflow·reset |
| Kafka (`hapbang-kafka`) | 앱 Kafka client, Spring Kafka | 소비 그룹·토픽별 lag, 소비 건수, listener 처리 결과(성공·실패, 재시도 포함)·처리 시간, 마지막 poll 뒤 경과 시간, 할당된 partition, rebalance, KafkaTemplate 전송 결과·시간, 토픽별 전송 레코드·오류·재시도, 그룹별 DLT 적재(#339). 실패·DLT 개요 값은 pod가 뜬 뒤 누적이다. 처음 실패할 때 생기는 series는 `increase()`가 첫 값을 놓치기 때문이다 |

지금 지표로는 볼 수 없는 것:

- pod 상태(Pending·CrashLoopBackOff), 정확한 재시작 횟수와 종료 사유(OOMKilled), Deployment 가용 replica: kube-state-metrics가 필요하다. "컨테이너 재생성"은 같은 pod 이름의 cAdvisor 컨테이너 ID 수로 센 근사치라 StatefulSet pod 재생성도 센다. 종료 사유는 `kubectl describe pod`로 본다.
- OOMKilled 이벤트: cAdvisor `container_oom_events_total`은 2026-10-30 lecture-app OOMKilled(#461)를 기록하지 못했다. 대신 "메모리 사용률 / limit (선택 기간 최대, 상위 15)"로 위험을 본다.
- 정확한 앱 HTTP percentile: bucket 경계 사이는 선형 보간한 근사치다(예: p95가 250~500ms 사이면 그 안에서 비례로 정한다). 5s를 넘는 요청이 percentile에 걸리면 5s로 나온다. 최대값은 `http_server_requests_seconds_max`로 본다. Gateway dashboard의 percentile은 Envoy가 잰 외부 요청만이라 앱 사이 내부 HTTP 호출이 빠진다.
- 앱 개요의 cAdvisor 지표(메모리/limit, CPU): cAdvisor에는 앱 label이 없어 pod 이름(`<앱>-<replicaset hash>-<5자>`)에서 앱 이름을 꺼낸다. Deployment가 아닌 workload나 이름 규칙이 다른 pod는 잡히지 않는다.
- circuit breaker 실패율: `resilience4j_circuitbreaker_failure_rate`는 sliding window의 최소 호출 수를 채우기 전에는 -1이라, 호출이 드문 지금(2026-11-03 운영 전부 -1)은 그리지 않는다. 대신 호출 결과(`failed`)와 상태를 본다.
- 나가는 HTTP 호출: `http_client_requests_seconds` series는 앱이 그 대상을 처음 부른 뒤에 생긴다. pod가 새로 뜬 뒤 호출이 없으면 비어 있다.
- PostgreSQL·Kafka·Redis 내부 지표: exporter가 없다. 앱 쪽 HikariCP·Kafka client 지표만 있다.
- Kafka consumer 중단: lag는 consumer client가 보고하는 값이라, consumer가 멈추면 lag가 늘지 않고 series가 사라진다. Kafka dashboard의 "소비 그룹" 수가 줄어드는 것으로 본다. broker 쪽 lag는 exporter가 필요하다.
- 미완료 publication 수: 지표가 없다. 발행 실패는 "발행 실패"(KafkaTemplate 결과)로만 본다.
- DLT 토픽별 지표는 첫 DLT 전송 뒤 1~2분 늦게 나타난다. Micrometer가 Kafka client의 새 지표(토픽별 지표 포함)를 60초마다 등록하기 때문이다. "listener 처리 실패"는 재시도마다 세므로 DLT 건수가 아니다.
- Kafka client 지표의 `topic` label은 `member.events.v1`과 `member_events_v1` 두 형태로 함께 나온다. 점이 있는 쪽만 고르지 않으면 합계가 두 배가 된다. `records_lag_max`는 최근 fetch가 없으면 NaN이라 `records_lag`를 쓴다.

**수정 방법.** JSON 파일(`k8s/platform/base/dashboards/*.json`)이 원본이다. provider가 `allowUiUpdates: false`라 Grafana UI에서 고친 내용은 저장되지 않는다. UI에서 고친 뒤 Export → Export as JSON으로 내려받아 파일을 바꾸고 PR로 올린다. datasource는 uid(`prometheus`, `loki`)로 적는다. Prometheus datasource의 `timeInterval: 1m`(scrape 주기) 덕분에 `$__rate_interval`은 4분 이상이다.

**적용.** dashboard만 바꿨으면 ConfigMap만 적용한다. client-side `kubectl apply`는 객체 전체를 `last-applied-configuration` annotation에 담고, annotation은 256 KiB(262,144 byte)를 넘을 수 없다. #617 뒤 이 ConfigMap의 JSON은 약 231 KB라 여유가 31 KB쯤이다. dashboard를 더 늘리려면 ConfigMap을 나누거나 `--server-side` 적용으로 바꾼다. 디렉터리로 마운트되어 Grafana 재시작 없이 1~2분 안에 반영된다. provider·datasource를 바꿨으면 grafana HelmChart도 고른다(overlay 전체를 적용하지 않는다).

```bash
kubectl kustomize k8s/platform/overlays/prod \
  | yq 'select(.kind=="ConfigMap" and .metadata.name=="grafana-dashboards-hapbang")' > dashboards.yaml
kubectl --context hapbang-k3s diff -f dashboards.yaml
kubectl --context hapbang-k3s apply -f dashboards.yaml
```

Grafana는 dashboard 없이도 prod에서 3일 최대 working set이 374 MiB였다(2026-11-02, limit 384 MiB, 차트가 넣는 GOMEMLIMIT 345 MiB). 로컬 k3d에서는 384 MiB일 때 dashboard를 여는 동안 메모리가 GOMEMLIMIT 근처(342 MiB)에 붙은 채 unready였고, 448 MiB(GOMEMLIMIT 403 MiB)에서는 세 dashboard가 모두 그려졌다. 다만 k3d Grafana는 이 변경 전부터 CPU를 많이 쓰고 재시작을 반복해 원인을 dashboard 하나로 보지는 않는다. Grafana limit은 #532에서 448 MiB로 올렸고, dashboard는 그 뒤에 적용했다. 2026-11-02 운영 적용 직후 Grafana는 기동 중 최대 426 MiB(limit의 95%), 약 1분 뒤 CPU 15m·메모리 약 290 MiB로 내려왔다. 기동 최대치가 limit에 가까우니 Grafana 재시작 뒤 OOMKilled가 없는지 본다. dashboard 다섯 개(#601)를 쓰며 7일 최대 418 MiB(93%, 2026-11-03)가 되어 limit을 512 MiB로 올렸다(#608, `k8s/gateway/overlays/prod/grafana.yaml`).

client-side apply는 `last-applied-configuration` annotation(256 KiB 한도)에 ConfigMap 전체를 넣는다. 세 파일 합계 약 110 KB에서 annotation은 약 150 KB다. 한도에 가까워지면 `--server-side`로 적용하거나 ConfigMap을 나눈다.

## Alert 정책 (#629)

대상 지표와 정책만 정했다. alert rule과 통보는 아직 구성하지 않았다. 구현은 #343의 후속 티켓에서 운영 실측을 본 뒤 한다.

운영자가 받는 장애 통보는 **alert**라고 쓴다. 회원이 받는 **알림**(notification, [용어집](../../GLOSSARY.md))과 섞어 쓰지 않는다.

### 경로와 통보

- **평가**: Grafana alerting으로 한다. Prometheus·Loki 쿼리를 모두 rule로 쓸 수 있고 pod가 늘지 않는다. Prometheus 차트의 Alertmanager는 켜지 않는다(pod가 하나 늘어 medium node 예산을 다시 잡아야 한다).
- **통보**: Slack으로 보낸다. Grafana의 Slack contact point(incoming webhook)를 쓰고, webhook URL은 Sealed Secret에 둔다.
- **관리**: rule과 contact point는 dashboard처럼 레포 파일로 provisioning한다. UI에서 고친 내용은 저장하지 않는다.
- **자원**: rule 평가가 Grafana에 더해지므로 적용 전후 메모리를 잰다(limit 512 MiB, 7일 최대 418 MiB, #608).
- **온콜 도구는 쓰지 않는다.** 당번 체계가 없고, Opsgenie는 신규 판매가 끝났으며 2027-04-05에 지원이 끝난다([Atlassian 안내](https://www.atlassian.com/licensing/opsgenie)).

### alert마다 정할 것

| 항목 | 내용 |
| --- | --- |
| 측정 대상·조건 | 어떤 지표가 어떤 값을 넘는가 |
| 지속 시간 | 조건이 얼마 동안 이어져야 보내는가. 잠깐 튀는 값은 거른다 |
| 통보 채널 | 보낼 Slack 채널 |
| 중복 억제 | 같은 alert를 다시 보내는 간격과 묶는 label |
| 복구 통보 | 정상으로 돌아왔을 때 알리는가 |
| 확인할 곳과 대응 | 받은 사람이 볼 dashboard·로그 쿼리와 대응 절차 |

**임계값.** 돈·HP 정합성 alert는 0건을 넘으면 바로 보낸다(지속 시간 없음). 나머지는 운영 7일 실측(평소 값과 최대)을 본 뒤 구현 티켓에서 정한다. 실측 없이 정한 숫자는 평소에도 울리거나, 장애 때도 울리지 않는다.

**메모리는 컨테이너 기준이다.** 메모리 alert는 컨테이너 working set ÷ limit으로 본다. OOMKilled는 이 값이 limit에 닿을 때 일어난다. JVM heap 사용률은 GC가 정상으로 돌아도 높게 나오므로 alert에 쓰지 않고 dashboard에서만 본다. 평소 비율이 앱마다 다르고 이미 높다(2026-11-03 7일 최대, 앱 6개 모두 85% 이상, #610). "80% 넘으면"처럼 한 숫자로 정하면 지금도 계속 울린다.

### 후보

지금 수집하는 지표로 만들 수 있는 것이다. 지표 이름은 Micrometer 이름이고, Prometheus에서는 `.`이 `_`로 바뀐다.

| 분류 | alert | 지표 | 조건 |
| --- | --- | --- | --- |
| 돈·HP 정합성 | 서비스 간 HP 대사 불일치 | `lecture.hp_reconciliation.mismatches`, `appearance.hp_reconciliation.mismatches` | 0 초과 즉시 |
| 돈·HP 정합성 | 지갑 잔액 대사 불일치·실패 | `hapbang.billing.reconciliation.wallets{result="mismatched"\|"failed"}` | 0 초과 즉시 |
| 돈·HP 정합성 | 대사가 돌지 않음 | `hapbang.billing.reconciliation.last.success`, `*.hp_reconciliation.runs` | 마지막 성공이 하루 넘게 없음 |
| 돈·HP 정합성 | HP 작업 DEAD | `lecture.hp_task.dead`, `appearance.hp_task.dead` | 0 초과 즉시 |
| 앱 상태 | 지표 수집 끊김 | `up` (`kubernetes-pods` job) | 0이 지속 |
| 앱 상태 | 컨테이너 메모리 | cAdvisor working set ÷ limit | 실측 뒤 |
| 앱 상태 | 5xx 비율 | Envoy `envoy_http_downstream_rq_xx` (route별) | 실측 뒤 |
| 앱 상태 | DB 연결 대기·timeout | HikariCP `hikaricp.connections.pending`, `hikaricp.connections.timeout` | 실측 뒤 |
| 앱 상태 | circuit breaker 열림 | `resilience4j.circuitbreaker.state{state="open"}` | 열린 상태가 지속 |
| 비동기 | Kafka 소비 lag | Kafka client `records-lag` (topic label 중복 주의, 위 "Dashboard") | 실측 뒤 |
| 비동기 | 소비 그룹 사라짐 | 같은 지표의 series 부재 | 그룹 수 감소 |
| 비동기 | 채팅 전달 작업 격리·전달 중지 | `chat.delivery.isolated`, `chat.delivery.paused.state` | 격리 증가, 중지 지속 |
| 인프라 | node 디스크 | cAdvisor `container_fs_usage_bytes` / `container_fs_limit_bytes` (`/dev/root`) | 실측 뒤 |
| 인프라 | ERROR 로그 급증 | Loki, JVM dashboard의 ERROR 쿼리 | 실측 뒤 |

지표가 없어 지금은 만들 수 없는 것:

- DLT 적재 건수: 앱 카운터가 필요하다(위 "Dashboard").
- 채팅 outbox DEAD·적재 대기: outbox 상태 지표가 없다. 전달 작업 격리(`chat.delivery.isolated`)로만 본다.
- OOMKilled 사유, CrashLoopBackOff, Deployment 가용 replica: kube-state-metrics가 필요하다.
- PostgreSQL·Kafka·Redis 내부 상태: exporter가 없다.

## metrics 수집과 저장 — Prometheus vs Mimir

metrics pipeline에는 역할이 셋 있다. Prometheus는 이 중 수집과 저장을 **둘 다** 할 수 있어서 "Alloy vs Prometheus"처럼 보이기 쉽지만, 실제로 고르는 것은 축 두 개다.

| 역할 | 하는 일 | 후보 | 이 문서의 선택 |
| --- | --- | --- | --- |
| metrics 수집 | 앱의 `/actuator/prometheus`와 exporter scrape | Prometheus 자체 scrape, Alloy scrape + remote write | **현재는 Prometheus 직접 scrape** |
| 저장·조회 | 시계열 보관, PromQL 응답, recording/alert rule | Prometheus TSDB, Mimir | 아래 비교 |
| logs·traces 수집 | pod log와 OTLP trace 전달 | Alloy | **Alloy로 고정** |
| 조회 UI | dashboard, alert, trace·log 이동 | Grafana | Grafana로 고정 |

### 저장소: Prometheus vs Mimir

두 제품 모두 Prometheus ecosystem의 protocol과 PromQL을 사용하므로 Micrometer 계측과 Grafana dashboard는 유지할 수 있다. 차이는 단일 node TSDB와 분산 장기 저장소 중 어느 운영 문제를 풀 것인지다.

| 비교 축 | Prometheus | Grafana Mimir | 이 포트폴리오 판단 |
| --- | --- | --- | --- |
| 수집·조회·저장 model | target scrape, PromQL, rule, local TSDB를 한 server가 담당한다 | 직접 target을 scrape하지 않고 Alloy의 remote write를 받는다. distributor·ingester·querier·compactor 등으로 write/read/storage path를 나누고 TSDB block을 object storage에 둔다([Mimir architecture](https://grafana.com/docs/mimir/latest/get-started/about-grafana-mimir-architecture/)) | 현재는 Prometheus 하나가 metrics pipeline을 소유한다 |
| HA·장기 보존 | local storage는 단일 node의 확장성과 내구성에 제한되고 clustered·replicated storage가 아니다. 기본 보존은 15일이며 time/size limit를 설정한다([Prometheus storage](https://prometheus.io/docs/prometheus/latest/storage/)) | horizontally scalable·highly available한 long-term metrics store가 목표이며 object storage에 block을 보존한다. local filesystem backend는 single-node 전용이다([Mimir 개요](https://grafana.com/docs/mimir/latest/), [Mimir architecture](https://grafana.com/docs/mimir/latest/get-started/about-grafana-mimir-architecture/)) | server 1 + agent 1로는 Mimir의 HA를 실현할 수 없고, 기존 S3는 업로드 전용이라 장기 metrics object storage도 범위 밖이다 |
| 확장·tenancy | local TSDB는 한 server 단위이며 tenant isolation을 제공하지 않는다 | component를 수평 확장하고 tenant별 TSDB와 request tenant ID를 사용한다. shuffle sharding으로 tenant workload의 영향 범위도 제한할 수 있다([Mimir shuffle sharding](https://grafana.com/docs/mimir/latest/configure/configure-shuffle-sharding/)) | 단일 팀·2노드 시연에는 multi-tenancy와 분산 확장이 필요 없다 |
| 운영 복잡도 | server 1개, local volume 1개와 retention 설정으로 끝난다 | monolithic mode도 내부의 여러 component·ring·block compaction·storage 설정을 함께 운영한다. 분산 장점을 내려면 microservices와 object storage까지 필요하다([Mimir deployment modes](https://grafana.com/docs/mimir/latest/references/architecture/deployment-modes/)) | 장애 시연 대상이 아닌 metrics backend에 운영 항목을 늘리지 않는다 |
| 포트폴리오 적합성 | dashboard·alert·PromQL·k6 전후 비교·exemplar 연계에 충분하다 | 다중 tenant, 장기 보존, 대규모 수평 확장 자체를 시연할 때 가치가 있다 | **비교 기준선은 Prometheus 선택** |

따라서 Grafana 관측성 stack의 metrics backend는 **Prometheus 하나만 배포**한다. Prometheus와 Mimir를 동시에 쓰거나, 짧은 보존 Prometheus 앞에 Mimir를 추가하지 않는다. Mimir는 다음 중 하나가 실제 시연 요구가 될 때 Prometheus를 **대체**한다. 이때 Prometheus의 Kubernetes discovery·relabel 규칙을 Alloy `prometheus.scrape`로 옮기고 Mimir로 remote write한다([Alloy metrics 수집 공식 문서](https://grafana.com/docs/alloy/latest/collect/prometheus-metrics/)).

- MinIO 등 object storage에 metrics를 장기 보존하고 compaction·retention을 보여 줄 때
- Mimir를 microservices mode로 띄워 ingester 장애 중에도 수집과 조회가 유지되는 것을 보여 줄 때
- 환경(dev/demo)이나 팀별 tenant 분리와 tenant별 limit를 보여 줄 때

어느 쪽이든 metrics 장기 저장소는 하나만 둔다. 이 구성은 Elastic과 비교하기 위한 단일-node observability 기준선이다.

시연에서는 `traceId`를 구조화 log에 넣어 trace에서 관련 Loki log로 이동하고, log에서 Tempo trace로 돌아오게 한다. 가능하면 latency histogram exemplar에서도 해당 trace로 이동시켜 metrics → trace → logs 한 흐름을 녹화한다.

Promtail은 사용하지 않는다. 2026-03-02에 EOL이 되었고 이후 기능 개발은 Alloy에서 진행된다는 공식 안내가 있다([Loki 공식 문서](https://grafana.com/docs/loki/latest/send-data/promtail/)).

시연용 초기 목표는 다음과 같이 두고 실제 결과를 README에 남긴다.

| 시나리오 | 초기 목표 |
| --- | --- |
| 일반 API | 100 RPS, 10분, p95 500 ms 이하, 5xx 1% 미만 |
| 채팅 | WebSocket 200개, 20 msg/s, 저장→전달 p95 1초 이하 |
| Kafka | consumer 중지 후 재개, backlog 5분 내 회복 |
| 채팅 outbox·Streams·Pub/Sub | relay lease·PEL 회수, XADD/PUBLISH/DB 기록/XACK 경계의 중복, Redis 데이터 유실 재구성·보존·메모리 상한, 마지막 프레임 유실의 이력 대조 복구 |
| 돈 흐름 | timeout·중복 요청에도 원장 불변식 위반 0건 |
| rolling update | HTTP 오류 최소화, WebSocket 재연결 후 history gap 0건 |

이 수치는 제품 SLO가 아니라 포트폴리오 실험의 기준선이다.

EC2에서 부하 test를 할 때는 다음을 지킨다.

- k6는 EC2 밖(개발 PC나 GitHub Actions runner)에서 실행한다. 같은 host에서 k6를 돌리면 부하 생성기가 2 vCPU를 나눠 써서 측정값이 왜곡된다.
- t3 standard credit 모드에서는 credit이 바닥나면 CPU가 기준선으로 제한된다. 각 실행 전후의 CloudWatch `CPUCreditBalance`를 결과와 함께 기록한다. credit 소진 구간의 p95는 코드 성능이 아니라 instance 제한으로 해석한다.
- EC2에는 prod만 있으므로 부하 test는 prod를 대상으로, 팀이 서버를 쓰지 않는 시간대에 하고 미리 공지한다. 결과에는 그 시간 node별 자원 사용량도 함께 남긴다. 반복 실험은 로컬 k3d에서 먼저 한다.
- 위 초기 목표(100 RPS 10분 등)가 2노드에서 유지되지 않으면 목표를 낮추는 것이 아니라 node별 CPU credit, DB connection, GC 병목을 JFR과 dashboard로 설명하는 것을 결과물로 삼는다.

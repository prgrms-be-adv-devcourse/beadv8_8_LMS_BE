# 부하 테스트

k6 부하 테스트의 실행 규칙, 측정 절차, 결과 문서 양식을 모은다. 시나리오 목표는 [관측성](../architecture/infrastructure/observability.md)의 "시연용 초기 목표" 표를 따른다. 결과는 시나리오마다 이 폴더에 문서 하나로 남긴다.

| 문서 | 내용 |
| --- | --- |
| [강의 조회 API 기준선](lecture-baseline.md) | lecture-app 조회 API 100 RPS 10분, JFR (#690) |

채팅 단체방 부하 시험 도구는 chat-app이 따로 관리한다(`apps/chat-app/docs/experiments/`, #682).

## 파일

| 경로 | 내용 |
| --- | --- |
| `loadtest/k6/lib/` | 시나리오가 같이 쓰는 코드. base URL, `RsData` 응답 검사, 5xx 비율 지표(`server_errors`), 로그인 |
| `loadtest/k6/<모듈>/<시나리오>.js` | 시나리오 스크립트 |
| `loadtest/jfr.sh` | JVM 앱 pod에 JDK 임시 컨테이너를 붙여 JFR을 기록한다 |
| `docs/load-test/results/` | k6 요약 JSON(`--summary-export`). 요청마다 남는 원시 데이터(`--out json`)는 크기가 커 커밋하지 않는다 |
| `docs/load-test/images/` | 결과 문서의 Grafana 캡처 |

## 측정 규칙

EC2에는 prod만 있다. 부하 테스트는 prod를 대상으로 하고 다음을 지킨다.

- k6는 EC2 밖(개발 PC나 GitHub Actions runner)에서 실행한다. 같은 host에서 k6를 돌리면 부하 생성기가 2 vCPU를 나눠 써서 측정값이 왜곡된다.
- 개발 PC에서 돌리면 집 네트워크 지연이 결과에 섞인다. 실행 전에 단건 요청의 응답 시간(아래 "실행 직전 점검")을 기준값으로 적는다.
- t3 standard credit 모드에서는 credit이 바닥나면 CPU가 기준선으로 제한된다. 각 실행 전후의 CloudWatch `CPUCreditBalance`를 결과와 함께 기록한다. credit 소진 구간의 p95는 코드 성능이 아니라 instance 제한으로 해석한다.
- 서버 운영 시간은 [작업 흐름](../contributing/git-workflow.md) 6장을 따른다. 측정은 서버가 켜져 있는 시간에 한다.
- 스크립트를 바꾸면 본 측정 전에 `PROFILE=smoke`(1 VU 30초)로 운영에서 먼저 돌려 본다. 예전 규칙인 "반복 실험은 로컬 k3d에서 먼저"는 두지 않는다. k3d는 노트북 자원이라 수치가 운영과 다르고, 로그인·S3 presigned URL 같은 운영 경로가 없어 스크립트 확인에도 맞지 않는다(#690에서 정함).
- 공용 PostgreSQL·Kafka·관측 stack이 같이 흔들린다. 팀이 서버를 쓰는 중이면 미리 알리고, 같은 시각에 운영을 바꾸는 작업(dev 병합에 따른 자동 배포, HelmChart 적용)과 겹치지 않게 한다. 결과에는 그 시간 node별 자원 사용량도 함께 남긴다.
- 초기 목표(100 RPS 10분 등)가 2노드에서 유지되지 않으면 목표를 낮추는 것이 아니라 node별 CPU credit, DB connection, GC 병목을 JFR과 dashboard로 설명하는 것을 결과물로 삼는다.
- 쓰기 API(장바구니·주문·충전)는 실제 데이터와 HP를 움직이므로 시나리오에 넣으려면 먼저 팀과 정한다.
- 로그인이 필요한 API는 부하 테스트 전용 일반 회원 계정으로 부른다. 계정 정보는 레포 밖(`~/.hapbang/load-tests/`, 권한 0600)에 두고 환경 변수로 넘긴다.

### 실행 직전 점검

측정을 무효로 만드는 일이 없는지 본다. 결과 문서의 "조건" 절에 그대로 적는다.

```bash
kk() { kubectl --context hapbang-k3s "$@"; }
# 진행 중인 배포가 없어야 한다
gh run list --workflow publish.yml --limit 3
gh run list --workflow deploy.yml --limit 3
# 대상 앱 pod 재시작 횟수와 이미지(커밋 SHA)
kk -n hapbang-prod get pod -l app.kubernetes.io/name=lecture-app \
  -o 'custom-columns=POD:.metadata.name,RESTARTS:.status.containerStatuses[0].restartCount,IMAGE:.spec.containers[0].image'
# 대상 앱 JVM 옵션. ConfigMap은 이름에 hash가 붙어 여러 개 남으니 Deployment가 가리키는 것을 본다
CM=$(kk -n hapbang-prod get deploy lecture-app -o jsonpath='{.spec.template.spec.containers[0].envFrom[0].configMapRef.name}')
kk -n hapbang-prod get cm "$CM" -o jsonpath='{.data.JAVA_TOOL_OPTIONS} {.data.MALLOC_ARENA_MAX}'
# 관측 stack 재시작 횟수와 Prometheus limit
kk -n hapbang-platform get pod
kk -n hapbang-platform get deploy prometheus-server -o jsonpath='{.spec.template.spec.containers[?(@.name=="prometheus-server")].resources.limits.memory}'
# Envoy trace 샘플링(%)
kk -n envoy-gateway-system get envoyproxy hapbang -o jsonpath='{.spec.telemetry.tracing.samplingRate}'
# 공개 강의 수(데이터 건수). 결과 문서의 "조건"에 적는다
curl -s 'https://hapbang.duckdns.org/api/v1/lectures?size=1' | jq '.data.page.totalElements'
# 단건 응답 시간 기준값(5회)
for i in 1 2 3 4 5; do curl -s -o /dev/null -w '%{time_total}\n' 'https://hapbang.duckdns.org/api/v1/lectures?size=20'; done
```

실행 중이나 뒤에 대상 앱 pod가 재시작하거나 OOMKilled되면 그 실행은 무효로 하고 다시 잰다. 공용 PostgreSQL·Kafka가 재시작한 실행도 무효다. 관측 stack(Tempo·Alloy 등)이 재시작하면 결과는 쓰되 그 구간의 trace·log가 비는 것을 한계로 적는다.

## k6 실행

k6는 `brew install k6`로 설치한다. 모든 명령은 레포 루트에서 실행한다.

계정 파일 예시(`~/.hapbang/load-tests/lecture.env`):

```bash
LT_EMAIL=<테스트 계정 이메일>
LT_PASSWORD=<비밀번호>
```

스크립트는 먼저 `PROFILE=smoke`(1 VU 30초)로 도는지 보고, 본 측정을 한다.

```bash
set -a; source ~/.hapbang/load-tests/lecture.env; set +a
k6 run -e PROFILE=smoke loadtest/k6/lecture/baseline.js

STAMP=$(date +%Y%m%d-%H%M)
# 원본 요약 JSON에 access token이 들어가므로 나만 읽게 만든다
umask 077
mkdir -p -m 700 ~/.hapbang/load-tests/runs
chmod 700 ~/.hapbang ~/.hapbang/load-tests
k6 run -e PROFILE=baseline \
  --summary-export ~/.hapbang/load-tests/runs/lecture-baseline-${STAMP}.json \
  loadtest/k6/lecture/baseline.js > ~/.hapbang/load-tests/runs/k6-lecture-${STAMP}.log 2>&1
echo "k6 exit=$?"   # 0 합격, 99 threshold 실패, 그 밖은 실행 오류
tail -60 ~/.hapbang/load-tests/runs/k6-lecture-${STAMP}.log
# 요약 JSON의 setup_data에는 setup()이 돌려준 access token이 들어 있다. 빼고 레포로 옮긴다
jq 'del(.setup_data)' ~/.hapbang/load-tests/runs/lecture-baseline-${STAMP}.json \
  > docs/load-test/results/lecture-baseline-${STAMP}.json
grep -c 'eyJ' docs/load-test/results/lecture-baseline-${STAMP}.json   # 0이어야 한다
```

- **k6 `--summary-export`는 `setup()`의 반환값(`setup_data`)을 그대로 쓴다.** 이 시나리오는 access token을 반환하므로, 원본은 레포 밖에 두고 `setup_data`를 지운 파일만 커밋한다.
- 실행 기록(로그·원본 JSON·JFR)은 `~/.hapbang/load-tests/`처럼 지워지지 않는 곳에 둔다. `/tmp`나 작업용 임시 폴더는 정리될 수 있다.

- `BASE_URL`을 주면 다른 주소를 친다(기본 `https://hapbang.duckdns.org`).
- 요청에는 `User-Agent: hapbang-loadtest-k6`가 붙는다. Envoy access log에서 이 값으로 부하 요청을 거른다.
- threshold를 하나라도 통과하지 못하면 k6는 종료 코드 99로 끝난다. `server_errors`가 5%를 넘으면 30초 뒤부터 바로 멈춘다(`abortOnFail`).
- 시작 시각과 끝 시각(KST)을 적어 둔다. Prometheus 구간 값과 Grafana 캡처의 범위로 쓴다.

## JFR

운영 이미지는 `eclipse-temurin:25-jre`라 `jcmd`가 없다. `loadtest/jfr.sh`가 `kubectl debug`로 JDK 이미지(`eclipse-temurin:25-jdk`)의 임시 컨테이너(ephemeral container)를 붙인다. 임시 컨테이너는 앱 컨테이너의 PID namespace를 같이 쓰고(`--target=app`), 앱과 같은 uid 10001·gid 999로 돈다. 그래서 jcmd가 앱 JVM(PID 1)에 붙고, JFR 파일은 `/proc/1/root/tmp`로 읽는다. 앱 컨테이너에는 exec하지 않는다.

```bash
# k6 시작 전
loadtest/jfr.sh --context hapbang-k3s start lecture-app
# k6가 끝난 뒤
loadtest/jfr.sh --context hapbang-k3s dump lecture-app "$HOME/.hapbang/load-tests/jfr"
loadtest/jfr.sh --context hapbang-k3s stop lecture-app
```

- 대상은 JVM 앱 5개(member, appearance, lecture, billing, chat)다. customer-support-app은 운영에서 GraalVM Native Image라 빌드에 `--enable-monitoring=jfr,jcmd`를 넣어야 하며, 지금은 지원하지 않는다([GraalVM jcmd](https://www.graalvm.org/jdk24/reference-manual/native-image/debugging-and-diagnostics/jcmd/)).
- 기록 설정은 `settings=default`다. Oracle 문서 기준 오버헤드는 1% 안팎이다. 버퍼는 앱 컨테이너 안의 native 메모리를 쓰므로 limit에 가까운 앱은 대상 앱 메모리를 같이 본다.
- 임시 컨테이너는 지울 수 없다. `sleep 3600`이 끝나면 종료 상태로 pod에 남고, 다음 배포로 pod가 바뀌면 사라진다. 임시 컨테이너에는 resource limit을 줄 수 없어, jcmd heap을 `-Xmx32m`로 묶는다.
- 임시 컨테이너는 붙인 뒤 1시간이 지나면 끝난다. 측정이 그 안에 끝나지 않으면 `dump`할 수 없으니, 측정 직전에 `start`한다.
- 처음 붙일 때 node가 JDK 이미지를 받는다(수백 MB).
- jcmd는 attach 파일을 만든 뒤 앱 JVM에 SIGQUIT를 보낸다. 임시 컨테이너의 gid가 앱과 달라 파일을 못 만들면, JVM은 SIGQUIT를 thread dump 요청으로 받아 앱 로그에 전체 thread dump를 찍는다(2026-11-05 lecture-app, gid 10001로 붙였을 때 10초 동안 6번). `jfr.sh`는 jcmd를 부르기 전에 gid를 확인해 다르면 멈춘다.
- `.jfr` 파일은 레포에 커밋하지 않는다. 결과 문서에는 `jfr` CLI(로컬 JDK 25)로 뽑은 표를 넣는다.

```bash
F=~/.hapbang/load-tests/jfr/lecture-app-<시각>.jfr
jfr summary "$F"
jfr view hot-methods "$F"
jfr view gc-pauses "$F"
jfr view allocation-by-class "$F"
jfr view contention-by-site "$F"
```

## 지표 수집

### Prometheus 구간 값

scrape 간격이 1분이라 `rate`·`increase`의 범위는 2분 이상으로 둔다(`[1m]`은 sample이 하나뿐이라 빈 결과가 나온다). 본 측정 10분이 끝난 시각(k6 시작 + 워밍업 2분 + 10분)을 `END`(Unix 초)로 두고 `[10m]`으로 조회해, k6 본 측정과 같은 구간을 본다.

```bash
kk() { kubectl --context hapbang-k3s "$@"; }
prom() {
  kk -n hapbang-platform exec deploy/prometheus-server -c prometheus-server -- \
    wget -qO- "localhost:9090/api/v1/query?time=${END}&query=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$1")"
}
```

| 값 | PromQL |
| --- | --- |
| 앱 메모리 최대 / limit | `max_over_time(container_memory_working_set_bytes{pod=~"lecture-app-.*",container="app"}[10m])`, `container_spec_memory_limit_bytes{pod=~"lecture-app-.*",container="app"}` |
| 앱 CPU 평균·최대(core) | `avg_over_time(rate(container_cpu_usage_seconds_total{pod=~"lecture-app-.*",container="app"}[2m])[10m:1m])`, 같은 식의 `max_over_time` |
| heap 최대 | `max_over_time(sum(jvm_memory_used_bytes{app_kubernetes_io_name="lecture-app",area="heap"})[10m:1m])` |
| GC 정지 시간 합·횟수 | `sum(increase(jvm_gc_pause_seconds_sum{app_kubernetes_io_name="lecture-app"}[10m]))`, `..._count` |
| 최대 GC 정지 | `max_over_time(max(jvm_gc_pause_seconds_max{app_kubernetes_io_name="lecture-app"})[10m:1m])` |
| DB 연결 active·pending 최대 | `max_over_time(sum(hikaricp_connections_active{app_kubernetes_io_name="lecture-app"})[10m:1m])`, 같은 식의 `hikaricp_connections_pending` |
| DB 연결 획득 시간 평균·최대 | `sum(increase(hikaricp_connections_acquire_seconds_sum{app_kubernetes_io_name="lecture-app"}[10m])) / sum(increase(..._count[10m]))`, `max_over_time(max(hikaricp_connections_acquire_seconds_max{app_kubernetes_io_name="lecture-app"})[10m:1m])` |
| 스레드 최대 | `max_over_time(sum(jvm_threads_live_threads{app_kubernetes_io_name="lecture-app"})[10m:1m])` |
| 앱이 본 요청 수 | `sum(increase(http_server_requests_seconds_count{app_kubernetes_io_name="lecture-app",uri!~"/actuator.*"}[10m]))` |
| node CPU 평균·최대 | `avg_over_time((sum by (instance) (rate(container_cpu_usage_seconds_total{id="/"}[2m])) / sum by (instance) (machine_cpu_cores))[10m:1m])`, 같은 식의 `max_over_time` |
| PostgreSQL CPU 최대 | `max_over_time(sum(rate(container_cpu_usage_seconds_total{namespace="hapbang-platform",pod=~"postgres-.*",container!=""}[2m]))[10m:1m])` |
| Envoy p95(서버 쪽) | `histogram_quantile(0.95, sum by (le) (increase(envoy_http_downstream_rq_time_bucket{envoy_http_conn_manager_prefix=~"https-.*"}[10m])))` |

### CPU credit

AWS 콘솔이나 `AWS_PROFILE=devcos`(읽기 전용)로 본다. 같은 계정에 다른 팀 instance도 있으니 node의 사설 IP로 instance를 고른다. instance ID는 공개 레포에 적지 않는다.

```bash
export AWS_PROFILE=devcos
aws cloudwatch get-metric-statistics --region ap-northeast-2 --namespace AWS/EC2 \
  --metric-name CPUCreditBalance --dimensions Name=InstanceId,Value=<instance ID> \
  --start-time <시작 UTC ISO> --end-time <끝 UTC ISO> --period 300 --statistics Average
```

### Grafana 캡처

`https://hapbang.duckdns.org/grafana/`의 `합방` 폴더에서 측정 구간(워밍업 포함)으로 시간을 맞춰 찍는다. 캡처는 `docs/load-test/images/<시나리오>-<dashboard>.png`로 둔다. Grafana·Prometheus는 lecture-app과 같은 medium node에 있으니 측정 중에는 dashboard를 열지 않는다.

| dashboard (uid) | 변수 |
| --- | --- |
| 앱 개요 (`hapbang-apps`) | `app=<대상 앱>` |
| JVM·Spring Boot (`hapbang-jvm`) | `app=<대상 앱>` |
| Gateway (Envoy) (`hapbang-gateway`) | `route=<대상 앱>` |
| Kubernetes 자원 (`hapbang-kubernetes`) | 기본값 |

## 결과 문서 양식

`docs/load-test/<시나리오>.md`에 다음 순서로 쓴다.

1. **요약**: 합격 여부와 한 줄 결론
2. **조건**: 측정 날짜·시각(KST), 대상 커밋 SHA(이미지 태그), 앱 limit·JVM 옵션·GC 종류, 데이터 건수, trace 샘플링, k6 버전·실행 위치, 단건 응답 시간 기준값, 실행 직전 점검 결과
3. **시나리오**: 요청 비율, 부하 프로필, threshold, 스크립트 경로
4. **결과**: k6 요약(요청 이름별 p50·p95·p99, RPS, 오류율), Prometheus 구간 값, CPU credit 전후, 관측 stack 재시작
5. **JFR**: hot method, GC, 할당, lock 경합 표와 해석
6. **Grafana 캡처**
7. **한계와 다음 과제**

# 쿠폰 선착순 발급·계산 미리보기 (로컬)

쿠폰 선착순 발급 경쟁(회원 1,000명·재고 100장)과 계산 미리보기(quote)를 로컬에서 재고, 로컬 컨테이너에 장애를 넣어 동작을 기록했다(#813 5단계). 측정 규칙은 [README](README.md)에 있다. 이 문서의 수치는 **노트북 수치**다. 운영 수치가 아니며, Q41 통과 여부는 운영 측정으로 정한다. 운영 측정은 이번 이슈에서 하지 않았다(아래 "운영 측정").

## 요약

- **Q41 기준(로컬)은 3회 중 2회 통과.** 접수 p95는 669 ms(불합격) · 206 ms · 369 ms였다. 첫 실행은 앱 기동 직후 워밍업 한 번만 거친 상태였다. 세 번 모두 5xx 0, 과발급 0(ISSUED = 100), 회원 중복 0, PENDING 잔류 0, 대사 불일치 0이었고, 모든 접수가 2.3초 안에 ISSUED로 확정됐다(기준 2분).
- 매진 응답(409)은 서버에서 대부분 5 ms 안에 끝난다. 접수 시간은 접수된 100건(202)이 정한다. 이 100건은 Redis Lua 호출, 그리고 같은 요청 스레드에서 Kafka ACK를 기다리는 시간을 쓴다.
- 요청 스레드가 기다린 곳(JFR): Redis 응답 대기, Spring Data Redis `DefaultRedisScript.getSha1`의 lock(Lua를 부를 때마다 중첩 jar 안 스크립트 파일의 수정 시각을 확인한다), 매진마다 남기는 INFO 로그의 logback lock, Kafka ACK 대기. CPU는 병목이 아니었다.
- 재고 1,000장에 1,000명이 몰린 실행에서 소비자는 1,000건을 4.8초에 확정했다(초당 약 208건). 쿠폰 하나의 메시지는 key가 쿠폰 ID라 partition 하나로 가서, 소비 스레드 하나가 처리한다.
- Redis 메모리는 접수 1건에 약 138 B다(requests 105 B + users 33 B, 1,000건 기준). 설정 주석의 추정값은 약 250 B다.
- quote: 판매자 쿠폰만 가졌을 때는 강의 20개에서도 `optimal=false`가 0%이고 p95는 46 ms다. 모든 강의 대상 플랫폼 쿠폰 10장을 함께 가지면(로컬 실험, 아래 참고) 강의 10개에서 70%, 20개에서 100%가 `optimal=false`이고, p95는 52 ms와 71 ms다.
- 장애: Redis를 멈추면 발급 접수가 **60초 뒤에** 503을 받는다(Lettuce 기본 명령 제한 시간). 사용·취소(quote·예약·해제)는 그동안에도 정상이었다(I9). Redis를 다시 띄우자 30초 뒤 run_id 변경을 감지해 OPEN 쿠폰 67개를 모두 막았고, 복구 1~5에는 8.6초가 걸렸다. Kafka를 60초 멈추면 접수는 1.03초 뒤 202로 끝나고, Kafka를 다시 띄우고 44.6초 뒤 재발행으로 모두 ISSUED가 됐다. PostgreSQL을 멈추거나 멈춤 상태로 두어도(stop·pause) 접수는 35~40 ms로 계속 받았고, DB가 돌아온 직후 모두 확정됐다. DLT로 간 메시지는 없었다.

## 조건

| 항목 | 값 |
| --- | --- |
| 측정 시각 (KST) | 2026-11-10 10:02~10:33 |
| 대상 | lecture-app, `feature/tkt-813` 커밋 `a97748ac`의 `git archive`로 만든 bootJar. 같은 worktree에서 진행 중인 커밋 전 변경은 들어 있지 않다 |
| 실행 위치 | 개발 PC(macOS, Apple Silicon 10코어, 메모리 24 GiB). k6와 앱, Docker Desktop 컨테이너가 같은 PC에서 돈다 |
| JVM | JDK 25, `-Xmx224m -XX:+UseSerialGC -XX:ActiveProcessorCount=2`. 운영의 heap 상한과 GC(Serial)에 맞췄다. CPU 수를 2로 알려 줄 뿐이고 실제 CPU 사용은 제한하지 않는다 |
| 인프라 | `loadtest/k6/coupon/local-stack.sh up`이 띄운 컨테이너(`hapbang-lt-*`): PostgreSQL 18, Redis 8(운영과 같은 인자: AOF everysec, maxmemory 48mb, noeviction), Kafka 4.4.0 단일 노드, S3Mock. 개발용 `docker/compose.yaml`과 이름·볼륨·포트를 나눴다 |
| 앱 설정 | local 프로필. Kafka 리스너·토픽 생성을 켰다(`SPRING_KAFKA_LISTENER_AUTO_STARTUP=true` 등). `hapbang.coupon.*`·`lecture.*`는 모두 코드 기본값이다. quote 플랫폼 실험 때만 `HAPBANG_COUPON_PLATFORM_FUNDING_ENABLED=true`로 다시 띄웠다 |
| 인증 | 앱 포트로 `X-User-Id`·`X-User-Role`을 직접 넣었다([로컬 실행](../dev/local.md) "앱 하나만 확인"). Envoy·JWT 검증 구간은 지나지 않는다 |
| 부하 생성 | k6 v2.3.0 |
| 로그 | 앱 표준 출력을 파일로 받았다(평문 로그). 운영은 JSON 한 줄 로그다 |
| 운영 smoke | 하지 않았다(운영 측정은 이번 이슈에서 하지 않는다, 아래 "운영 측정") |

## 시나리오

### 선착순 발급 경쟁 `loadtest/k6/coupon/issue-race.js`

- 회원 N명(VU마다 다른 회원)이 같은 순간에 `POST /api/v1/coupons/{couponId}/issue-requests`를 한 번 보낸다(`per-vu-iterations`, VU 1,000개 × 1회). 202를 받은 회원은 `GET …/issue-requests/{issueRequestId}`를 0.5초마다 불러 ISSUED·REJECTED가 될 때까지 기다린다. 409 `COUPON_SOLD_OUT`은 접수 순간 확정된 거절로 센다.
- 지표: 접수 응답 시간(`http_req_duration{name:issue_accept}`), 5xx 비율(`server_errors`, 상태 조회 포함), 접수 → 확정 시간(`issue_settle_seconds`), 시작 → 확정 시간(`issue_settled_since_start_seconds`, 최대값이 "모든 접수가 확정될 때까지"), 결과 수(`issue_accepted`·`issue_issued`·`issue_rejected`·`issue_sold_out`·`issue_unsettled`).
- threshold(Q41): 접수 p95 ≤ 500 ms, 5xx < 1%, 확정 안 된 요청 0, 시작 → 확정 최대 ≤ 120초, check 99% 초과.
- k6의 `http_req_duration`에는 TCP 연결 시간이 들어가지 않는다. 1,000개 연결을 한꺼번에 여는 시간(로컬 p95 약 100 ms)은 `http_req_connecting`에 따로 남는다.
- 끝난 뒤 `loadtest/k6/coupon/verify-local.sh check <쿠폰ID> <M>`로 DB·Redis·지표를 확인한다. 쿼리는 그 스크립트에 있다.
  - I1: `coupon.member_coupon` 수 = `coupon.issued_count` = M이고 수량 이하
  - I2: `member_coupon`을 `member_id`로 묶어 2장 이상인 회원이 0
  - I3: Redis `coupon:{id}:requests`의 요청 ID 중 `coupon.coupon_issue_request`에 없는 것(PENDING 잔류)이 0. `coupon_dlt_log` 0
  - 대사: `/actuator/prometheus`의 `coupon_reconciliation_mismatches` 세 종류가 모두 0(대사 배치는 1분 주기)
  - 소비 처리량: `coupon_issue_request`의 `min(accepted_at)` ~ `max(processed_at)`, 접수 → 확정 지연(`processed_at − accepted_at`)의 p95·최대
- Redis 메모리: `verify-local.sh sample <쿠폰ID>`를 k6와 함께 띄워 `MEMORY USAGE`(requests·users)·항목 수·`used_memory`를 0.2초마다 남긴다.

### 계산 미리보기 `loadtest/k6/coupon/quote.js`

- `POST /api/v1/coupons/me/quote`, `selections`를 비워 추천을 받는다. 강의 수(`SIZES`, 기본 1·5·20)마다 `constant-arrival-rate` 시나리오를 차례로 돌린다.
- 지표: 강의 수별 응답 시간, `quote_not_optimal{size}`(추천이 `calculate.max-search-nodes`에 걸려 찾은 최선을 돌려준 비율).
- threshold: 강의 수별 p95 < 500 ms, 5xx < 1%, check 99% 초과.
- 데이터: 강의 20개(가격 11,000~30,000 HP), 강의마다 판매자 쿠폰 3장(정률 10%·20% 중첩 가능, 30% 단독). 회원 10명(3000000~3000009)이 60장을 모두 받았다.
- 플랫폼 실험: 플랫폼 쿠폰은 billing 예산 연동 없이 열 수 없어 API로 만들 수 없다. 모든 강의 대상 정률 플랫폼 쿠폰(6~15%, 상한 1,300~4,000 HP, 중첩 가능, CLOSED)과 발급 쿠폰을 로컬 DB에 SQL로 직접 넣었다. 회원 3100003·3100006·3100010이 각각 3·6·10장을 가졌고, 앱은 `platform-funding-enabled=true`로 다시 띄웠다. 운영에서는 재현하지 않는다.

## 결과 (로컬)

### 선착순 발급: 회원 1,000명 · 재고 100장

요약 JSON: [`results/coupon-issue-race-local-20261110-1002.json`](results/coupon-issue-race-local-20261110-1002.json), [`-1004`](results/coupon-issue-race-local-20261110-1004.json), [`-1005`](results/coupon-issue-race-local-20261110-1005.json).

| 실행 (KST) | 접수 p50 | p95 | p99 | 최대 | 5xx | 결과 | 모두 확정 | 판정 |
| --- | ---: | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 10:02:54 (앱 기동 1분 뒤, 워밍업 1회 뒤) | 227.6 | **669.5** | 690.9 | 692.3 | 0 | 202 100 · 409 매진 900 → ISSUED 100 | 2.29초 | 불합격(p95) |
| 10:04:58 | 150.9 | **205.8** | 229.5 | 231.0 | 0 | 같음 | 1.26초 | 합격 |
| 10:05:10 | 140.8 | **368.9** | 389.8 | 396.1 | 0 | 같음 | 1.21초 | 합격 |

(접수 시간 단위 ms)

- 세 번 모두 I1·I2·I3을 확인했다: `member_coupon` 100 = `issued_count` 100, 회원 중복 0, PENDING 잔류 0, DLT 0, `coupon_reconciliation_mismatches` 0(DB_OVER_QUANTITY·NEGATIVE_STOCK·UNEXPLAINED), Redis `stock=0`·`users=100`.
- 서버가 잰 접수 시간(`http_server_requests`, 10:04:58 실행의 증가분): 202 100건은 모두 250 ms 이하였고 72건이 100 ms 이하였다. 409 900건은 684건이 5 ms 이하, 모두 250 ms 이하였다. 클라이언트 값과 서버 값의 차이는 Tomcat 요청 대기열과 k6가 같은 PC에서 돈 몫이다.
- 접수 → DB 확정 지연: 최대 1.0~2.1초, p95 0.97~1.99초. 확정 시간이 1초 단위로 몰려 있는 것은 상태 조회 간격(0.5초) 때문이다.
- 기동 직후 워밍업 실행(10:02:36, 회원 300명 · 재고 50장)의 접수 p95는 401 ms였다. 같은 앱에서 실행할수록 빨라졌다. 운영 앱은 오래 떠 있으므로 두 번째·세 번째 값이 운영에 가깝다고 보지만, 첫 실행도 기록에 남긴다.

### 소비 처리량과 Redis 메모리: 회원 1,000명 · 재고 1,000장

[`results/coupon-issue-race-local-1000x1000-20261110-1005.json`](results/coupon-issue-race-local-1000x1000-20261110-1005.json)

| 항목 | 값 |
| --- | --- |
| 접수 | 202 1,000건, p50 321.9 · p95 378.9 · 최대 414.7 ms, 5xx 0 |
| 확정 | 1,000건 모두 ISSUED. 첫 접수 → 마지막 확정 4.81초(초당 약 208건). 접수 → 확정 지연 p95 3.96초, 최대 4.39초. 시작 → 모두 확정 5.24초 |
| I1·I2·I3 | `member_coupon` 1,000 = `issued_count` 1,000, 중복 0, PENDING 잔류 0, DLT 0 |
| Redis `coupon:{id}:requests` | 105,182 B / 1,000건 = 105 B(hashtable 인코딩). 요청 ID는 18자다. UUID(36자)를 쓰면 18 B쯤 늘어난다 |
| Redis `coupon:{id}:users` | 33,289 B / 1,000명 = 33 B(hashtable) |
| 쿠폰마다 고정 | `issue-control` 242 B, `stock` 41 B |
| 100건일 때 | requests 7,913 B(79 B/건, listpack), users 449 B(intset). 항목이 128개(hash)·512개(set)를 넘으면 인코딩이 바뀌어 건당 바이트가 커진다 |

접수 기록은 DB에 ISSUED로 확정된 뒤 기록 정리 배치가 지운다. 위 값은 정리 전 최대값이다.

### 계산 미리보기

| 데이터 | 강의 수 | 요청 | p50 | p95 | p99 | 최대 | `optimal=false` |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 판매자 쿠폰만(강의마다 3장) | 1 | 20 RPS 2분 | 8.5 | 14.2 | 21.7 | 81.1 | 0% |
| | 5 | 20 RPS 2분 | 12.8 | 27.3 | 42.3 | 246.7 | 0% |
| | 20 | 20 RPS 2분 | 22.5 | 46.3 | 95.3 | 224.9 | 0% |
| + 플랫폼 쿠폰 10장(로컬 실험) | 5 | 10 RPS 1분 | 20.0 | 32.5 | 44.4 | 185.6 | 0% |
| | 10 | 10 RPS 1분 | 40.7 | 51.8 | 66.8 | 132.4 | 70.0% |
| | 20 | 10 RPS 1분 | 53.4 | 71.2 | 99.5 | 192.9 | 100% |

(단위 ms. 요약 JSON: [`results/coupon-quote-local-20261110-1011.json`](results/coupon-quote-local-20261110-1011.json), [`results/coupon-quote-local-platform-20261110-1018.json`](results/coupon-quote-local-platform-20261110-1018.json))

- 5xx 0, check 실패 0.
- 판매자 쿠폰은 특정 강의 대상이라 항목끼리 쿠폰을 나눠 쓰지 않는다. 탐욕해가 곧 상한이 되어 분기 한정 탐색이 바로 끝난다. 그래서 지금(`platform-funding-enabled=false`)은 `max-search-nodes`에 닿을 수 없다. 강의 20개의 비용은 강의마다 한 번씩 부르는 강의 조회 내부 API(같은 앱 HTTP) 20번이 차지한다.
- 플랫폼 쿠폰 3장·6장을 가진 회원은 강의 5·10·20개에서 모두 `optimal=true`였다. 이 데이터에서는 단독 판매자 쿠폰(30%)이 플랫폼 쿠폰보다 커서 플랫폼 쿠폰이 추천되지 않았다. 10장이면 강의 10개부터 상한(100,000)에 걸린다. 상한에 걸린 요청도 단건 0.1~0.16초, 10 RPS에서 p95 71 ms였다. 상한에 걸린 결과가 최적해보다 얼마나 작은지는 재지 않았다.

### 서버 자원 (JFR, 로컬)

10:02:31부터 15분 28초 동안 기록했다(`-XX:StartFlightRecording`, `settings=default`). `loadtest/jfr.sh`는 k8s pod에 임시 컨테이너를 붙이는 스크립트라 로컬 앱에는 쓸 수 없어, JVM 옵션으로 기록하고 `jcmd JFR.dump`로 뽑았다.

**요청 스레드가 기다린 시간**(`jdk.ThreadPark`, 요청 스레드 `http-nio-*`, 유휴 대기 `TaskQueue.poll` 제외). 경쟁 구간별 합계다.

| 구간 | Redis 응답 대기 (`LettuceConnection.await`) | `DefaultRedisScript.getSha1` lock | logback 쓰기 lock | Kafka ACK 대기 (`KafkaCouponIssueEventPublisher.publish`) | Hikari 연결 대기 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 10:02:54 (1,000×100, 첫 실행) | 76.4초 / 404회 | 10.1초 / 145회 | 8.4초 / 154회 | 2.7초 / 100회 | - |
| 10:04:58 (1,000×100) | 5.1초 / 52회 | - | 0.4초 / 18회 | 0.5초 / 19회 | - |
| 10:05:10 (1,000×100) | 11.8초 / 201회 | - | **42.2초 / 212회** | - | - |
| 10:05:24 (1,000×1,000) | 43.7초 / 886회 | 14.9초 / 365회 | - | 10.4초 / 86회 | 7.0초 / 156회 |

- Redis 서버가 잰 명령 시간은 짧다(`INFO commandstats`: `evalsha` 평균 70 µs). 요청 스레드의 Redis 대기는 Redis 실행이 아니라, 연결 하나를 같이 쓰는 Lettuce의 응답 대기 줄과 Docker Desktop 포트 전달 구간에서 생긴 것으로 본다. 운영(k3s, 같은 cluster 안 Service)과 다르다.
- `getSha1` lock: Spring Data Redis 4.1.1의 `DefaultRedisScript.getSha1()`은 스크립트마다 lock을 잡고 `ResourceScriptSource.isModified()`로 스크립트 파일의 수정 시각을 본다. 쿠폰 Lua는 `ClassPathResource`라 운영(`java -jar app.jar`)에서도 매 호출마다 중첩 jar의 URL을 연다. CPU hot method의 `java.net.URL.<init>`, `NestedJarFile.hasEntry`가 이것이다. 같은 Lua를 부르는 요청은 이 lock에서 한 줄로 선다.
- logback lock: `CouponExceptionHandler`는 매진(409)마다 INFO 한 줄을 남긴다. 회원 900명이 1초 안에 매진을 받으면 900줄을 동기로 쓴다. 로컬은 파일, 운영은 JSON 표준 출력이라 쓰는 비용이 다르다.
- CPU: 경쟁 구간의 요청 스레드 CPU 표본은 30초에 106개뿐이다. CPU가 아니라 기다림이 접수 시간을 정했다. 그 106개는 Tomcat 23%, Spring 14%, Spring Web 13%, Micrometer 12%, Spring Security 11%였다.
- GC: 15분 동안 371회, 합 3.07초, 중앙값 3.3 ms. major GC는 없었다.

## 장애 기록 (로컬 컨테이너만)

`docker stop`·`docker pause`는 `local-stack.sh`가 띄운 `hapbang-lt-*` 컨테이너에만 했다. 운영 Redis·Kafka·PostgreSQL은 건드리지 않았다(Q40).

| 시나리오 | 기대 | 실제 | 걸린 시간 |
| --- | --- | --- | --- |
| Redis stop(10:22:27) → 발급 접수 | 503 `COUPON_ISSUE_STORE_UNAVAILABLE` | 503. 단 **60.05초 뒤**에 응답했다. 두 번째 요청은 70초 안에 응답이 없었다(curl 제한) | 60초 |
| Redis stop → 발급 요청 상태 조회 | DB 결과가 있으면 그 결과, 없으면 UNKNOWN | DB에 있는 요청은 ISSUED(0.03초). 없는 요청은 UNKNOWN(60.02초) | 0.03초 / 60초 |
| Redis stop → 받을 수 있는 쿠폰 목록·상세 | 계속 응답 | 200, 0.03초·0.01초 | - |
| Redis stop → quote·내부 예약·내부 해제(I9) | 계속 동작 | quote 200(0.05초), 예약 200 `RESERVED`(0.04초), 해제 200 `AVAILABLE`(0.05초) | - |
| Redis stop → 배치 | 다음 회차로 미룸 | 재발행·대사·프로세스 점검이 쿠폰 하나마다 60초씩 막혔다(10:24:10 couponId=1, 10:25:10 couponId=2) | 쿠폰당 60초 |
| Redis start(10:26:02, AOF 보존, run_id 바뀜) | 프로세스 점검이 run_id 변경을 감지해 `hold=RECOVERY` | 10:26:31에 감지, OPEN 쿠폰 67개 모두 `ready=0`·`hold=RECOVERY`, 쿠폰마다 `[COUPON_RECOVERY_REQUIRED]` ERROR | 재기동 뒤 30초(주기 1분) |
| 복구 1~5 (쿠폰 77) | BLOCKED → BOUNDARY_FIXED → RESULTS_CHECKED → REBUILT → RESUMED, 수량 대사 일치 | 1 차단 200(`requestsLost=true`) → 2 경계는 409 "…7967ms 뒤에" 4번 뒤 200 → 3·4·5 각 0.04~0.06초. 재개 근거 `quantity=50,dbIssued=1,redisStock=49,inProgress=0,epoch=2`. 재개 뒤 새 회원 발급 ISSUED | 8.6초(경계 대기 8초) |
| Redis pause 28초(10:27:14~42) | 접수 지연 뒤 처리 | 일시정지 중 보낸 접수는 풀릴 때까지 기다렸다가 202(27.3초) → ISSUED. 그동안 quote·예약·해제 정상. run_id가 같아 복구 없음 | 27.3초 |
| Kafka stop(10:28:00) → 접수 20건 | 202 ACCEPTED(재고 차감), 상태 PENDING | 202, 접수 1.03초(`publish-ack-timeout` 1초를 다 기다림). `coupon_issue_publish_failures_total{result="timed_out"}` 20 | 1.03초 |
| Kafka start(10:29:01) → 확정 | 재발행 배치가 같은 요청 ID로 다시 보내 ISSUED | 10:29:45 재발행 회차(`republished=20, acked=20`) → 20건 모두 ISSUED. 중복·잔류 0 | 재기동 뒤 44.6초, 접수 뒤 104초 |
| PostgreSQL pause 85초(10:30:00~31:25) → 접수 20건 | 접수는 Redis만 써서 계속, 소비는 DB 복구 뒤 | 접수 202(p95 42 ms). 상태 조회는 Hikari 연결 대기 30초 뒤 500(5xx 40건). 강의 목록도 30초 뒤 500. 풀린 직후 20건 모두 ISSUED, DLT 0 | 풀린 뒤 0.1초 |
| PostgreSQL stop 60초(10:31:56~32:56) → 접수 20건 | 소비 재시도 → 한도를 넘으면 DLT | 접수 202(p95 35 ms). 소비자는 DB 연결에 실패해 같은 offset으로 두 번 되돌아갔다(10:31:57, Hikari 연결 대기 30초 뒤 10:32:28). 재시도 3회를 다 쓰기 전에 DB가 돌아와 DLT로 가지 않았다. DB가 돌아오자 재발행 배치가 아직 확정 전인 요청을 다시 보냈고, 소비자가 중복 17건을 `duplicate`로 무시했다. 20건 모두 ISSUED, 대사 불일치 0 | DB 복귀 뒤 1초 안 |
| 강의 쿠폰 회로 → 202 → DEAD | 쿠폰 내부 API 실패가 이어지면 회로가 열리고 주문은 202(결제 처리 중), 재전송이 상한에 닿으면 DEAD | **재지 못했다.** 강의와 쿠폰이 같은 JVM·같은 DB라 DB를 멈추면 둘 다 멈춘다. 계산(calculate)은 성공하고 예약만 실패하게 하려면 중간 프록시(새 도구)나 코드 hook이 필요하다. 주문의 HP 보류에는 billing-app도 필요하다 | 설정으로 계산한 값: 시도 상한 10 × 재전송 주기 1분(cron 30초, `coupon-work-retry-after` 30초) ≈ 9~10분, 회로가 열린 동안은 고르지 않으므로 그만큼 더 |
| 강의 이벤트 재발행 (Kafka 중단 뒤) | 결제·취소 완료 이벤트가 outbox에서 재발행 | 재지 못했다. 주문 결제에 billing-app이 필요하다 | - |

- 장애 실험 전후 모든 쿠폰에서 `coupon_dlt_pending` 0, `coupon_reconciliation_mismatches` 0이었다.
- Redis 중단 동안 접수가 60초씩 걸린 것은 Lettuce가 끊긴 동안 명령을 쌓아 두고 기본 명령 제한 시간(60초)까지 기다리기 때문으로 본다. 레포는 `spring.data.redis.timeout`을 정하지 않는다. 운영에서 같은 일이 생기면 Tomcat 요청 스레드(최대 200)가 60초씩 묶여 강의 API까지 느려질 수 있다.
- Redis 프로세스가 바뀌면 OPEN·SUSPENDED 쿠폰이 모두 막히고 복구는 쿠폰마다 수동이다. 로컬에는 실험용 쿠폰이 67개 있어 66개는 막힌 채로 두었다.

## 한계

- 노트북 한 대에서 k6·앱·컨테이너가 함께 돈다. Redis·Kafka·PostgreSQL은 Docker Desktop VM 안에 있고 포트 전달을 거친다. 운영의 t3 CPU credit, 노드 간 네트워크, Envoy·JWT 검증 구간이 없다.
- 인증은 사용자 헤더를 직접 넣었다. 운영에서는 회원마다 access token이 필요하다(아래).
- k6의 응답 시간에는 연결 시간이 빠진다. 로컬에서 1,000개 연결을 동시에 여는 시간은 p95 약 100 ms였다. 운영(TLS)에서는 더 크다.
- 첫 실행(불합격)과 다음 실행(합격)의 차이는 JIT·연결 풀이 데워진 정도로 본다. 운영 앱은 오래 떠 있지만, 운영에서도 반복해 재야 한다.
- 소비자 처리량(초당 약 208건)은 쿠폰 하나(partition 하나)의 값이다. 여러 쿠폰이 동시에 열리면 partition 3개에 나뉜다.
- quote 플랫폼 실험은 SQL로 넣은 CLOSED 플랫폼 쿠폰과 `platform-funding-enabled=true`로 했다. billing 계약 뒤의 실제 데이터와 다를 수 있다.
- 주문 예약(reserve) 부하와 결제·취소 이벤트(사용·복원)는 billing-app 없이 재지 못했다. I9은 내부 예약·해제 API를 직접 불러 확인했다.
- 운영 Redis 메모리 한도(48 MB)는 다른 기능(채팅 Pub/Sub)과 같이 쓴다. 건당 바이트는 쿠폰 key만 잰 값이다.
- 노트북 한 대에서 k6 1,000 VU·앱·Docker VM(3.8 GiB, 다른 프로젝트 컨테이너 포함)이 함께 돌아 메모리 압박이 있었다. 다시 잴 때는 VM 메모리를 늘리고 다른 스택을 내린 뒤 N을 작게 시작한다.

## 로컬 실행 방법

모든 명령은 레포 루트에서 실행한다. 운영 주소는 쓰지 않는다. 스크립트 기본 주소는 `http://localhost:18084`이고, 헤더 인증(`AUTH=header`)은 localhost가 아니면 멈춘다.

```bash
# 1. 인프라(포트 25432·26379·29092·29090)와 앱(18084)
loadtest/k6/coupon/local-stack.sh up
./gradlew :lecture-app:bootJar
loadtest/k6/coupon/local-stack.sh app apps/lecture-app/build/libs/app.jar > /tmp/lecture-app.log 2>&1 &
# JFR을 같이 남기려면: LT_JAVA_OPTS="-XX:StartFlightRecording=name=loadtest,settings=default,filename=<경로>.jfr" ... app ...

# 2. 테스트 데이터(앱 API로 만든다. 판매자 900001, 관리자 900000)
L=$(loadtest/k6/coupon/setup-local.sh lecture)          # 강의 등록 → 검수 승인(ON_SALE)
C=$(loadtest/k6/coupon/setup-local.sh coupon "$L" 100)  # 판매자 쿠폰 신청 → 심사 요청 → 관리자 승인(OPEN)

# 3. 선착순 경쟁(먼저 PROFILE=smoke로 도는지 본다). 같은 쿠폰에 다시 돌리면 MEMBER_ID_START를 바꾼다
k6 run -e PROFILE=smoke -e COUPON_ID="$C" -e MEMBER_ID_START=500 loadtest/k6/coupon/issue-race.js
C=$(loadtest/k6/coupon/setup-local.sh coupon "$L" 100)
loadtest/k6/coupon/verify-local.sh sample "$C" 0.2 > /tmp/redis-sample.csv &
k6 run -e COUPON_ID="$C" -e N=1000 -e EXPECTED_M=100 \
  --summary-export /tmp/coupon-issue-race.json loadtest/k6/coupon/issue-race.js
kill %2   # sample 종료
loadtest/k6/coupon/verify-local.sh check "$C" 100

# 4. quote 데이터: 강의 20개 × 쿠폰 3장, 회원 10명이 모두 받는다
for i in $(seq 1 20); do loadtest/k6/coupon/setup-local.sh lecture $((10000 + i * 1000)); done > /tmp/lectures.txt
for l in $(cat /tmp/lectures.txt); do
  for spec in "10 true" "20 true" "30 false"; do loadtest/k6/coupon/setup-local.sh coupon "$l" 100 $spec; done
done > /tmp/coupons.txt
# macOS seq는 큰 수를 3e+06으로 찍는다. -f %.0f를 준다
for m in $(seq -f %.0f 3000000 3000009); do for c in $(cat /tmp/coupons.txt); do echo "$c $m"; done; done \
  | xargs -P 10 -L 1 loadtest/k6/coupon/setup-local.sh issue
k6 run -e LECTURE_IDS=$(paste -sd, /tmp/lectures.txt) -e RATE=20 -e DURATION=2m loadtest/k6/coupon/quote.js

# 5. 장애 주입(로컬 컨테이너만)
docker stop hapbang-lt-redis;  docker start hapbang-lt-redis
docker pause hapbang-lt-postgres; docker unpause hapbang-lt-postgres
# 정리
loadtest/k6/coupon/local-stack.sh down
```

- 4의 `spec`은 bash처럼 단어로 나뉘어야 한다. zsh에서는 `bash -c`로 돌린다.
- 로컬 프로필은 회원·고객센터 토픽이 없어 강의의 다른 Kafka 리스너가 `UNKNOWN_TOPIC_OR_PARTITION` WARN을 계속 남긴다. 측정에는 영향이 없다.
- 복구 API 순서: `POST /api/v1/coupons/admin/{couponId}/recovery` → `…/{recoveryId}/boundary`(409면 안내한 시간 뒤 다시) → `results` → `rebuild` → `resume` `{"resumeBasis": "…"}`(쿠폰 설계 contracts.md "관리자 운영 API").

## 운영 측정

이번 이슈에서는 하지 않음(2026-11-10 사용자 결정, 로컬 1,000 VU 실행 중 노트북 메모리 압박). 운영 발급 개방 전에 별도로 측정한다. 운영에는 아무 요청도 보내지 않았다.

운영 실행은 Q40대로 팀 공지 뒤, 전용 테스트 데이터로 선착순 발급(issue-race)만 한다. 장애 주입과 reserve 부하는 하지 않는다. 그때 아래 항목을 채운다.

| 항목 | 값 |
| --- | --- |
| 측정 시각 (KST) | |
| 대상 이미지 | |
| 실행 직전 점검 | |
| CPU credit 전후 | |
| 계정·토큰 준비 | |
| 테스트 강의·쿠폰 ID | |
| 접수 p50·p95·p99 | |
| 5xx | |
| 결과(202·409·ISSUED·REJECTED) | |
| 모두 확정까지 | |
| I1·I2·I3·대사 확인 | |
| Redis `MEMORY USAGE` | |
| JFR(`loadtest/jfr.sh`) | |
| 정리(쿠폰 CLOSED 등) | |
| Q41 판정 | |

## 다음 과제

- 운영 발급 개방 조건은 쿠폰 [docs/operations.md](../../apps/lecture-app/boundedContext/coupon/docs/operations.md) "운영 발급 개방 체크리스트"를 따른다.
- Redis 명령 제한 시간: `spring.data.redis.timeout`이 없어 Redis 중단 때 요청 스레드가 60초 묶인다. 짧은 제한 시간(예: 1~2초)이나 끊긴 동안 명령을 바로 거절하는 설정을 검토한다. 채팅 앱도 같은 Redis 설정 방식을 쓰는지 함께 본다.
- Lua 호출마다 `getSha1` lock과 스크립트 파일 수정 시각 확인을 한다. 스크립트를 기동 때 문자열로 읽어 두는 방식(예: `StaticScriptSource`)을 검토한다.
- 매진 409마다 남기는 INFO 로그의 양과 logback lock 경합을 운영 로그 형식(JSON)에서 다시 본다.
- 운영 측정 뒤 접수 p95가 기준에 가까우면, 접수 직후 Kafka ACK 대기(`publish-ack-timeout`)가 요청 스레드에 들어가는 몫을 나눠 본다.
- 플랫폼 쿠폰 계약 뒤 실제 데이터로 `calculate.max-search-nodes`에 걸린 결과와 최적해의 차이를 잰다.

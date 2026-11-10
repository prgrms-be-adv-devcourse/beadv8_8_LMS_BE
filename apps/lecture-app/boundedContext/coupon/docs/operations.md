# 운영과 검증

## 복구와 대사

정상 수량 식은 `총수량 = DB 발급 완료 + 진행 중/거절 보상 대기 예약 + Redis 가용 재고`다. 동일 요청은 전달 위치와 관계없이 한 번만 센다. 동시 쓰기 중 서로 다른 시점의 조회로 장애를 확정하지 말고 재확인하거나 발급을 차단해 비교한다.

Redis 재시작·유실 시 다음 순서를 지킨다.

1. DB에 복구 실행·epoch를 기록하고 신규 발급과 이전 작업자를 차단한다. 일부 AOF와 준비 키가 살아 있어도 자동 정상 재개하지 않는다. Redis 프로세스 변경 감지와 준비 재검증을 연결한다.
2. 진행 중 생산자를 정리하고, 남아 있는 requests 중 DB에 없는 요청을 전부 재발행한 뒤 Kafka 파티션 경계와 retry/DLT 경계를 저장한다. 경계는 차단 시각 + 전송 수명(`publish-ack-timeout` + max(`producer-delivery-timeout`, `republish.ack-timeout`) + `recovery.boundary-grace`, 기본값으로 8초)이 지난 뒤에만 확정한다. 그 전에는 409로 남은 시간을 알린다. 쿠폰 producer의 `delivery.timeout.ms`는 짧게 고정해 이 수명이 상한이 되게 한다. Redis가 유실돼 requests가 없으면 재발행할 수 없으므로 그 요청들은 UNKNOWN으로 남긴다.
3. 식별 가능한 요청을 DB 완료 또는 영구 거절로 정리한다. 복구 중 처리할 이전 epoch의 허용 범위를 고정한다. DB 세대 확인과 INSERT를 원자적으로 수행한다.
4. 미처리(Kafka lag·DLT PENDING·REPLAYING) 0과 예산을 확인하고 확정 DB 기준으로 재고·회원 중복·요청 결과·issue-control을 재구축한다. 재구축은 `REBUILDING` 선점(claimedAt 토큰, `recovery.claim-lease`)을 DB에 커밋한 뒤 Redis를 바꾼다. Lua 펜스: rebuild는 issue-control의 `recoveryId`가 이 복구와 같고 `ready=0`일 때만 실행한다.
5. 새 epoch를 활성화한다. 재개도 `RESUMING` 선점 뒤 진행하며 activate는 `recoveryId`가 일치해야 하고 멱등이다. 차단은 복구 ID가 단조 증가할 때만 `recoveryId`를 덮고, 작은 ID가 오면 차단만 한다(늦은 차단이 새 복구 토큰을 덮지 못한다). 이전 epoch의 늦은 전달·보상은 기존 결과만 재생하거나 격리하고 새 재고를 변경하지 않는다.

복구 실행(run)의 단계는 `BLOCKED` → `BOUNDARY_FIXED` → `RESULTS_CHECKED` → `REBUILDING` → `REBUILT` → `RESUMING` → `RESUMED`다. 예외 경로:
- 재구축(4) 전에는 운영자가 취소할 수 있다. run은 `ABORTED`, 차단(`hold=RECOVERY`)은 유지, 새 run을 만들 수 있다. 같은 취소 재요청은 200 멱등이다. 취소된 run이 유실 의심(`requestsLost`)이면 새 run이 그 표식을 이어받는다.
- 재개(5) 중 Redis 결과 불명이면 run은 `RESUMING`에 남고 재개만 재시도한다(재구축은 그 사이 들어온 접수를 지울 수 있어 금지). 단, 살아 있는 선점이 없고 issue-control이 사라졌거나 Redis `run_id`가 바뀐 것이 확인되면 그때만 취소를 허용한다. 그때는 먼저 현재 복구 ID로 `ready=0`·`hold=RECOVERY`를 쓰고 확인한 뒤 `ABORTED`(사유 `RESUME_UNKNOWN_<state>`)로 남기고, 새 복구를 `requestsLost=true`로 처음부터 한다(Redis에 쓴 것이 이미 없으므로 지울 위험이 없다).
- `requestsLost` 복구를 거친 쿠폰에서 Redis·DB 어디에도 없는 요청 ID의 상태 조회는 그 쿠폰의 Redis 기록 보관 기간까지 UNKNOWN이다. 보관 기간은 issue-control `keysExpireAt`이고, 없으면 DB 발급 종료 + `keys-expire-margin`이다. 그 뒤는 NOT_FOUND. 시간만으로 정한 별도 창(unknown-window)은 두지 않는다. 재개 뒤 이전 epoch로 온 이벤트는 소비자가 STALE로 DLT 격리한다.
- Redis 프로세스 변경은 prepare 때 issue-control에 기록한 `runId`와 `INFO server`의 `run_id`를 배치(`recovery.process-check-interval`)로 비교해 감지한다. 다르면 OPEN·SUSPENDED 쿠폰을 `hold=RECOVERY`로 막고 쿠폰마다 수동 복구한다.
- 차단·재구축은 hold를 `RECOVERY`로 덮어써 기존 `RECONCILE`·`BACKLOG`가 사라진다(재구축이 수량을 다시 맞춘다). 재개는 `RECOVERY`만 지운다.

AOF everysec에서 Redis에만 있던 접수가 사라질 수 있다. 이때 요청을 무조건 REJECTED나 ISSUED로 만들지 않는다. UNKNOWN과 운영 확인을 유지하며 증명되지 않으면 자동 재개하지 않는다. DB 총량 보장은 유실된 선착순 순서 복원을 뜻하지 않는다.

참고: [Redis 영속성](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/), [Lua 실행](https://redis.io/docs/latest/develop/programmability/eval-intro/).

## 작업자와 배치

기존 @ConditionalOnSchedulingEnabled + UseCase 패턴을 따른다. 복수 인스턴스 선점과 업무 멱등성을 함께 적용한다. 주기는 설정으로 관리하며 아래 값은 확정 운영 수치가 아니다.

| 작업 | 처리 |
| --- | --- |
| 재발행 | OPEN·SUSPENDED·정리 전 CLOSED 쿠폰의 requests를 HSCAN(묶음 `republish.scan-count`)해 접수 뒤 `republish-after`가 지났는데 DB에 없는 요청을 같은 ID로 Kafka에 다시 발행. PUBLISHED 표식이 있어도 DB에 없으면 재발행. COMPENSATED·DLT PENDING·REPLAYING·현재 epoch보다 작은 기록은 보내지 않음. 매 전송 직전에 issue-control의 ready·epoch를 다시 확인(복구 중이면 건너뜀). 한 쿠폰에서 연속 실패가 `republish.max-consecutive-failures`에 이르면 그 쿠폰의 남은 전송을 멈추고 다음 쿠폰으로(쿠폰별 RepublishSummary에 끝난 이유, WARN). DB 미확정 요청이 남은 쿠폰은 `extend-keys.lua`로 key 만료를 지금 + `keys-expire-margin`까지 늦춤(실패는 WARN만) |
| 사용 가능 쿠폰 만료 | `MemberCouponExpiryBatch`가 `expiry.interval`(10m, `fixedDelay`)마다 expiresAt ≤ now인 SELLER 쿠폰의 AVAILABLE을 `expires_at, id` 순으로 `expiry.batch-size`(500)씩 골라 조건부 UPDATE(`status = AVAILABLE and expires_at <= :now`)로 EXPIRED로 바꾼다(7단계, 묶음마다 짧은 트랜잭션, replica 1). generation은 그대로이고 두 번 돌아도 같다. 지표 `coupon.expiry.expired`, 회차마다 INFO 요약(0건이어도). 플랫폼 쿠폰의 만료와 예산 해제 작업 등록은 billing 프로모션 지갑 계약 뒤 같은 트랜잭션으로 붙인다(Q35). 그때까지 플랫폼 발급 쿠폰은 기한이 지나도 AVAILABLE로 남아 쿠폰함(`/me`)에 보일 수 있다. 사용 경로는 `expiresAt > now`로 막으므로 쓸 수는 없다(플랫폼 쿠폰은 지금 열 수도 없다) |
| 사용/예약 중 기한 경과 | 상태를 무조건 AVAILABLE/EXPIRED로 덮지 않는다. 사용 이력·billing 보류와 결과를 확인하고 더 필요 없는 예약 예산만 해제 |
| 예산 작업 재시도 | 쿠폰 상태와 독립적으로 미완료 예약/해제 결과를 재조회하고 완료 처리 |
| 발급 종료 | 발급 차단 후 진행 중 요청을 정리하고 미발급 수량의 예산 해제 |
| 오래된 예약 | 강의 주문/예약 작업·billing 결과를 제공 API로 확인한다. 단순 시간 경과로 해제하지 않음 |
| 대사 | 발급 수량, 중복 인덱스, 사용 시도, 재원, 작업 적체를 비교. 원인을 모르는 재고를 임의 INCR하지 않음. DB 미확정 요청이 남은 쿠폰은 재발행과 같이 key 만료를 늦춤 |
| 기록 정리 | 쿠폰이 접수될 수 있는 동안(OPEN·SUSPENDED이고 발급 종료 + 여유 전)은 DB ISSUED 기록만 HDEL. REJECTED 기록은 접수가 끝난 뒤 지운다(지우면 재고 누수). 이전 epoch 기록은 DB 최종 결과가 있으면 보상 표식과 무관하게 정리. 쿠폰의 Redis 기록이 전부 정리되면 용량 예약 해제 |
| 고아 예약 정리 | 대사가 OPEN·SUSPENDED가 아니고 Redis key도 없는 쿠폰의 용량 예약을 해제. DRAFT·REVIEW는 `limits.orphan-reservation-grace`가 지나야 해제(진행 중인 열기 보호). REJECTED 전이는 즉시 해제, CLOSED는 기록 정리 때 해제 |
| Redis 프로세스 점검 | `recovery.process-check-interval`마다 issue-control의 `runId`와 Redis `INFO server run_id`를 비교. 다르면 해당 쿠폰을 `hold=RECOVERY`로 막고 UNKNOWN 판정으로 전환 |

**DEAD 작업:** RESERVE 작업이 DEAD면 주문은 HOLD_PENDING으로 남는다. 시간만으로 해제하지 않으므로 같은 강의 재주문이 막힌다. 운영자는 강의 관리자 API `/api/v1/lectures/admin/coupon-works`(ADMIN, contracts.md "강의 쿠폰 작업 관리자 API")로 처리한다(7단계).

1. `[LECTURE_COUPON_WORK_DEAD]`(또는 `[LECTURE_COUPON_WORK_RETRY_FAILED]` 반복)를 받으면 `GET …/coupon-works?status=DEAD`로 대상을 고른다. 오래 PENDING인 작업(회로 열림 등)은 `?status=PENDING`으로 본다.
2. `GET …/{workId}`로 쿠폰 예약 상태(`items[].coupons[].status`)와 주문 상태를 본다. `couponStatus=UNAVAILABLE`이면 쿠폰 모듈과 내부 API 키(`[LECTURE_COUPON_AUTH_FAILED]`)를 먼저 고친다.
3. 예약 작업(RESERVE): 쿠폰이 정상으로 돌아왔고 주문을 이어 가도 되면 `retry`다. 같은 workId로 보통 흐름(대조 → 보류 등록 → 결제)을 잇는다. 주문을 끝내야 하면 `fail`이다. 결과는 셋이고 모두 주문을 HOLD_FAILED로 만든다. `RESERVED_RELEASING`은 예약 generation을 기록하고 해제 작업을 만들어 한 번 보낸다. `REJECTED`는 거절 코드만 기록하고 해제하지 않는다. `NOT_RESERVED`(기록 없음·모두 해제)는 예약 작업을 REJECTED(reject_code `ADMIN_FAILED_NOT_RESERVED`)로 닫고 결제 실패만 기록한다(PR #816 리뷰). PAID·CANCELED나 강의 근거에 없는 사용 시도가 보이면 409 `COUPON_RESERVATION_SETTLED` + `[LECTURE_COUPON_WORK_RESERVATION_SETTLED]`이다. 자동 조치는 없고 쿠폰 사용 시도와 주문을 직접 조사한다.
4. 해제 작업(RELEASE): 쿠폰에 이 주문의 RESERVED가 없으면 `resolve`로 RELEASED로 닫는다. 남아 있으면 409 `COUPON_RESERVATION_STILL_HELD`이고, 쿠폰을 고친 뒤 `retry`한다.
5. 쿠폰을 조회할 수 없으면 503 `COUPON_UNAVAILABLE`이고 아무것도 바꾸지 않는다. 조회 뒤 다른 요청이 작업을 바꿨으면 409 `COUPON_WORK_CHANGED`이므로 다시 조회한다. 조치 뒤 `GET …/{workId}`와 주문 상태(HOLD_FAILED·PAID)를 다시 확인한다. 상태 변경 로그에 action·adminUserId·workId·전후 상태가 남는다.

DEAD가 될 때 강의가 기다리는 예약 요청은 없다. 작업은 보낼 차례(`nextAttemptAt <= now`)일 때만 DEAD가 되고, 마지막 시도를 시작할 때 `nextAttemptAt`을 `coupon-work-retry-after`(30s)만큼 미룬다. 그래서 DEAD는 마지막 요청 뒤 30초 이상이고, 쿠폰 클라이언트는 연결 2s·응답 5s 안에 끝난다. 남는 한계: 쿠폰 reserve 트랜잭션에는 서버 쪽 제한 시간이 없다. 강의가 끊은 뒤 30초를 넘겨 늦게 커밋되면 `fail`이 NOT_RESERVED를 본 뒤 예약이 생길 수 있다. reserve 제한 시간 또는 쿠폰 쪽 오래된 예약 정리로 정할 미결이다.

예산 해제는 상태 변경과 함께 작업을 저장하고 트랜잭션 밖에서 billing을 호출한다. EXPIRED 기록 후 호출 실패가 나도 작업이 남아야 한다. 취소·해제와 만료가 경쟁할 때 같은 예약 재원을 두 번 해제하지 않는다. USED 만료와 만료 뒤 취소 반환도 대사 대상이다.

## 용량과 캐시

현재 레포 Redis 설정은 컨테이너 64Mi, maxmemory 48mb, noeviction, AOF everysec다. 이 수치는 설정 확인이며 실제 여유 메모리를 측정한 결과는 아니다.

쿠폰별·전체 수량 상한과 용량 예약은 1차 필수다. CLOSED 정책도 Redis 기록이 남으면 포함한다. 동시 승인, requests·보상 표식까지 계산한다. 임계치에서 신규 발급을 차단하고 기존 요청 재발행·정리를 우선한다. 실제 한도는 대표 데이터 측정 후 정하며 측정 전 운영 발급을 열지 않는다.

SWR은 조회에만 쓴다. fresh 기간과 키 TTL을 분리하고 토큰 일치 시에만 락을 해제한다. 캐시 부재 때 대기는 유한하게 제한한다. Redis 장애 시 조회는 제한된 DB 경로로 대체한다. 갱신·정책 변경 경합으로 오래된 데이터가 덮이는 것을 버전으로 막는다. 구체 TTL·대기·조회 제한은 부하 검증으로 정한다.

### 확정 전 값

모두 `hapbang.coupon.*` 설정 + 코드 기본값이다. yaml에는 넣지 않고 측정·합의 뒤 바꾼다.

| 키 | 기본값 | 의미 |
| --- | --- | --- |
| `redis.keys-expire-margin` | 7d | 쿠폰 key TTL = 발급 종료 + 이 값. DB 미확정 요청이 남아 있으면 재발행·대사 배치가 지금 + 이 값까지 만료를 늦춘다 |
| `republish.max-consecutive-failures` | 3 | 한 쿠폰에서 연속 전송 실패가 이 수에 이르면 그 쿠폰의 남은 재발행을 다음 회차로 미룬다 |
| `max-expiry-days` | 3650 | 만료 N일·고정 만료 상한. DB CHECK와 같으며 늘릴 때는 마이그레이션과 함께 |
| `lecture-client.*` | 연결 2s·응답 5s, circuit breaker 윈도 20·최소 호출 10·실패율 50%·느린 호출 4s·open 30s·half-open 3 | 강의 조회 내부 API 클라이언트 |
| `publish-ack-timeout` | 1s | 접수 직후 Kafka 전송 ACK 대기. 쿠폰 producer `max.block.ms`로도 씀 |
| `producer-delivery-timeout` | 5s | 쿠폰 producer `delivery.timeout.ms` |
| `republish-after` | 30s | 접수 뒤 이 시간이 지나고 DB에 없으면 재발행 대상 |
| `republish.interval` | 1m | 재발행 배치 주기 |
| `republish.scan-count` | 200 | requests HSCAN 묶음 |
| `republish.ack-timeout` | 3s | 재발행 전송 ACK 대기 |
| `sold-out-cache-ttl` | 1s | 매진 메모리 캐시 TTL |
| `sold-out-cache-max-entries` | 10 000 | 매진 캐시(Caffeine) `maximumSize`. 넘으면 덜 쓰는 항목을 내보낸다(정리는 비동기라 잠깐 넘을 수 있다) |
| `issue-consumer.retry-attempts` | 3 | 소비 실패 재시도 횟수 |
| `issue-consumer.retry-interval` | 1s | 첫 재시도 간격 |
| `issue-consumer.retry-max-interval` | 10s | 재시도 간격 상한(배수 2는 코드 상수) |
| `dlt-replay-lease` | 1m | DLT 재발행 선점(REPLAYING) lease |
| `dlt-replay-timeout` | 25s | 관리자 주문 DLT 재처리의 소비 트랜잭션 제한 시간(7단계). 재처리 경로만 쓰고 Kafka 소비는 그대로다. 넘으면 PENDING으로 되돌리고 409 `COUPON_CONCURRENT_UPDATE`. 멱등 키 충돌 때 트랜잭션을 한 번 더 실행하므로 `2 × dlt-replay-timeout < dlt-replay-lease`여야 기동한다(PR #816 리뷰) |
| `recovery.republish-ack-timeout` | 3s | 복구 2단계 재발행 ACK 대기 |
| `recovery.kafka-timeout` | 5s | Kafka AdminClient 조회 시간 |
| `recovery.process-check-interval` | 1m | Redis run_id 점검 배치 주기 |
| `recovery.boundary-grace` | 2s | 경계 대기 여유. 경계 대기 = `publish-ack-timeout` + max(`producer-delivery-timeout`, `republish.ack-timeout`) + 이 값(기본 8s) |
| `recovery.claim-lease` | 2m | 재구축·재개 선점 lease |
| `limits.max-quantity-per-coupon` | 10 000 | 쿠폰별 발급 수량 상한 |
| `limits.max-total-quantity` | 50 000 | Redis 기록이 남은 전체 쿠폰 합산 수량 상한(건당 약 250B 추정) |
| `limits.backlog-threshold` | 500 | 재발행 대기 건수 임계치(넘으면 `hold=BACKLOG`) |
| `limits.backlog-release-ratio` | 0.8 | 적체 보류 해제 비율(히스테리시스) |
| `limits.orphan-reservation-grace` | 10m | DRAFT·REVIEW 고아 용량 예약을 해제하기 전 유예 |
| `reconciliation.interval` | 1m | 대사 배치 주기 |
| `calculate.max-search-nodes` | 100000 | 추천 탐색에서 시도할 조합 수 상한. 넘으면 찾은 최선을 `optimal=false`로 돌려준다 |
| `calculate.max-items` | 20 | 계산·예약 한 번의 항목 수 상한. 넘으면 400 `COUPON_TOO_MANY_ITEMS` |
| `platform-funding-enabled` | false | 플랫폼 쿠폰 재원 처리 활성 여부. false면 추천·usable에서 PLATFORM 제외, 직접 선택 409. billing 프로모션 지갑 계약이 붙으면 true(#811, 2026-11-10) |
| `expiry.interval` | 10m | 발급 쿠폰 만료 배치 주기(`fixedDelay`, 첫 실행도 같은 지연, 7단계) |
| `expiry.batch-size` | 500 | 만료 배치 한 묶음(짧은 트랜잭션)에서 고를 수 |

코드 상수: 복구·대사의 HSCAN과 DB 확인 묶음 500, 중단·종료의 막기 재시도 3회, 목록 조회 상한 100건.

## invariant와 보장 주체

구현이 바뀌어도 아래는 지켜져야 한다. PR은 건드리는 invariant와 그 검증을 적는다.

| ID | invariant | 보장하는 곳 | 검증 |
| --- | --- | --- | --- |
| I1 | DB 확정 발급 수 ≤ 총수량 | Lua 재고 + 소비자 조건부 UPDATE | k6 과발급 시나리오, 대사 |
| I2 | 회원당 같은 쿠폰 1장 | Lua 회원 SET + `UNIQUE(member_id, coupon_id)` | k6 중복 시나리오 |
| I3 | Lua 접수 ⇒ 결국 ISSUED 또는 REJECTED. 정상 경로에서 결과 불명으로 남지 않음(Redis 유실은 UNKNOWN) | 접수 직후 Kafka 전송 + requests 기반 재발행 + Kafka 재시도 + DLT + 대사 | 전달 장애·유실 복구 시나리오 |
| I4 | 같은 (쿠폰, 요청 ID)는 같은 결과. 요청 ID는 쿠폰 안에서만 유일 | Redis requests(쿠폰별) + `UNIQUE(coupon_id, issue_request_id)` | Lua·e2e·리포지토리 테스트 |
| I5 | 발급 쿠폰 1장은 동시에 주문 항목 1개에만 | generation CAS + 부분 유일 인덱스 `(order_item_id, member_coupon_id) WHERE status='RESERVED'` | 동시 예약 테스트 |
| I6 | 취소 완료 뒤 늦은 결제 완료가 상태를 바꾸지 않음 | 완료 우선 + 사용 시도 이력 | 이벤트 시나리오 |
| I7 | 쿠폰별 d = f + s, 항목 D ≤ P, 구매자 결제액 ≥ 0 | 계산 API | 단위 테스트 |
| I8 | 쿠폰의 보류·예약 합 ≤ 예약 총액 | billing(사용자 담당) | 재원·만료 시나리오 |
| I9 | Redis 장애 중에도 사용·취소 동작 | 사용 경로가 Redis 미의존 | Redis 중단 통합 테스트 |
| I10 | 특정 강의 대상 플랫폼 쿠폰이 살아 있는 동안 강의 가격이 오르지 않음 | 강의 가격 수정 → `priceRaiseBlocked` | 가격 수정 테스트 |

## 테스트 구성 (피라미드)

| 층 | 대상 | 양 | 방식 |
| --- | --- | --- | --- |
| 도메인 | `domain/`의 금액 규칙(d = f + s, 상한), 상태 전이, generation 비교, 만료 판정, 추천 조합 선택, Lua 반환값 해석 | **가장 많이** | Spring 없이 순수 단위 테스트. 입력 조합을 넓게 |
| 서비스(app) | UseCase·배치·재발행 배치의 흐름과 분기(멱등 응답, 전부 성공/전부 실패, 결과 불명 처리) | 도메인보다 적게 | 저장소·Redis·HTTP·Kafka는 mock 또는 fake. 한 UseCase당 주요 분기 몇 개 |
| 리포지토리 | 쿠폰 Repository마다 **하나씩** | Repository 수만큼 | `@DataJpaTest` + Testcontainers. 조건부 UPDATE(CAS)·UNIQUE 제약·조회 조건이 실제 DB에서 맞는지만 |
| e2e | 발급 접수 → Kafka → DB(전송 실패 뒤 재발행 포함), 계산 → 예약 → 결제 완료 → 취소 완료 → 복원, Redis 장애 중 사용·취소 | **가장 적게** | `@SpringBootTest` + Testcontainers(PostgreSQL·Redis·Kafka). "필수 검증" 표의 범주당 1~2개 |

- 동시성 검증(과발급·중복·동시 예약)은 도메인 테스트로 못 잡으므로 e2e 또는 k6에 둔다. 그 밖의 논리는 도메인 층으로 내린다.
- Lua 스크립트는 Redis Testcontainers로 스크립트 단위 테스트를 둔다(반환값 7가지, pcall 되돌리기). 이것은 리포지토리 층으로 센다.
- 같은 것을 여러 층에서 반복 검증하지 않는다. 위 층에서 검증한 규칙은 아래 층에서 "호출했는지"만 본다.

## 필수 검증

| 범주 | 시나리오·기대 결과 |
| --- | --- |
| 발급 경쟁 | 마지막 재고·같은 회원 동시 접수에도 총량·중복 제약 유지 |
| 전달 장애 | Lua 후 종료, Kafka 전송 실패·ACK 유실, 재발행 중복에도 중복 효과 없음 |
| 스크립트 실패 | 키 타입 오류·쓰기 실패·부분 보상 실패를 성공 응답하지 않고 차단·대사 |
| 상태 변경 경쟁 | 발급 중단/기간 종료와 접수의 순서가 일관되고 기존 요청 재조회 가능 |
| 유실 복구 | retry/DLT가 남은 채 재개하지 않음. 이전 epoch 전달·보상이 새 재고를 변경하지 않음 |
| 결과 조회 | 미접수·처리 중·확정 거절·유실 불명 구분, 타 회원 요청 접근 거절 |
| 계산 | 여러 강의 공용 쿠폰 배치까지 최적화, 중복 배치 금지, 정수·동률·0할인 정책 검증 |
| 주문 예약 | 예약 전 HP 배치 실행 금지. 예약 성공 후 강의 기록 전 종료도 복구 |
| 재원 | 판매자 할인/플랫폼 할인/중첩의 구매자·판매자·플랫폼 합계 일치 |
| 0 HP | 플랫폼 100%·판매자 100%·양수 항목 혼합 주문의 구매·취소·지급 완료 |
| 보상 | 일부 항목 보류 실패 시 양쪽 재원 보상 완료 후에만 쿠폰 해제 |
| 이벤트 | 취소 완료 선도착, 중복, 이전 generation 지연에도 현재 사용 상태 보존 |
| 만료 | AVAILABLE/RESERVED/USED의 기한 경과, 만료 뒤 취소, 해제 응답 유실에도 예산 중복 해제·누락 없음 |
| 용량 | 종료 키 잔존·적체·동시 승인 시 한도 준수, 미처리 기록 강제 삭제 없음 |
| 경계 | 모듈별 독립 커밋, JOIN/FK 금지, Redis 중단 중 기존 쿠폰 사용·취소 가능 |

현재 문서는 검증 기준이다. 애플리케이션 구현·장애·부하 테스트를 수행했다는 뜻이 아니다.

## ERROR 로그 alert 표식

`docs/dev/logging.md`대로 사람이 바로 봐야 하는 ERROR는 메시지 앞에 `[대문자_코드]`를 붙이고, alert 쿼리는 이 코드로 찾는다. WARN·INFO에는 붙이지 않는다.

| 표식 | 상황 |
| --- | --- |
| `[COUPON_ISSUE_DLT_ISOLATED]` | 발급 요청을 DLT로 격리 |
| `[COUPON_ISSUE_REJECTED_AFTER_ACCEPT]` | 접수된 요청을 소비자가 거절 확정(Redis 접수 기록과 DB가 어긋난 신호) |
| `[COUPON_ISSUE_COMPENSATION_FAILED]` | 영구 거절 뒤 Redis 수량 보상 실패 |
| `[COUPON_ISSUE_RECONCILIATION_MISMATCH]` | 대사 수량 불일치(접수 차단) |
| `[COUPON_ISSUE_RECORD_MALFORMED]` | requests 기록 형식 오류 |
| `[COUPON_RECOVERY_REQUIRED]` | Redis 프로세스 변경 감지 등으로 복구가 필요해 접수를 막음 |
| `[COUPON_RECOVERY_REPUBLISH_FAILED]` | 복구 2단계 경계 재발행 실패 |
| `[COUPON_RECOVERY_RESULT_UNKNOWN]` | 재구축·재개 결과 불명 |
| `[COUPON_RECOVERY_REJECTED]` | 복구 단계 전제 조건 미충족으로 거절 |
| `[COUPON_RECOVERY_QUANTITY_MISMATCH]` | 재개 전 수량 대사 불일치 |
| `[COUPON_ORDER_DLT_ISOLATED]` | 주문 결제·취소 완료 이벤트를 DLT로 격리(`coupon_dlt_log` kind ORDER) |
| `[LECTURE_COUPON_AUTH_FAILED]` | 강의 → 쿠폰 내부 API가 401·403(내부 API 키 설정 문제). 주문은 503 또는 202 |
| `[LECTURE_COUPON_RESERVATION_MISMATCH]` | 예약 응답이 주문 스냅샷과 다름. 주문 HOLD_FAILED + RELEASE |
| `[LECTURE_COUPON_WORK_DEAD]` | 쿠폰 작업이 시도 상한에 닿거나 해제가 거절돼 DEAD |
| `[LECTURE_COUPON_EVENT_MISSING_GENERATION]` | 예약 generation이 없는 쿠폰 항목이라 결제·취소 완료 이벤트를 내지 않음(결제·반환은 롤백하지 않음) |
| `[LECTURE_COUPON_QUOTE_INVALID]` | 쿠폰 계산 응답이 할인 스냅샷 규칙에 맞지 않음(모듈 간 계약 위반). 주문은 503 |
| `[LECTURE_COUPON_WORK_RESERVATION_SETTLED]` | 관리자 fail·resolve 중 PAID·CANCELED 또는 강의 근거에 없는 사용 시도를 봄. 409, 자동 조치 없음 |
| `[LECTURE_COUPON_WORK_RETRY_FAILED]` | 재전송 배치나 fail 뒤 해제 전송이 작업 하나를 처리하다 예외 |
| `[LECTURE_PRICE_RAISE_CHECK_FAILED]` | 가격 인상 확인을 쿠폰이 4xx로 거절해 가격 인상이 503 |

`LECTURE_` 표식 여덟은 강의 모듈이 남긴다. 6단계에서 생긴 다섯 표식과 7단계에서 더한 넷이 알림 후보다. 등급 후보(7단계, 돈·데이터가 이미 어긋남·서비스 멈춤은 critical, 사람 조치가 필요하지만 돈은 안전하면 warning):
- critical: `[COUPON_ISSUE_RECONCILIATION_MISMATCH]`, `[COUPON_RECOVERY_QUANTITY_MISMATCH]`, `[COUPON_ISSUE_REJECTED_AFTER_ACCEPT]`, `[COUPON_ISSUE_COMPENSATION_FAILED]`, `[COUPON_RECOVERY_REQUIRED]`(발급 멈춤), `[LECTURE_COUPON_AUTH_FAILED]`, `[LECTURE_COUPON_RESERVATION_MISMATCH]`, `[LECTURE_COUPON_QUOTE_INVALID]`, `[LECTURE_COUPON_EVENT_MISSING_GENERATION]`, `[LECTURE_COUPON_WORK_RESERVATION_SETTLED]`.
- warning: `[COUPON_RECOVERY_REPUBLISH_FAILED]`, `[COUPON_RECOVERY_RESULT_UNKNOWN]`, `[COUPON_RECOVERY_REJECTED]`, `[COUPON_ISSUE_RECORD_MALFORMED]`, `[COUPON_ISSUE_DLT_ISOLATED]`·`[COUPON_ORDER_DLT_ISOLATED]`(기존 Kafka DLT 적재 rule과 겹침), `[LECTURE_COUPON_WORK_DEAD]`, `[LECTURE_COUPON_WORK_RETRY_FAILED]`, `[LECTURE_PRICE_RAISE_CHECK_FAILED]`.

Loki 쿼리는 `sum(count_over_time({namespace="hapbang-prod", app="lecture-app"} |= "[표식]" [5m])) > 0` 모양이다. 지표 후보는 `coupon_dlt_pending` > 0 30분(warning), `coupon_issue_republish_oldest_age_seconds` > 300(warning), `coupon_reconciliation_mismatches` > 0(critical), `coupon_issue_backlog_holds` > 0(warning), 만료 배치 회차 INFO가 30분 없음(warning, `absent_over_time`)이다. 알림 등록은 인프라 담당이 `rules.yaml`에 한다.

## 운영 발급 개방 체크리스트 (7단계, 2026-11-10)

운영 발급은 아래가 모두 확인된 뒤 연다. 1·2가 끝나지 않으면 열지 않는다.

| # | 항목 | 지금 | 누가·무엇으로 확인 |
| --- | --- | --- | --- |
| 1 | **운영 선착순 측정(Q41) 미실시 → 운영 발급 개방 불가** | 미실시 | 사용자 승인 뒤 백엔드가 `issue-race.js`를 운영에서 smoke → 본 측정(Q40 전용 데이터·팀 공지·끝나면 쿠폰 CLOSED), `coupon-issue.md` "운영 측정" 표를 채움 |
| 2 | Q41 기준 통과(1,000명·재고 100, p95 ≤ 500 ms, 5xx < 1%, 과발급·중복 0, 2분 안 확정) | 로컬만 3회 중 2회 | 1의 결과로 판정 |
| 3 | Redis 명령 제한 시간 설정(후속 #826) | 반영(#826, 2s·연결 1s, 무응답 시 접수 약 4초 503) | 로컬 e2e(`withRedisPaused`)로 확인. 운영 측정 때 다시 |
| 4 | 용량 상한(`limits.*`)이 운영 Redis 48 MB 안 | 계산상 안(6.9 MB/5만 건) | 운영 측정의 `MEMORY USAGE`로 다시 |
| 5 | alert 등록("ERROR 로그 alert 표식"의 critical 표식·지표) | 미등록 | 인프라 담당이 `rules.yaml`에 등록, 오케스트레이터 확인 |
| 6 | 복구 1~5 리허설 | 로컬 1회(8.6초) | 운영 전 담당자가 runbook으로 한 번 더(운영 Redis는 멈추지 않음, 로컬·k3d) |
| 7 | DEAD 쿠폰 작업 0, 주문 DLT PENDING 0, 발급 DLT PENDING 0, hold 0, 대사 불일치 0 | 운영 미확인 | 개방 직전 관리자 API(`/coupon-works?status=DEAD`, 쿠폰 DLT 목록)와 지표 |
| 8 | lecture-app replica 1 | 1 | `kubectl --context hapbang-k3s -n hapbang-prod get deploy lecture-app` |
| 9 | 플랫폼 쿠폰은 billing 계약 전 열지 않음 | `platform-funding-enabled=false`, 강의 F > 0 409 | 설정·코드 확인 |
| 10 | 첫 운영 쿠폰 규모 | 미정 | 1의 결과로 수량 상한 안에서 작게(예: 재고 100 이하) 시작, 사용자 결정 |
| 11 | 만료 배치 동작 | 코드 반영 | 배포 뒤 회차 INFO 로그 확인 |

## 후속 후보

구현에서 확인됐지만 이번 범위에 넣지 않은 것이다. 안전한 쪽으로 동작하며 데이터 정합성에는 영향이 없다.

- 복구 일괄 API: 전제 조건(미처리 0·예산·대사)이 모두 증명된 쿠폰만 1~5를 한 번에 수행한다. 지금은 쿠폰마다 수동이다.
- 늦은 차단이 재개 뒤에 도착하면 접수가 다시 닫힌다. 그 쿠폰은 새 복구가 필요하다.
- RESUMING 예외 취소의 사전 검사와 차단 사이에 재개가 선점하는 경쟁이 남아 있다. 최악의 경우 `RESUMED`인데 `ready=0`이고 새 복구가 필요하다.
- REVIEW에서 준비만 되고 OPEN 커밋에 실패해 Redis key가 남은 쿠폰은 고아로 보지 않는다. key TTL이 지날 때까지 용량 예약을 차지한다.
- 배치(재발행·대사·프로세스 점검)는 lecture-app replica 1을 전제한다. 실행 표시가 앱 메모리라 분산 락이 없고, 중복 전달은 소비자 멱등이 흡수한다. replica를 늘리면 billing의 Postgres advisory lock 방식을 붙인다.
- 조회 API의 목록 상한 100건을 설정으로 옮기고, 회원 목록 쿼리(status + 기간 + 대상)가 느려지면 인덱스를 새 마이그레이션으로 넣는다.
- 강의 조회 circuit breaker의 micrometer 지표는 붙이지 않았다.
- 지표 이름: `coupon.issue.publish.failures`, `coupon.issue.republished`·`.republish.oldest.age`·`.republish.last`·`.republish.stale.epoch`, `coupon.issue.consume{result}`, `coupon.dlt.pending`(PENDING + REPLAYING)·`coupon.dlt.isolated`, `coupon.issue.compensations{result}`, `coupon.recovery.redis_check{result}`, `coupon.reconciliation.mismatches{kind}`, `coupon.issue.backlog_holds`, `coupon.capacity.reserved`. 매진 409 응답 수는 Counter `coupon.issue.sold_out`(매진 표시 캐시로 막은 것 + Lua 매진, #826. Prometheus `coupon_issue_sold_out_total`. 아래 매진 캐시 지표의 태그 값과 이름이 같으니 구분한다). 매진 캐시(Caffeine)는 Micrometer 바인더의 `cache.gets{cache=coupon.issue.sold_out,result}`(hit = Redis 미호출, miss = Redis 읽음. 예외: 쿠폰 시계가 Caffeine 만료 시계(단조 시계)와 어긋나 `freshAt` 검사에 걸리면(시계 역행 등) hit로 세고도 Redis를 읽는다)·`cache.size`·`cache.evictions{cache=coupon.issue.sold_out}`. 캐시에는 매진 신호와 Redis에서 읽은 매진 아님 결과가 함께 들어간다. 접수 경로는 쿠폰이 있는지 확인하기 전에 캐시를 보므로 없는 쿠폰 ID도 매진 아님으로 담겨, 없는 ID를 흩뿌리면 상한까지 찰 수 있다. 상한은 메모리 보호이고, 상한을 넘으면 W-TinyLFU가 덜 쓰는 항목을 내보내며 내보낸 쿠폰의 다음 요청은 Redis `sold_out`을 읽는다. 판정은 Lua가 지키므로 정합성 영향은 없다. 소비 실패·lag는 Spring Kafka 기본 지표. ERROR 로그 표식은 위 "ERROR 로그 alert 표식" 표. Grafana 대시보드·알림 등록은 운영 결정이다.
- `prepare.lua`·`recovery-rebuild.lua`는 만료를 발급 종료 + 여유로 다시 써서 연장분을 줄일 수 있다. prepare는 열기 전에만, rebuild는 보관 기간 안에서만 실행되므로 위험은 낮다. 필요하면 `PEXPIREAT GT` 적용을 검토한다.

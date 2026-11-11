# 데이터와 계약

아래는 데이터의 소유와 의미다. 내부 HTTP 요청·응답은 `common/shared`의 확정된 DTO를 사용한다.

## 데이터

| 소유 | 데이터 | 필수 내용·제약 |
| --- | --- | --- |
| coupon | coupon | 유형, 신청자, 대상, 할인 방식/값, 장당 최대 할인액(모든 강의 대상 정률만 필수), 수량, 발급 기간, 만료 방식, 중첩, 정책 버전, 승인/운영 상태, 예산 예약 ID, epoch·준비 상태 |
| coupon | member_coupon | 소유자, 쿠폰 ID, 발급 요청 ID, 상태, generation, 현재 항목, 발급/만료 시각, `budget_release_planned_hp`(해제 작업으로 등록한 누적 HP, PENDING 포함). UNIQUE(member_id,coupon_id), UNIQUE(coupon_id,issue_request_id). 요청 ID는 쿠폰 안에서만 유일하다(Redis requests도 쿠폰별) |
| coupon | coupon_usage | 발급 쿠폰+generation UNIQUE, 주문/항목, 적용 순서·기준액·d/f/s·정책 버전, 예약 작업 ID, 결제/해제/취소/복원 사실 |
| coupon | processed_operation | 이벤트/작업 ID UNIQUE, 요청 내용 식별값, 결과. last_event_id 하나로 전체 중복 처리를 대체하지 않음 |
| coupon | coupon_issue_request | (쿠폰 ID, 요청 ID) 유일, 회원/epoch, ISSUED/REJECTED 결과, 거절 사유, 최초 접수 시각, 발급 건, 보상 상태 |
| coupon | budget_work | `work_id` PK, coupon_id, kind(RESERVE/RELEASE), amount, reason, status(PENDING/DONE/FAILED), result, 생성/갱신 시각. RELEASE의 `reservation_id`·`target`은 필수이고 금액은 양수다. 재시도 선점에 `attempt`·`lease_until`·`version`을 사용한다. 상태 변경·해제 누적액과 작업을 함께 저장 |
| coupon | recovery_run | 복구 ID, 이전/새 epoch, 단계, requests 재발행 완료 시각, Kafka 파티션 경계, 미처리 결과, 재개 근거 |
| coupon | coupon_dlt_log | 원문, 요청 ID, 실패 원인, 격리·재발행·최종 처리 상태 |
| lecture | 주문·할인·예약 작업 | 원가격, 구매자 금액, 판매자 지급액, 쿠폰별 d/f/s, 적용 순서, reservedGeneration, 예약·해제 작업 결과 |
| billing | 재원·예약·보류·원장 | 쿠폰 및 사용 시도별 재원 이동, 작업 결과, 충전 출처, 반환 원금 추적 |

coupon_usage는 같은 항목에 대한 재시도 여부를 generation·작업 ID로 구분한다. 작업 요청 내용 불일치는 거절한다. 단순히 마지막 이벤트 하나만 저장하거나 0행 CAS를 무조건 성공으로 처리하지 않는다.

## 내부 HTTP API (강의 → 쿠폰)

JVM 분리 가정에 따라 아래는 Java 인터페이스가 아니라 **내부 HTTP API**다. 레포 규칙대로 경로는 `/api/v1/internal/coupons/...`, 인증은 회원 인증이 아니라 `X-Internal-Api-Key`이며 Envoy route를 만들지 않아 서버 밖에서는 닿지 않는다(루트 AGENTS.md, D63). 클라이언트는 `@HttpExchange`, DTO는 `common/shared`, 계약 문서는 `docs/api/internal-coupon.md`다.

| API | 입력·결과 |
| --- | --- |
| calculate | 회원, 서버 조회 항목 목록, 선택 매핑(쿠폰 ID→강의 ID). 생략은 추천, 빈 목록은 미사용. 항목별 금액·쿠폰별 적용 순서/기대 generation/버전 반환 |
| reserve | 회원·주문·workId·항목별 선택/계산 근거. 전체 성공 또는 롤백. reservedGeneration과 검증된 결과 반환 |
| release | 주문·workId·사용 시도 목록·실패 및 보상 완료 근거. AVAILABLE 또는 EXPIRED로 종료 |
| reservationStatus | workId와 소유 문맥으로 예약 결과 조회. 주문 저장 실패·응답 유실 복구에 사용 |
| requestIssue | 쿠폰·로그인 회원·issueRequestId. 접수와 최종 완료 구분 |
| issueStatus | 쿠폰·로그인 회원·issueRequestId. ISSUED/REJECTED/PENDING/NOT_FOUND/UNKNOWN 구분 |
| myCoupons / usableFor | 로그인 회원 기준 보유/사용 가능 쿠폰 조회 |
| 신청·승인·중단·종료 | 판매자는 판매자 쿠폰 신청·중단, 관리자는 승인·거절과 플랫폼 쿠폰 발행·중단·종료. 플랫폼 재원은 관리자 발행 경로로만 확보 |
| priceRaiseBlocked | 강의 ID. 특정 강의 대상 플랫폼 쿠폰이 OPEN이거나 미사용 발급 쿠폰이 남아 있으면 true. 강의 모듈이 가격 인상 전에 호출 |

상태 조회는 DB 최종 결과를 우선하고 없으면 Redis 접수 기록을 확인한다. 장애로 결과를 확인할 수 없으면 UNKNOWN이다. 요청 자체가 없다고 확인되는 경우만 NOT_FOUND다. 요청 ID만 안다고 다른 회원 결과를 조회할 수 없다.

사용자용 REST는 `/api/v1/coupons`, `/api/v1/coupons/{couponId}/issue-requests`, `/api/v1/coupons/{couponId}/issue-requests/{requestId}`, `/api/v1/coupons/me`, `/api/v1/coupons/admin/...`를 기준으로 한다. 계산·예약·해제·reservationStatus·priceRaiseBlocked는 `/api/v1/internal/coupons/...` 내부 API로만 열고 외부 사용자에게 노출하지 않는다. 내부 API의 base URL은 `hapbang.coupon.base-url`(기본 같은 서버)이다.

## 이벤트

강의 → 쿠폰 이벤트는 **Kafka**로만 전달한다. 강의가 `@Externalized` 이벤트를 업무 변경과 같은 트랜잭션으로 `event_publication`에 기록하고 `spring-modulith-events-kafka`가 내보낸다. 쿠폰은 `@KafkaListener`로 소비한다. schema는 `common/shared`에 둔다.

- OrderItemPaid (기존 토픽 `lecture.events.v1`, key = 주문 항목 ID): eventId, 주문/항목, usages[{memberCouponId,generation}], paidAt.
- OrderItemCancelCompleted (같은 토픽, key = 주문 항목 ID): eventId, 주문/항목, usages, billing 반환 작업 ID, 쿠폰별 예산 복구 결과, completedAt.
- CouponIssueRequested (토픽 `coupon.events.v1`, key = couponId): issueRequestId, couponId, memberId, epoch, issuedAt, expiresAt. 소비 그룹 `lecture-app.coupon-issue`, DLT는 팀 규칙대로 `coupon.events.v1.lecture-app.coupon-issue.DLT`. 팀 Kafka 규칙에 이벤트 ID가 없어 소비 멱등은 결정적 작업 ID `coupon-issue:{couponId}:{requestId}`(`processed_operation`)로 한다. 접수 직후 앱이 보내고(ACK 대기 설정), 재발행 배치가 같은 ID로 다시 보낼 수 있다.

같은 주문 항목의 결제 완료·취소 완료는 같은 key라 순서가 지켜진다. 이벤트 ID(`processed_operation`)뿐 아니라 사용 시도의 최종 결과로 역전도 막는다. 인프로세스 `@ApplicationModuleListener`로 두 모듈을 잇지 않는다.

취소 완료를 소비할 때는 잠근 사용 시도의 PLATFORM·F > 0 스냅샷과 `budgetRestorations`를 couponId·memberCouponId·generation·금액으로 1:1 대조한다. 진행 중인 플랫폼 사용 시도가 usages에서 빠지거나 복구 결과가 누락·추가·중복·불일치하면 어느 쿠폰도 복원하지 않고 기존 주문 DLT 경로로 격리한다. SELLER 또는 F = 0 사용 시도에는 복구 결과가 없어야 한다.

## Redis

키 생성은 한 클래스에 모은다. 쿠폰별 stock, users, requests, issue-control, sold_out을 둔다. Redis Stream은 두지 않는다. issue-control(HASH)은 권위 있는 판정 데이터다. 필드: `ready`, `status`(`READY`·`OPEN`·`SUSPENDED`·`CLOSED`), `epoch`, `startAt`·`endAt`(발급 기간), `keysExpireAt`(쿠폰 key TTL), `expiryType`·`expiryDays`·`fixedExpiresAt`(만료 계산 입력), `fence`(쿠폰 DB version), `hold`(비어 있지 않으면 신규 접수 거절. 값은 `RECOVERY`·`RECONCILE`·`BACKLOG`), `runId`(prepare 때 기록한 Redis `run_id`, 프로세스 변경 감지용), `recoveryId`(복구 실행 ID, 단조 증가. rebuild·activate 펜스). 접수 경로는 이 HASH와 Lua만 읽고 DB를 읽지 않는다. `READY`는 준비됐지만 접수 불가, `OPEN`만 접수 허용이다. 상태 쓰기는 fence 비교로 늦은 옛 쓰기를 무시한다(design.md "발행과 플랫폼 예산" 5). SWR 정책 캐시와 분리한다.

requests는 요청 ID → `memberId|acceptedAtMs|ACCEPTED|expiresAtMs|e{epoch}[|PUBLISHED][|COMPENSATED]`로 두어(epoch는 접수 당시 세대, PUBLISHED는 Kafka 전송 성공 표식, COMPENSATED는 보상 처리 완료 표식이지 재고 복구 보장이 아니다 — 이전 epoch 기록이나 issue-control이 없을 때는 표식만 남기고 재고는 그대로다. 재발행 판단은 DB 기준. epoch 필드가 없는 옛 형식은 control epoch로 대체) 요청 ID로 회원·접수 시각·결과·만료 시각을 조회할 수 있게 한다. 같은 요청의 재응답은 최초 expiresAt을 돌려준다. issue.lua는 `{code, expiresAtMs, epoch}`를 반환한다(접수 직후 Kafka 이벤트가 DB 없이 epoch를 실을 수 있도록). 판정 순서는 인수·key 타입 → 같은 요청 ID(소유자 일치면 `2`, 다른 회원이면 `-4`) → 신규 요청만 issue-control(`-2`) → 기간(`-3`) → hold(`-2`) → 회원 중복(`-1`) → 재고(`0`) → 쓰기(`1`, 실패 `-9`)다. 같은 요청을 준비·기간·hold보다 먼저 보므로 중단·기간 종료·보류 뒤의 재시도도 최초 결과를 받는다. 회원 중복 인덱스는 별도로 유지한다. 보상은 요청별 표식·epoch 검증·재고 복구를 원자적으로 처리한다. Kafka 전송 성공은 DB 완료가 아니다. requests 기록은 DB 확정 전에 지우지 않는다. 쿠폰 key TTL은 발급 종료 + `keys-expire-margin`이지만, DB 미확정 요청이 남아 있으면 재발행·대사 배치가 `extend-keys.lua`로 key 5개와 issue-control `keysExpireAt`의 만료를 지금 + `keys-expire-margin`까지 늦춘다(`PEXPIREAT GT`, 더 늦은 쪽으로만 바꾸고 줄이지 않는다). 정리는 확정 뒤에만 한다. 대사가 ISSUED 기록을 정리한 뒤 같은 요청 ID를 다시 보내면 issue.lua는 `2`가 아니라 `-1`(이미 발급)을 돌려주고, 상태 조회는 DB 기준 ISSUED다.

중단은 Redis 발급 차단을 확인한 뒤 완료 응답하며 중간 실패를 작업으로 재시도한다. 중단 전 원자적 접수가 끝난 요청은 기존 요청으로 처리한다. Redis 장애 때는 신규 접수를 열지 않는다.

## 관리자 운영 API (발급 복구)

권한은 ADMIN. 경로는 `/api/v1/coupons/admin/...`이며 `/admin`은 강의 앱 SecurityConfig와 Facade `@PreAuthorize`가 함께 막는다. 상태 변경 로그에 adminId를 남긴다.

| 메서드·경로 | 역할 |
| --- | --- |
| GET `/api/v1/coupons/admin/dlt?status=` | DLT 기록 목록(페이지). 상태 `PENDING` → `REPLAYING`(선점, lease) → `REPLAYED`, 또는 `REJECTED` |
| POST `/api/v1/coupons/admin/dlt/{dltId}/replay` | 같은 요청 ID로 원 토픽에 재발행. `REPLAYING`으로 선점·커밋 → 전송 → `REPLAYED`. 실패면 `PENDING` 복귀(그 사이 새 격리가 생겼으면 `REPLAYED`). lease가 지난 `REPLAYING`은 다시 선점할 수 있다. 복구 중이면 409 |
| POST `/api/v1/coupons/admin/dlt/{dltId}/reject` `{reason}` | 영구 거절. DB에 REJECTED·사유 확정 뒤 `compensate.lua` 한 번(표식·epoch 검증·INCR·SREM·매진 해제 원자). 표식이 있으면 무변경, 기록이 없으면 무변경 + ERROR 지표, 이전 epoch면 표식만. 재거절은 409가 아니라 200으로 보상만 재시도 |
| GET `/api/v1/coupons/admin/{couponId}/recovery` | 복구 실행 목록 |
| POST `/api/v1/coupons/admin/{couponId}/recovery` | 1 차단. 복구 실행 생성(`BLOCKED`), epoch 후보, `requestsLost` 판정, issue-control `ready=0`·`hold=RECOVERY`. 끝나지 않은 복구가 있으면 409 |
| POST `…/recovery/{recoveryId}/boundary` | 2 경계 확정(`BOUNDARY_FIXED`). 차단 시각 + 전송 수명 대기 전이면 409("…ms 뒤에"). requests 재발행 뒤 Kafka 파티션 경계 기록 |
| POST `…/recovery/{recoveryId}/results` | 3 결과 확정(`RESULTS_CHECKED`). 미처리(Kafka lag·DLT PENDING·REPLAYING)가 0이어야 다음 단계 가능 |
| POST `…/recovery/{recoveryId}/rebuild` | 4 재구축(`REBUILDING` 선점 → `REBUILT`). 미처리가 남았으면 409. DB 확정 발급 기준으로 stock·users·requests(결과만)·issue-control 재구축, `ready=0` 유지 |
| POST `…/recovery/{recoveryId}/resume` | 5 재개(`RESUMING` 선점 → `RESUMED`). 수량 대사가 맞으면 새 epoch 활성화, `ready=1`, hold 해제 |
| POST `…/recovery/{recoveryId}/abort` | 취소(`ABORTED`, 멱등). 재구축 전까지, 또는 RESUMING에서 살아 있는 선점이 없고 issue-control이 없거나 runId가 바뀐 것이 확인될 때만. 차단은 유지된다 |
| POST `/api/v1/coupons/admin/{couponId}/reconcile/release` | 대사 불일치 보류(`hold=RECONCILE`) 해제. 운영자가 원인을 확인한 뒤 부른다. 다른 사유의 hold면 409 |

복구 단계: `BLOCKED` → `BOUNDARY_FIXED` → `RESULTS_CHECKED` → `REBUILDING` → `REBUILT` → `RESUMING` → `RESUMED`. 세부 규칙은 operations.md "복구와 대사".

## billing 담당 계약

billing 코드·공통 DTO는 billing이 소유한다. 쿠폰은 `CouponBillingInternalApi`(`@HttpExchange`)와 `HttpPromotionBudget`으로 기존 계약을 호출하며 billing 테이블에 직접 접근하지 않는다. 주소는 `hapbang.clients.billing.base-url`, 인증은 `X-Internal-Api-Key`(`hapbang.internal-api.key`)다. 주소가 없으면 어댑터를 등록하지 않으며 플랫폼 쿠폰을 열 때 성공으로 간주하지 않는다.

- 충전: fundingWorkId와 출처로 중복 충전을 방지한다.
- 예약: `POST /api/v1/internal/billing/promotion-budgets/reservations`, `PromotionBudgetReserveCommand(workId, couponId, policyVersion, amountHp)`. 성공의 예약 ID는 `PB-{양수}` 형식이며 이후 받은 값을 그대로 쓴다.
- 해제: 같은 경로 접두사의 `POST /releases`, `PromotionBudgetReleaseCommand(workId, reservationId, amountHp, target, reason)`. target은 1~200자로 필수이고 `UNISSUED:{수량}` 또는 `MEMBER_COUPON:{발급쿠폰ID}`를 기록한다. reason은 최대 500자다. 같은 쿠폰의 서로 다른 해제와 같은 작업의 재전송을 구분한다.
- 결과 조회: 같은 경로 접두사의 `GET /works/{workId}`. DONE은 확정 결과이며 NOT_FOUND는 성공·거절 어느 쪽도 증명하지 않는다. billing은 거절 결과를 작업으로 저장하지 않으므로 쿠폰은 같은 workId로 원래 명령을 다시 보낸다.
- 보류: 항목·구매자 부담과 `promotions[{couponId,memberCouponId,generation,amountHp}]`를 함께 처리한다. billing이 couponId로 예산 예약을 찾으며 보류 요청에 budgetReservationId를 싣지 않는다. 플랫폼 재원이 양수면 구매자 금액이 0이어도 billing 작업을 수행한다.
- 지급/반환: 구매자·플랫폼 금액과 쿠폰별 복구 결과를 확인한 뒤 항목을 완료한다. 취소 완료 이벤트에는 billing이 확인한 `couponId, memberCouponId, generation, amountHp`를 담아 각 쿠폰 예약으로의 복구 근거를 전달한다. 0 HP 구매자 작업도 결과 조회의 기준으로 식별할 수 있다.

쿠폰의 예산 workId는 `[A-Za-z0-9_-]{1,100}`이며 `:`를 쓰지 않는다(billing 계약은 최대 200자). 예약은 `coupon-{쿠폰ID}-reserve-v{정책버전}`, 확정 거절 뒤 새 시도는 `-a{번호}`를 붙인다. 해제는 `coupon-{쿠폰ID}-release-unissued-v{정책버전}` 또는 `coupon-{쿠폰ID}-release-member-{발급쿠폰ID}-to-{해제누적HP}`다. 같은 workId에는 예약·해제 대상과 금액·사유가 같은 내용을 다시 보낸다.

관리자 열기와 예산 배치는 모두 `CouponBudgetWorkUseCase.execute(workId)`의 같은 lease·attempt fence를 사용한다. 이미 선점된 PENDING 작업은 HTTP를 중복 호출하지 않는다. 관리자는 실행 뒤 DB의 최신 결과를 읽어 DONE일 때만 열기를 이어 가고, FAILED는 409, PENDING은 503으로 응답한다. 늦은 성공·거절 응답이 다른 attempt의 확정 결과를 덮지 않는다. 예약 성공이 발급 기한 뒤에 확정되면 쿠폰 행 잠금 아래 만료 DRAFT의 무발급 조건을 확인해, 예약 DONE·쿠폰 예약 ID와 미발급 전액 해제 작업을 같은 트랜잭션에 기록한다. 이미 DONE인 만료 DRAFT도 주기적으로 수집하며 CLOSED와 같은 `release-unissued-v{정책버전}` workId·`UNISSUED:{수량}` target을 재사용한다. 확정 예약 ID·DRAFT·`issueEndAt <= now`·`issuedCount = 0`을 모두 확인해야 하며 불명 결과를 실패로 바꾸지 않는다.

### 예산 HTTP 결과 분류

| HTTP·업무 결과 | 쿠폰 결과·처리 |
| --- | --- |
| 유효한 2xx 성공 응답 | SUCCEEDED. 예약은 workId·couponId·금액·예약 ID를, 해제는 workId·예약 ID·해제액·음수가 아닌 잔액을 확인한다. 조회 DONE도 workId·작업 유형·예약 ID·양수 금액이 있어야 한다 |
| 명령의 확정 업무 4xx | `PROMOTION_BALANCE_INSUFFICIENT`, `BUDGET_RELEASE_EXCEEDS_REMAINING`, `BUDGET_RESERVATION_NOT_FOUND`, `IDEMPOTENCY_KEY_CONFLICT`, `INVALID_POINT_AMOUNT`만 REJECTED로 분류한다. 408·425·429는 제외한다 |
| 408·425·429, 5xx, I/O·제한 시간 초과, 회로 열림 | UNKNOWN. 작업을 PENDING으로 남겨 같은 workId로 조회·재전송한다 |
| `BUDGET_RESERVATION_EXISTS`, 인증·설정 오류, 알 수 없는 4xx·오류 본문, 조회 4xx | UNKNOWN. 기존 예약 존재나 해석 불가 응답을 새 작업의 확정 거절로 바꾸지 않는다 |
| 조회 NOT_FOUND 또는 비어 있거나 깨진·요청과 다른 성공 응답 | UNKNOWN. NOT_FOUND는 회로 실패로 세지 않으며 같은 workId로 원래 명령을 재전송한다 |

어댑터는 요청을 한 번만 수행한다. 연결·응답 제한 시간과 circuit breaker는 기존 `hapbang.clients.billing.*` 설정을 사용하며 재시도는 영속 `budget_work`가 맡는다. 4xx·NOT_FOUND는 회로의 실패로 세지 않고 5xx·I/O·해석할 수 없는 성공 응답은 기술 실패로 센다. 로컬 DB 선점·결과 저장 트랜잭션 안에서 HTTP를 호출하지 않는다.

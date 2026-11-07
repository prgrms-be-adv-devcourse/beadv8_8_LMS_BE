# 데이터와 계약

구체 Java 타입·REST DTO는 구현 전에 담당자와 확정한다. 아래는 필요한 데이터와 의미다.

## 데이터

| 소유 | 데이터 | 필수 내용·제약 |
| --- | --- | --- |
| coupon | coupon | 유형, 신청자, 대상, 할인 방식/값, 장당 최대 할인액(모든 강의 대상 정률만 필수), 수량, 발급 기간, 만료 방식, 중첩, 정책 버전, 승인/운영 상태, 예산 예약 ID, epoch·준비 상태 |
| coupon | member_coupon | 소유자, 쿠폰 ID, 발급 요청 ID, 상태, generation, 현재 항목, 발급/만료 시각. UNIQUE(member_id,coupon_id), UNIQUE(coupon_id,issue_request_id). 요청 ID는 쿠폰 안에서만 유일하다(Redis requests도 쿠폰별) |
| coupon | coupon_usage | 발급 쿠폰+generation UNIQUE, 주문/항목, 적용 순서·기준액·d/f/s·정책 버전, 예약 작업 ID, 결제/해제/취소/복원 사실 |
| coupon | processed_operation | 이벤트/작업 ID UNIQUE, 요청 내용 식별값, 결과. last_event_id 하나로 전체 중복 처리를 대체하지 않음 |
| coupon | coupon_issue_request | (쿠폰 ID, 요청 ID) 유일, 회원/epoch, ISSUED/REJECTED 결과, 거절 사유, 최초 접수 시각, 발급 건, 보상 상태 |
| coupon | budget_work | 예산 예약·해제의 작업 ID, 대상, 금액, 사유, 진행/완료 결과. 상태 변경과 작업을 함께 저장 |
| coupon | recovery_run | 복구 ID, 이전/새 epoch, 단계, Stream 경계, Kafka 파티션 경계, 미처리 결과, 재개 근거 |
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
- CouponIssueRequested (토픽 `coupon.events.v1`, key = couponId): issueRequestId, couponId, memberId, epoch, issuedAt, expiresAt. DLT는 팀 규칙대로 `coupon.events.v1.lecture-app.coupon-issue.DLT`.

같은 주문 항목의 결제 완료·취소 완료는 같은 key라 순서가 지켜진다. 이벤트 ID(`processed_operation`)뿐 아니라 사용 시도의 최종 결과로 역전도 막는다. 인프로세스 `@ApplicationModuleListener`로 두 모듈을 잇지 않는다.

## Redis

키 생성은 한 클래스에 모은다. 쿠폰별 stock, users, requests, issue-control, sold_out과 공통 발급 Stream을 둔다. issue-control(HASH)은 ready·status(`READY`·`OPEN`·`SUSPENDED`·`CLOSED`)·epoch·발급 시작/종료·만료 계산 입력(방식·N일·고정 시각)·fence(쿠폰 DB version)를 가진 권위 있는 판정 데이터다. 접수 경로는 이 HASH와 Lua만 읽고 DB를 읽지 않는다. `READY`는 준비됐지만 접수 불가, `OPEN`만 접수 허용이다. 상태 쓰기는 fence 비교로 늦은 옛 쓰기를 무시한다(design.md "발행과 플랫폼 예산" 5). SWR 정책 캐시와 분리한다.

requests는 요청 ID로 회원·내용·접수 결과를 조회할 수 있게 구성한다. 회원 중복 인덱스는 별도로 유지한다. 보상은 요청별 표식·epoch 검증·재고 복구를 원자적으로 처리한다. Stream ACK는 DB 완료가 아니다. 미처리 기록은 고정 TTL/MAXLEN으로 삭제하지 않는다.

중단은 Redis 발급 차단을 확인한 뒤 완료 응답하며 중간 실패를 작업으로 재시도한다. 중단 전 원자적 접수가 끝난 요청은 기존 요청으로 처리한다. Redis 장애 때는 신규 접수를 열지 않는다.

## billing 담당 계약

billing 코드 변경은 사용자 담당이다. 프로모션 충전의 출처·권한·원장 유형도 billing에서 확정한다.

- 충전: fundingWorkId와 출처로 중복 충전을 방지한다.
- 예약: couponId·정책 버전·workId·예산 금액. 같은 작업의 다른 내용은 거절한다.
- 보류: 항목·구매자 부담과 promotions[{couponId,budgetReservationId,memberCouponId,generation,amount}]를 함께 처리한다.
- 지급/반환: 재원별 처리를 한 항목 작업으로 완료하고 각 쿠폰 예약으로 정확히 복구한다.
- 해제: workId·예약 ID·발급 쿠폰 또는 미발급 수량 범위·금액·사유를 전달한다. 같은 쿠폰의 서로 다른 해제와 재시도를 구분한다.
- 결과 조회: 응답 유실에도 동일 작업의 확정 결과를 조회한다. 0 HP 작업의 결과도 식별할 수 있어야 한다.

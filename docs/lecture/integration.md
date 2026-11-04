# 강의 모듈 — 연동·운영 설계

> 문서 지도: [핵심 설계](module.md) · [모듈 연동·운영](integration.md) · [스키마·API·구현 참고](reference.md) · [결정 기록](decisions.md)
>
> 장 번호는 기존 문서의 참조를 깨지 않도록 유지한다.

## 6. 모듈 간 연동

이 장은 **목표 구조**(D62, #271)를 적는다. D56의 Port + In-process Adapter 구조는 구현 전에 폐기했고, D57~D61의 제품·복구 결정은 HTTP 경계에 맞춰 이어 간다.

### 6.1 연동 방식

| 상대 모듈 | 내용 | 방식 |
| --- | --- | --- |
| 정산 | 주문 항목별 HP 보류, 구매 취소·이의 제기 인정 반환, 지급 | `shared.billing.out.BillingApiClient`로 정산 내부 HTTP API를 **강의 트랜잭션 밖에서** 호출한다. 호출은 HP 작업(`LectureHpTask`)으로 먼저 기록하고 실행한다 (D57, D62) |
| 고객센터 | 이의 제기 접수 | `shared.lecture.out.LectureApiClient`로 강의 내부 HTTP API를 고객센터 트랜잭션 밖에서 호출한다. 강의는 명령 이력으로 멱등하게 처리한다 (D60, D62) |
| 고객센터 | 이의 제기 판단·철회 | 명령 이벤트 → 강의 처리 → 결과 이벤트. 인정한 건의 HP 반환 완료·조치 필요는 별도 이벤트다 (D60) |
| 회원 | 가입, 회원 정보 변경 | 이벤트 |

#### 원칙 (D62)

1. **로컬 트랜잭션만 보장한다.** 다른 모듈의 저장 결과와 함께 커밋·롤백된다는 계약을 두지 않는다.
2. **동기 경계는 제공 모듈의 내부 HTTP API다.** 소비 모듈은 제공 모듈의 주소와 URI 계약만 알고, 제공 모듈 Java Application·domain·Repository 타입을 참조하지 않는다.
3. **shared는 데이터 계약과 공통 HTTP Client SDK를 제공한다.** DTO·이벤트·안정적인 enum·오류 코드와 `shared.[제공 모듈].out.*ApiClient`를 둔다. Java Port·엔티티·Repository·공유 `BusinessException`은 두지 않는다.
4. **`apps`는 bootstrap-only다.** 애플리케이션 시작과 공통 설정만 담당하며 bounded context를 연결하는 Adapter나 업무 로직을 두지 않는다. 단일 앱에서도 Client는 self-HTTP를 사용한다.
5. **동기 연동도 호출자 트랜잭션 밖에서 한다.** 로컬 상태와 작업을 먼저 커밋하고, 멱등 키로 호출한 뒤, 결과를 짧은 로컬 트랜잭션으로 기록한다.
6. **비동기 연동은 이벤트 계약을 쓴다.** 이벤트 발행은 로컬 상태 변경과 같은 트랜잭션에 기록되고(Spring Modulith 이벤트 발행 기록), `@ApplicationModuleListener`가 별도 트랜잭션에서 처리한다.
7. **완료 상태와 처리 중 상태를 나눈다.** `PAID`·`PURCHASED`·`CANCELED`·`RETURNED`·`PAID_OUT`은 보류·반환·지급이 실제로 끝난 뒤에만 쓴다.
8. **재시도는 명시적인 작업·명령 기록에 근거한다.** 같은 요청 ID + 같은 payload는 기존 결과를 재현하고, 같은 요청 ID + 다른 payload는 충돌이다.
9. **self-HTTP와 분리 배포 HTTP는 같은 계약을 쓴다.** 서비스 분리 시 provider별 base URL만 바꾼다. Kafka는 동기 Client의 대체 구현으로 가장하지 않는다 (6.2 "서비스 분리 시").
10. **작업 실행은 중복·중단을 견딘다.** 한 실행기만 원자적으로 선점하고, 실행 중 프로세스가 끝나면 lease가 지난 뒤 다시 선점한다. 시간·건수는 설정으로 둔다 (D57).

- `AGENTS.md`의 "모듈 간 통신은 Spring Event 기본"에서 동기 호출로 남는 것은 HP 이동(보류·반환·지급)과 이의 제기 접수뿐이다. 둘 다 요청자에게 결과(잔액 부족, 접수 불가 사유)를 바로 알려야 하기 때문이다. 판단·철회는 이벤트다.
- 최종 구조에는 shared Port(`shared.billing.port.BillingPort`, `shared.lecture.port.LectureObjectionPort`), 소비 모듈 Port, `apps` 연결 Adapter와 모듈 간 공유 Java 예외(`LectureObjectionRefusedException`)가 **남지 않는다** (#280).

#### 의존 방향

```
강의 HP 작업 실행기
  → shared.billing.out.BillingApiClient
      → POST /api/v1/internal/billing/{holds|releases|payouts}
          → billing Controller → billing Application (billing 로컬 트랜잭션)

고객센터 접수 실행기
  → shared.lecture.out.LectureApiClient
      → 강의 내부 HTTP Controller → 강의 Application (강의 로컬 트랜잭션)

shared (데이터 계약 + 공통 HTTP Client SDK)
  billing.dto     HoldCommand, ReleaseCommand, PayoutCommand, PenaltyCommand, *ResultDto
  billing.domain  BillingReferenceType, SettlementSourceType, BillingFailureCode
  billing.out     BillingApiClient, BillingCallResult, BillingCallException
  lecture.dto     이의 제기 접수 요청·결과 (LectureObjectionFileRequest, LectureObjectionFiledDto)
  lecture.domain  LectureObjectionCommandType, LectureObjectionFailureCode
  lecture.out     LectureApiClient
  lecture.event   이의 제기 판단·철회 결과, 반환 완료·조치 필요 이벤트 (lecture.events.v1)
  customersupport.event  이의 제기 판단·철회 요청 이벤트 (customersupport.events.v1)

apps
  bootstrap, scheduling, security·HTTP 공통 설정만 담당
```

#### 전환 결과

#271의 하위 티켓(#272~#280)으로 모듈 간 Java Port 호출을 모두 내부 HTTP·이벤트로 바꿨다(2026-11-02 #278·#279·#280 마무리). 전환 표는 #280에서 지웠다. 남은 후속은 지급 실패 레거시 컬럼 정리(#268)와 이의 제기 실패 명령 운영 복구(#267)다.

#### 실행 중 실패한 이벤트의 재처리 (D61 → D71)

Modulith는 기동할 때만 미완료 발행 기록을 다시 보낸다(`republish-outstanding-events-on-restart: true`). 실행 중 재전송은 [`kafka.md`](../architecture/infrastructure/kafka.md) "발행" 규칙대로 Kafka로 발행하는 앱마다 둔다. D71이 D61의 global 공통 재발행(#266)을 대체했다.

| 항목 | 강의 앱 | 고객센터 앱 |
| --- | --- | --- |
| 주기 | 매분 5초 (`lecture.event-republish.cron`) | 매분 5초 (`customersupport.batch.event-republish-cron`) |
| 처음 재발행할 최소 나이 | 발행 후 1분 (`lecture.event-republish.pending-grace`) | 발행 후 1분 (`customersupport.batch.pending-grace`) |
| 한 번에 처리 | 100건, 오래된 기록부터 | 100건, 오래된 기록부터 |
| 대상 | Kafka로 내보내는 강의 이벤트(`shared.lecture.event`)만 | Kafka로 내보내는 고객센터 이벤트(`shared.customersupport.event`)만 |
| 중복 | 받는 쪽(고객센터)이 문의 상태·명령 ID로 거른다 | 받는 쪽(강의)이 명령 이력으로 거른다 |

- 분산 락은 두지 않는다. replica 여럿이 같은 기록을 다시 보내도 받는 쪽 멱등이 흡수한다.
- D61의 stale 판정, 재발행 10회 경고, 자동 중단은 두지 않았다(billing·출연 앱도 없다). 필요해지면 따로 설계한다.

### 6.2 동기 HTTP 인터페이스

#### 지갑: shared `BillingApiClient` (D62·D64·D66, #272·#273·#411)

`BillingApiClient`는 인터페이스 Port가 아니라 shared에 있는 공통 HTTP Client SDK다. 강의와 출연은 같은 구현을 주입받고 `hapbang.clients.billing.base-url`(billing-app 주소)만 설정한다. 경로·본문·실패 분류 계약은 [지갑 내부 API 계약](../api/internal-billing.md)이다.

```java
// shared.billing.out — URI, 직렬화, timeout, ProblemDetail 변환을 감춘다.
public class BillingApiClient {
    BillingCallResult<HoldResultDto> hold(HoldCommand command);
    BillingCallResult<ReleaseResultDto> release(ReleaseCommand command);
    BillingCallResult<PayoutResultDto> payout(PayoutCommand command);
    BillingCallResult<PenaltyResultDto> settlePenalty(PenaltyCommand command);
    boolean isCircuitOpen();       // 회로가 열려 지금 부르면 CIRCUIT_OPEN으로 실패하는지 (D67)
}

public sealed interface BillingCallResult<T> {
    record Succeeded<T>(T value) implements BillingCallResult<T> {}
    record Failed<T>(BillingFailureCode code, String detail, int httpStatus, String rawCode)
            implements BillingCallResult<T> {}
}

public class BillingCallException extends RuntimeException {
    BillingCallFailureKind kind;   // RETRYABLE 또는 UNKNOWN_RESULT
    int httpStatus;                // 응답을 받지 못했으면 0
    String rawCode;                // 오류 응답의 code, 회로가 열렸으면 "CIRCUIT_OPEN". 없으면 null
}
```

| 항목 | 내용 |
| --- | --- |
| 요청·결과 DTO | 기존 `shared.billing.dto`의 `HoldCommand`·`ReleaseCommand`·`PayoutCommand`와 `*ResultDto`를 그대로 쓴다 |
| `Failed` | billing이 4xx(408·425·429 제외)로 응답한 확정 실패다. 업무 거절(`INSUFFICIENT_BALANCE`)만이 아니라 재시도 대상(`WALLET_NOT_FOUND`)도 들어오므로 "거절"이라 부르지 않는다. 분류는 실행기가 작업 유형별로 한다 (D57) |
| `BillingCallException` | 연결 거부·주소를 찾지 못함, 408·425·429처럼 처리하지 않은 것이 확실함 → `RETRYABLE`. 연결·응답 시간 초과, 응답 도중 끊김, 5xx(코드가 있어도), 읽을 수 없는 2xx처럼 처리 여부를 모름 → `UNKNOWN_RESULT` (D64). 회로가 열려 보내지 않음(`rawCode` `CIRCUIT_OPEN`) → `RETRYABLE`, 시도 한 번의 전체 상한(8초) 초과 → `UNKNOWN_RESULT` (D66). `Throwable`·원인 문자열은 결과에 넣지 않고 로그와 `lecture_hp_task_attempt`에만 남긴다 |
| 회복성 | Client가 Resilience4j로 `Retry( CircuitBreaker( TimeLimiter( HTTP ) ) )`를 건다. `RETRYABLE`만 첫 호출 포함 3회(최대 약 0.7초) 다시 보내고 다 실패하면 마지막 예외를 그대로 던지므로, 실행기의 작업 재시도(D57)와 `classify`는 바뀌지 않는다. HP 작업 배치는 `isCircuitOpen()`이면 선점하지 않고 회차를 건너뛴다(D67). 주문 요청 안 즉시 보류는 전체 10초 예산을 넘으면 남은 항목을 배치로 넘기고 202다(D68). 값과 지표·health는 [`docs/billing/CONTEXT.md`](../billing/CONTEXT.md) 2장 (#411) |
| 응답 검증 | 예상하지 못한 응답 형식, 결과 금액이 항목 가격과 다름 → 실행기가 `INVARIANT_VIOLATION`으로 본다 |
| 트랜잭션 | 강의는 상태와 작업을 먼저 커밋한 뒤 Client를 호출한다. HTTP라 Java 트랜잭션이 전파되지 않으며, 통합 테스트에서 호출 시 강의 트랜잭션이 비활성인지 확인한다 (#275~#277) |
| billing 진입점 | `POST /api/v1/internal/billing/holds`·`releases`·`payouts`·`penalty-settlements`. 요청 하나를 billing 로컬 트랜잭션 하나로 처리한다 (#273) |
| 성공 응답 | global `RsData{statusCode, message, data}`로 감싼다. 상태가 상황마다 달라지거나 응답 헤더가 필요할 때만 `ResponseEntity<RsData<T>>`를 사용한다 |
| 멱등 키 | 보류는 참조 대상(`HOLD:LECTURE_ORDER_ITEM:{항목 ID}`, 항목당 1건). 반환·지급은 작업의 요청 ID: 구매 취소 `LECTURE-CANCEL-{항목 ID}`, 이의 제기 인정 `LECTURE-RETURN-{항목 ID}`, 보류 보상 `LECTURE-HOLD-COMPENSATE-{항목 ID}`, 지급 `LECTURE-PAYOUT-{항목 ID}` (D43) |
| `replayed` | 결과 불명 뒤 같은 작업을 다시 실행하면 billing이 처음 결과를 `replayed = true`로 돌려준다. 같은 작업·같은 payload의 재현은 정상이다. 다른 payload 충돌(`IDEMPOTENCY_KEY_CONFLICT`)과 금액 불일치는 불변식 위반이다 (D43 수정) |
| 혼합 HP | billing이 항목마다 수익 HP → 충전 HP 순서로 보류하고, 반환·지급은 그 항목에서 차감한 HP 종류 그대로다 (D42) |
| 동시성 | 같은 구매자의 동시 결제는 billing이 지갑 잠금으로 직렬화한다. 보류가 항목마다 별개라 같은 주문의 다른 항목 반환·지급은 서로 영향이 없다 |
| 테스트 | 강의 단위·통합 테스트는 `BillingApiClient`의 테스트 대역 또는 HTTP stub을 쓴다. 실제 HTTP, 오류 본문, timeout과 트랜잭션 경계는 전체 앱 통합 테스트로 확인한다 (12장) |

`BillingFailureCode` (`shared.billing.domain`, D62). billing 내부 `BillingErrorCode` 전체가 아니라, 보류·반환·지급·위약금 호출에서 실제로 나올 수 있는 코드만 둔다. 목록은 billing 담당이 확인한다(#272·#273).

| 묶음 | 코드 |
| --- | --- |
| 지갑·잔액 | `WALLET_NOT_FOUND`, `INSUFFICIENT_BALANCE`, `BALANCE_OVERFLOW`, `INVALID_POINT_AMOUNT`, `WALLET_CLOSED`(탈퇴로 닫힌 지갑의 보류 거절, #623·D76) |
| 멱등 | `IDEMPOTENCY_KEY_CONFLICT` |
| 보류 | `HOLD_NOT_FOUND`, `HOLD_OWNER_MISMATCH`, `HOLD_ALREADY_CLOSED`, `HOLD_TYPE_NOT_SUPPORTED`, `HOLD_CHARGE_NOT_FOUND`, `HOLD_STATE_INCONSISTENT`, `CHARGE_PRINCIPAL_MISMATCH` |
| 반환·지급 | `RELEASE_AMOUNT_EXCEEDS_HELD`, `PAYOUT_AMOUNT_EXCEEDS_HELD`, `PAYOUT_TO_PAYER`, `PAYOUT_SOURCE_MISMATCH`, `PAYOUT_ALREADY_SETTLED` |
| 위약금 (출연) | `PENALTY_BASE_EXCEEDS_HELD`, `PENALTY_AMOUNT_ZERO` |
| 변환 불가 | `UNMAPPED` |

- billing 내부 API는 `BillingErrorCode`를 `ProblemDetail.code`로 응답하고, `BillingApiClient`가 이를 `BillingFailureCode`로 변환한다. billing 코드 전체 대조는 `default` 없는 `switch`와 테스트로 고정한다.
- `UNMAPPED`는 4xx의 오류 문자열이 목록에 없거나 본문이 없을 때(401 등) 쓴다. 실행기는 `INVARIANT_VIOLATION`으로 분류한다. 원래 코드 문자열은 사용자용 `detail`에 넣지 않고 `Failed.rawCode`로 받아 `lecture_hp_task_attempt.raw_error_code`에만 남긴다.
- `HOLD_STATE_INCONSISTENT`·`CHARGE_PRINCIPAL_MISMATCH`는 billing이 500으로 응답하므로 `Failed`가 아니라 `BillingCallException(UNKNOWN_RESULT)`의 `rawCode`로 온다. 실행기는 재시도 한도를 넘기면 `DEAD`로 둔다 (D64).

#### 강의: 이의 제기 접수 내부 HTTP (고객센터가 호출, D60·D62·D71, #278·#279)

고객센터는 shared의 `LectureApiClient.fileObjection`으로 강의 내부 HTTP endpoint(`POST /api/v1/internal/lectures/objections/file`)에 접수를 요청한다. 문의는 먼저 커밋되고, 호출은 고객센터 트랜잭션 밖에서 한다. 계약 문서는 [이의 제기 내부 API](../api/internal-objections.md)다.

| 항목 | 내용 |
| --- | --- |
| 요청 (`shared.lecture.dto.LectureObjectionFileRequest`) | `commandId = FILE:{inquiryId}`, `inquiryId`, `orderItemId`, `buyerUserId`, `reason`(문의 내용). 전송 재시도에도 `commandId`는 같다. 추적 ID(`correlationId`)는 두지 않고 trace가 맡는다(ADR 0002, D71) |
| 결과 | 성공이면 200 + `LectureObjectionFiledDto`, 거절이면 4xx ProblemDetail의 `code` = `LectureObjectionFailureCode`. 결과를 받지 못하면(5xx·시간 초과) 결과 불명이라 같은 요청을 다시 보낸다 |
| 강의 처리 | 한 강의 트랜잭션에서 명령 이력(`lecture_objection_command`) 확인 → 항목 `objection FILED` → 명령 결과 기록. 거절도 이력에 남긴다. 같은 `commandId` + 같은 내용이면 처음 결과(성공·같은 거절)를 재현한다 (D60, #278) |
| 접수 가능 조건 | 구매자 본인 항목, `PURCHASED` 또는 `CONFIRMED`, `objection_status = NONE`, `now < payout_scheduled_at` |
| 거절 코드 (`LectureObjectionFailureCode`) | 이름은 강의 `LectureErrorCode.OBJECTION_*`와 같다. 접수: `OBJECTION_NOT_BUYER`(404, 없는 항목 포함) → `OBJECTION_NOT_OBJECTIONABLE`(구매 완료 전·취소·반환 진행 중이거나 끝난 항목) → `OBJECTION_ALREADY_PAID_OUT` → `OBJECTION_ALREADY_FILED`(접수·판단된 항목) → `OBJECTION_PERIOD_EXPIRED` 순서로 검사한다(D49). `PAYOUT_PENDING`은 지급 예정 시각이 지난 뒤에만 생기므로 `OBJECTION_PERIOD_EXPIRED`(이미 판단됐으면 `OBJECTION_ALREADY_FILED`)다. 판단·철회: `OBJECTION_NOT_BUYER`·`OBJECTION_NOT_FILED`·`OBJECTION_INQUIRY_MISMATCH`. 같은 명령 ID·다른 내용: `OBJECTION_COMMAND_CONFLICT`(409, 이력은 그대로) |
| 필수 값 | 빠진 값·빈 사유는 shared 요청 레코드가 거절해 400 `INVALID_REQUEST`다 |
| 지급 배치와 경쟁 | 접수와 지급 대상 선점이 같은 항목 행을 바꾸므로 `order_item.version` 낙관적 락으로 직렬화된다. 접수가 먼저 커밋되면 지급 대상에서 빠지고, 지급 선점(`PAYOUT_PENDING`)이 먼저 커밋되면 접수는 `OBJECTION_PERIOD_EXPIRED`다. 지급 작업이 `DEAD`가 되어도 항목은 `PAYOUT_PENDING`이고 접수 기한은 다시 열리지 않는다. 겹친 낙관적 락 충돌은 재시도할 수 있는 실패라 거절로 저장하지 않는다(500, 고객센터가 다시 보낸다) |

#### 강의: 회원 탈퇴 확인 내부 HTTP (회원이 호출, D75, #625)

회원은 탈퇴를 지휘하면서 출연 다음에 shared의 `LectureApiClient.getWithdrawalBlocks`로 강의 내부 HTTP endpoint(`GET /api/v1/internal/lectures/users/{userId}/withdrawal-blocks`)를 부른다. 강의는 읽기만 해서 되돌릴 것이 없다. 계약 문서는 [회원 탈퇴 내부 API](../api/internal-withdrawals.md) 3장이다.

| 항목 | 내용 |
| --- | --- |
| 응답 | 회원 공통 DTO `shared.member.dto.WithdrawalBlocksDto`. 이유 코드·문장·`availableAt` 규칙은 D75 |
| 강의 처리 | 읽기 전용 트랜잭션에서 구매자 집계(`summarizeBuyerWithdrawalBlocks`), 판매자 집계(`summarizeSellerWithdrawalBlocks`, 판매 수익 분류 `EARNING_STATUS` 재사용), 판매 중·검수 대기 강의 수를 읽는다 |
| 실패 | 회원은 어떤 실패(`LectureApiCallException`)든 탈퇴를 멈추고 503으로 안내한다 |
| 탈퇴 반영 | 탈퇴가 끝나면 기존 `UserModifiedEvent`로 `LectureUser`가 `WITHDRAWN`이 된다. 이 조회는 이벤트 처리를 바꾸지 않는다 |

#### 서비스 분리 시 (D62)

| 전환 | 바꾸는 것 | 유지하는 것 |
| --- | --- | --- |
| self-HTTP → 분리 배포 HTTP | `hapbang.clients.[provider].base-url`을 해당 서비스 주소로 바꾸고 내부 인증·네트워크 정책을 분리 환경에 맞춘다 | shared Client, URI·DTO·오류·멱등 계약, HP 작업, 도메인 상태 전이 |
| HTTP → Kafka | 동기 `BillingApiClient`를 Kafka request/reply로 흉내 내지 않는다. HP 작업 실행기를 **작업 발행(dispatcher) + 결과 수신(consumer)** 으로 바꾼다. 작업은 결과 이벤트를 기다리고, 같은 요청 ID·claim 정보일 때만 기록한다 | 작업 모델, 도메인 상태 전이, DTO·오류 코드 계약 |
| 이의 제기 판단·철회 | 이미 Kafka 이벤트다(#278·#279). 바꿀 것이 없다 | 이벤트 계약, 명령 이력 |

### 6.3 이벤트 계약

Kafka 토픽·key·헤더·재시도 규칙은 [`kafka.md`](../architecture/infrastructure/kafka.md)를 따른다. 이벤트 레코드는 `<대상><과거형>Event`이고, 깨지는 변경은 같은 토픽에 새 레코드 타입으로 더한다(ADR 0001). `eventId`·`occurredAt`·`correlationId`는 두지 않고 업무 자연 키(명령 ID, 문의·주문 항목 ID)로 멱등 처리한다(ADR 0002, D71).

| 이벤트 (토픽, key) | 발행 | 구독 | 필드 | 받는 쪽 처리 |
| --- | --- | --- | --- | --- |
| `UserJoinedEvent` (`member.events.v1`, 회원 ID) | 회원 | 강의 | `UserDto` | `LectureUser` 생성 (있으면 무시, D17) |
| `UserModifiedEvent` (`member.events.v1`, 회원 ID) | 회원 | 강의 | `UserDto` | `LectureUser.syncFrom` (없으면 실패 → 재시도 뒤 DLT, 옛 버전은 무시, D17·#429) |
| `LectureObjectionResolutionRequestedEvent` (`customersupport.events.v1`, `inquiry:<문의 ID>`) | 고객센터 | 강의 | `commandId`(`RESOLVE:{inquiryId}`), `inquiryId`, `orderItemId`, `accepted`, `adminUserId` | 명령 이력 확인 → 인정이면 `ACCEPTED` + `RETURN_PENDING` + RETURN 작업, 기각이면 `REJECTED` → 결과 이벤트 |
| `LectureObjectionWithdrawalRequestedEvent` (`customersupport.events.v1`, `inquiry:<문의 ID>`) | 고객센터 | 강의 | `commandId`(`WITHDRAW:{inquiryId}`), `inquiryId`, `orderItemId`, `buyerUserId` | 명령 이력 확인 → `NONE`으로 되돌림(D52) → 결과 이벤트 |
| `LectureObjectionResolvedEvent` (`lecture.events.v1`, `order-item:<주문 항목 ID>`) | 강의 | 고객센터 | `commandId`, `inquiryId`, `orderItemId`, `accepted` | 처리 중인 그 판단이면 `RESOLVED` + `ANSWERED` + `objectionResult` + 판단 답변, 인정이면 `hpReturnStatus` ≥ `PENDING` |
| `LectureObjectionWithdrawnEvent` (`lecture.events.v1`, `order-item:<주문 항목 ID>`) | 강의 | 고객센터 | `commandId`, `inquiryId`, `orderItemId` | 처리 중인 그 철회면 `WITHDRAWN` + `deleted_at` |
| `LectureObjectionRequestRefusedEvent` (`lecture.events.v1`, `order-item:<주문 항목 ID>`) | 강의 | 고객센터 | `commandId`, `inquiryId`, `orderItemId`, `commandType`(`RESOLVE`·`WITHDRAW`), `failureCode` | 처리 중인 그 명령이면 `RESOLUTION_FAILED`·`WITHDRAWAL_FAILED` + 사유 (운영 복구 #267) |
| `LectureObjectionReturnCompletedEvent` (`lecture.events.v1`, `order-item:<주문 항목 ID>`) | 강의 | 고객센터 | `inquiryId`, `orderItemId` | `hpReturnStatus = COMPLETED` |
| `LectureObjectionReturnActionRequiredEvent` (`lecture.events.v1`, `order-item:<주문 항목 ID>`) | 강의 | 고객센터 | `inquiryId`, `orderItemId` | `hpReturnStatus = ACTION_REQUIRED` (이미 `COMPLETED`면 그대로) |

- 강의 리스너(`lecture-app.objection-command`)는 예상된 업무 거절이면 **예외를 던지지 않고** 거절 결과 이벤트를 발행한다(무한 재시도 방지). 낙관적 락 충돌처럼 다시 하면 될 수 있는 실패와 DB 같은 기술 실패는 예외를 던져 Kafka 재시도(3회) 뒤 그룹별 DLT로 보낸다.
- 도메인 변경, 명령 이력 기록, 결과 이벤트 발행 기록은 같은 강의 트랜잭션이다. 반환 완료·조치 필요 이벤트는 HP 작업 결과를 기록하는 트랜잭션에서 발행한다(`ReturnResultHandler`).
- 고객센터 리스너(`customer-support-app.objection-result`)는 문의 행을 잠그고, 처리 중인 명령(명령 ID가 같음)의 결과만 기록한다. 중복·늦은 결과는 무시하고, HP 반환 상태는 앞으로만 바꾼다. 반환 이벤트가 판단 결과보다 먼저 와도 된다.

### 6.4 주요 흐름

#### 구매 (장바구니 주문 / 바로 구매, D58)

```mermaid
sequenceDiagram
    actor B as 구매자
    participant LEC as 강의 모듈
    participant WAL as 지갑 모듈
    B->>LEC: POST /orders {lectureIds}
    Note over LEC: 트랜잭션 1
    LEC->>LEC: 검증 → 주문 HOLD_PENDING, 항목 HOLD_PENDING (강의 ID 오름차순), 항목마다 HOLD 작업
    Note over LEC,WAL: 트랜잭션 밖, 항목 순서대로 한 번씩
    LEC->>WAL: BillingApiClient.hold(구매자, LECTURE_ORDER_ITEM, itemId, price)
    WAL-->>LEC: Succeeded / Failed / BillingCallException
    Note over LEC: 짧은 트랜잭션: 작업 결과 기록
    alt 모든 보류 성공
        LEC->>LEC: 주문 PAID, 항목 PURCHASED, purchased_at = 지금 (D35), 장바구니에서 주문한 강의만 제거
        LEC-->>B: 201 {order: PAID}
    else 확정 거절 (예: 잔액 부족)
        LEC->>LEC: 주문·항목 HOLD_COMPENSATING, 실행 전 HOLD 작업 SKIPPED, 성공한 보류마다 RELEASE 작업
        LEC->>WAL: BillingApiClient.release(full, "LECTURE-HOLD-COMPENSATE-{itemId}") 한 번씩
        alt 보상까지 끝남
            LEC->>LEC: 주문·항목 HOLD_FAILED
            LEC-->>B: 409 INSUFFICIENT_BALANCE (billing과 같은 code, D69)
        else 보상 진행 중
            LEC-->>B: 202 {order: HOLD_COMPENSATING}
        end
    else 결과 불명·재시도 대상
        LEC-->>B: 202 {order: HOLD_PENDING}
    end
```

- 요청 안에서는 보류와 보상 반환을 **각각 한 번만** 바로 시도한다. 보상이 끝날 때까지 요청을 붙잡지 않는다. 나머지는 HP 작업 재시도 배치가 이어서 한다(D57).
- 요청 전체에 10초 예산을 둔다(D68). 시도하기 전에 남은 예산을 보고, 넘었으면 남은 보류·보상은 시도하지 않고 배치에 넘긴 뒤 202다. 이미 시작한 호출은 끊지 않는다.
- 거절 뒤 실행 전 HOLD 작업은 조건부 UPDATE로 `SKIPPED` 처리한다. 그 사이 선점된 작업은 끝까지 확인하고, 성공이면 보상한다 (D69).
- 주문 행을 먼저 저장하는 이유와 항목 순서는 그대로다: 보류의 참조 ID(주문 항목 ID)가 필요하고, `(buyer_user_id, lecture_id)` 부분 유일 인덱스 위반(중복 주문)을 보류 전에 걸러 409 `ALREADY_OWNED_LECTURE`로 응답한다(D45). 항목은 강의 ID 오름차순으로 만들어 교착을 막는다(D55).
- 구매 시각(`purchased_at`)은 **모든 항목의 보류가 성공했음을 기록하는 트랜잭션에서** `Clock`으로 한 번 캡처한 시각이다(D35 재확인). 그 전에는 `purchased_at`·`auto_confirm_at`·`payout_scheduled_at`이 없다(null).
- 보상 판단: 확정 거절이 나온 순간 주문·항목을 `HOLD_COMPENSATING`으로 바꾼다. 다만 성공한 보류도, 결과를 모르는 HOLD 작업도 없으면(예: 첫 항목에서 잔액 부족) 같은 트랜잭션에서 바로 `HOLD_FAILED`다. 한 번도 실행하지 않은 HOLD 작업은 `SKIPPED`로 닫는다. 이미 시도했지만 결과를 모르는 HOLD 작업은 끝까지 실행해 결과를 확인하고, 성공이면 그 항목에 보상 RELEASE 작업을 만든다. 모든 HOLD 작업이 끝나고 모든 보상 RELEASE 작업이 `COMPLETED`일 때만 `HOLD_FAILED`가 된다. **`HOLD_FAILED`는 남은 보류 HP가 없다는 것이 확인된 상태다.**
- 409를 돌려준다면 응답 시점에 이미 성공한 보류가 모두 반환된 것이다. 보상이 남았으면 409로 가장하지 않고 202다.

#### 구매 취소 (D59)

```mermaid
sequenceDiagram
    actor B as 구매자
    participant LEC as 강의 모듈
    participant WAL as 지갑 모듈
    B->>LEC: POST /orders/{orderId}/items/{itemId}/cancel
    Note over LEC: 트랜잭션 1
    LEC->>LEC: PURCHASED && now < auto_confirm_at && 이의 제기 없음 검사<br/>→ CANCEL_PENDING + RELEASE 작업 (버전 확인)
    Note over LEC,WAL: 트랜잭션 밖, 한 번
    LEC->>WAL: BillingApiClient.release(full, "LECTURE-CANCEL-{itemId}")
    WAL-->>LEC: 반환 결과
    Note over LEC: 짧은 트랜잭션: 주문 잠금 (D46)
    LEC->>LEC: releasedHp = price 확인 → CANCELED, 모든 항목이 끝났으면 주문 CANCELED
    alt 반환 완료
        LEC-->>B: 200 {item: CANCELED}
    else 반환 미완료
        LEC-->>B: 202 {item: CANCEL_PENDING}
    end
```

#### 자동 구매 확정과 지급 (배치, D59)

- 자동 구매 확정은 그대로다(D41). `PURCHASED`만 대상이고 HP를 움직이지 않는다.
- 지급은 둘로 나눈다. **지급 대상 선점**은 매일 01:00 배치가 하고(D50), **지급 실행·재시도**는 HP 작업 배치(1분마다)가 한다.

```mermaid
sequenceDiagram
    participant SCH as 지급 배치 (01:00)
    participant LEC as 강의 모듈
    participant TASK as HP 작업 실행기
    participant WAL as 지갑 모듈
    SCH->>LEC: 대상: CONFIRMED && payout_scheduled_at <= now && objection ∈ {NONE, REJECTED}
    loop 항목마다 (짧은 트랜잭션 1개)
        LEC->>LEC: PAYOUT_PENDING + PAYOUT 작업 (버전 확인)
    end
    SCH->>TASK: 선점한 작업을 한 번씩 실행
    TASK->>WAL: BillingApiClient.payout(항목 보류 → 판매자, "LECTURE-PAYOUT-{itemId}") (트랜잭션 밖)
    Note over TASK: 짧은 트랜잭션
    TASK->>LEC: paidHp = price 확인 → PAID_OUT, paid_out_at
    opt 실패
        TASK->>TASK: 작업 유형별 분류 → 재시도 예약 또는 DEAD (D57)
    end
```

- 지급 실패는 D54의 항목 컬럼이 아니라 HP 작업과 시도 기록에 남는다. D54의 분류 의미(`WALLET_NOT_FOUND`·기술 오류는 재시도, 그 밖의 billing 오류·지급액 불일치는 영구 실패)는 D57의 작업 유형별 분류로 옮긴다.
- 지급 작업이 `DEAD`가 되어도 항목은 `PAYOUT_PENDING`이고 처리 상태는 `ACTION_REQUIRED`다. 판매자에게 줄 돈이므로 관리자 목록, ERROR 로그·지표, 관리자 재시도로 반드시 복구한다.
- 기존 D54 데이터는 이관한다 (D59, 지급 실패 레거시 정리 티켓(#268)).

#### 내용 다름 이의 제기 접수 (D60)

```mermaid
sequenceDiagram
    actor B as 구매자
    participant CS as 고객센터 모듈
    participant LEC as 강의 모듈
    B->>CS: 이의 제기 문의 {orderItemId, reason}
    Note over CS: 트랜잭션 1
    CS->>CS: 문의 저장 (target = orderItemId), 연동 상태 FILING
    Note over CS,LEC: 트랜잭션 밖
    CS->>LEC: LectureApiClient.file(commandId = FILE:{inquiryId}, ...)
    LEC->>LEC: 강의 트랜잭션: 명령 이력 확인 → objection FILED (지급 멈춤) → 결과 기록
    alt 접수 성공
        Note over CS: 짧은 트랜잭션
        CS->>CS: FILED
        CS-->>B: 접수 완료 (기존 성공 응답)
    else 확정 거절
        CS->>CS: FILE_REJECTED + Soft Delete (커밋 성공 뒤에만 응답)
        CS-->>B: 4xx + 사유 코드
    else 결과 불명 또는 결과 기록 실패
        CS-->>B: 202 {FILING}
        Note over CS: 접수 재시도 배치(매분, 1분 지난 문의)가 같은 commandId로 다시 보낸다
    end
```

- 구매자는 강의 모듈의 주문 조회 API에서 `orderItemId`를 받아 고객센터 문의에 담는다.
- 202 뒤에 재시도에서 거절되면 문의를 지우지 않고 `FILE_REJECTED`와 "접수 불가 · 사유"(`objectionNotice`)를 상세에 보여 준다 (D60). 이 문의는 강의에 접수되지 않았으므로 구매자가 바로 지울 수 있다.
- API 클라이언트가 같은 문의를 다시 보내면 문의가 새로 생긴다(강의는 두 번째 접수를 `OBJECTION_ALREADY_FILED`로 거절한다). 요청 멱등 키를 도입하기 전까지는 막지 못한다(#279 범위 밖).

#### 관리자 판단과 판단 전 철회 (D60)

```mermaid
sequenceDiagram
    actor A as 관리자
    participant CS as 고객센터 모듈
    participant LEC as 강의 모듈
    participant WAL as 지갑 모듈
    A->>CS: 판단 (인정 / 기각)
    Note over CS: 트랜잭션 1: 문의 잠금, FILED → RESOLUTION_PENDING + ResolutionRequestedEvent 발행 기록
    CS-->>A: 202 {RESOLUTION_PENDING}
    CS--)LEC: LectureObjectionResolutionRequestedEvent
    Note over LEC: 강의 트랜잭션: 명령 이력 + 상태 변경 + 결과 이벤트 발행 기록
    alt 인정
        LEC->>LEC: ACCEPTED, 항목 RETURN_PENDING, RELEASE 작업 ("LECTURE-RETURN-{itemId}")
    else 기각
        LEC->>LEC: REJECTED (지급 예정 시각이 지났으면 다음 지급 배치가 선점)
    end
    LEC--)CS: LectureObjectionResolvedEvent (거절이면 RequestRefusedEvent)
    CS->>CS: RESOLVED + ANSWERED + objectionResult + 판단 답변 (인정이면 hpReturnStatus = PENDING)
    LEC->>WAL: BillingApiClient.release (HP 작업 실행기, 트랜잭션 밖)
    Note over LEC: 짧은 트랜잭션: 주문 잠금 (D46)
    LEC->>LEC: RETURNED, returned_at, 모든 항목이 끝났으면 주문 CANCELED
    LEC--)CS: LectureObjectionReturnCompletedEvent (작업 DEAD면 ReturnActionRequiredEvent)
    CS->>CS: hpReturnStatus 갱신 (PENDING < ACTION_REQUIRED < COMPLETED)
```

- 인정 반환은 요청 안에서 시도하지 않고 HP 작업 재시도 배치가 실행한다(D70 (1)). 반환 완료·DEAD 이벤트는 #278에서 `lecture.events.v1`로 발행한다(D71이 D70 (6)을 바꿈).
- 판단 전 철회: 구매자가 이의 제기 문의를 삭제하면 고객센터가 `FILED → WITHDRAWAL_PENDING`과 `LectureObjectionWithdrawalRequestedEvent` 발행을 한 트랜잭션에 기록하고 202로 응답한다. 강의는 항목을 `NONE`으로 되돌리고(D52) 결과 이벤트를 발행한다. 고객센터는 성공 결과를 받은 뒤에 `WITHDRAWN` + `deleted_at`을 기록한다. 그 뒤 같은 삭제를 다시 보내면 204다.
- 판단과 철회는 문의 행을 잠근 뒤 `FILED`일 때 하나만 선점한다 (D60, D71). 버전 칸은 두지 않는다.
- 접수가 지급보다 먼저 확정된다는 불변식(D9)은 유지된다: 접수는 여전히 동기 호출이고 강의 항목이 `FILED`가 된 뒤에야 성공으로 응답한다. 이벤트로 바뀐 판단·철회는 `FILED` 항목에만 적용되고, `FILED` 항목은 지급 대상이 아니라 지연돼도 지급이 먼저 나가지 않는다.

---

## 10. 동시성 · 멱등성 · 배치

| 상황 | 대응 |
| --- | --- |
| 구매 취소 ↔ 자동 구매 확정 배치가 같은 항목을 동시에 변경 | `order_item.version` 낙관적 락. 늦은 쪽은 실패한다. 구매 취소가 늦으면 RELEASE 작업도 함께 롤백되어 남지 않는다 (사용자 요청이면 충돌 오류) |
| 이의 제기 접수 ↔ 지급 대상 선점 | 둘 다 항목 행을 바꾸므로 낙관적 락으로 직렬화. `FILED`가 먼저 커밋되면 지급 대상에서 빠지고, `PAYOUT_PENDING`이 먼저면 접수는 `PERIOD_EXPIRED`다 (6.2) |
| 같은 주문의 여러 항목 반환·지급이 동시에 처리 | 보류가 항목 단위라(D36) 항목마다 별도 보류다. 서로 다른 항목의 반환·지급은 경쟁하지 않는다 (6.2) |
| 같은 주문의 마지막 두 항목 반환이 동시에 끝남 | 반환 결과를 기록하는 트랜잭션이 주문 행을 잠가(`SELECT ... FOR UPDATE`) 읽는다. 뒤 기록은 앞 기록이 커밋한 항목 상태를 보고 마지막 항목이면 주문을 `CANCELED`로 바꾼다 (D46, D59). 구매자를 모르는 이의 제기 반환은 항목 ID로 주문을 찾아 잠근다(D49) |
| 승인 중 판매자가 콘텐츠 수정 | 발생하지 않음. 검수 대기 중에는 콘텐츠를 수정할 수 없다 (D11) |
| 주문 버튼 중복 클릭, 202 뒤 재주문 | 부분 유일 인덱스(처리 중 상태 포함)로 같은 강의의 두 번째 주문이 HOLD 작업 기록 전에 실패하고 409 `ALREADY_OWNED_LECTURE`로 응답 (D45, D58) |
| 같은 강의들을 다른 순서로 동시에 다건 주문 (`[A,B]`·`[B,A]`) | 항목을 강의 ID 오름차순으로 만들어 유일 인덱스 키를 같은 순서로 잡는다. 늦은 주문은 교착 없이 앞 주문을 기다렸다가 409 `ALREADY_OWNED_LECTURE`. 그래도 교착(`40P01`)이 나면 재시도하지 않고 주문 트랜잭션만 롤백 후 500이다. 보류 전이라 HP는 움직이지 않는다 (D55, D58) |
| 같은 구매자의 동시 결제 (강의 주문 + 출연 요청 등) | `hold`의 지갑 잠금으로 잔액 검사와 보류를 원자적으로 처리 (billing 구현) |
| 같은 HP 작업을 요청 안 즉시 실행과 배치가 동시에 집음 | 둘 다 같은 `claim()`(조건부 UPDATE)을 거쳐 한쪽만 선점한다 (D57) |
| 실행 중 서버 종료, 느린 billing 호출 | lease(5분)가 지나면 다른 실행기가 다시 선점한다. lease는 호출을 끊는 제한 시간이 아니라 결과 기록 권한과 재선점 시점을 정한다. 앞 호출이 그 뒤에도 실행 중일 수 있으며, billing 멱등 키가 중복 HP 이동을, `claimToken` 검사가 늦은 결과의 반영을 막는다. 보장하는 것은 "외부 호출이 하나뿐"이 아니라 "한 선점의 결과만 작업에 반영된다"이다 (D57) |
| 같은 항목에 반환·지급이 두 번 호출 | 강의 상태 머신이 먼저 막고(처리 중 상태에서는 다른 HP 전이 불가), billing도 남은 보류가 없으면 거부한다. 같은 요청 ID로 다시 보내면 처음 결과를 돌려받는다 (6.2) |
| 이벤트 중복 도착 (회원) | 가입 이벤트는 복제본이 이미 있으면 무시하고, 변경 이벤트는 있는 복제본만 갱신한다. 생성은 가입 이벤트로만 해서 가입·변경이 동시에 처리돼도 변경 정보를 덮어쓰지 않는다 (D17) |
| 이의 제기 명령 중복·재전달 | 명령 이력(`lecture_objection_command`)이 `commandId`로 처음 결과를 재현하고, 내용이 다르면 `OBJECTION_COMMAND_CONFLICT`다. 판단·철회 이후 늦게 도착한 접수 재시도도 원래 결과를 재현한다. 같은 명령이 동시에 오면 명령 ID 유일 제약으로 한쪽이 실패하고, 다시 받으면 재현된다 (D60, D71, #278) |
| 이의 제기 판단 ↔ 철회 (같은 문의) | 고객센터가 문의 행을 잠그고 `FILED`일 때 하나만 선점한다. 진 쪽은 409 `OBJECTION_IN_PROGRESS`, 같은 요청 재시도는 202 (D60, D71) |
| 이벤트 발행 실패 (서버 실행 중) | 발행 앱마다 재발행 배치(6.1, D71) |
| 배치 다중 실행 | EC2 1대·애플리케이션 1개이지만, HP 작업은 선점과 lease로 다중 실행기에서도 한 실행기만 결과를 기록한다. 지급 대상 선점·자동 구매 확정은 항목마다 별도 트랜잭션과 낙관적 락으로 처리해 한 건 실패가 전체를 막지 않게 한다 |

배치 (`@Scheduled`, KST)

| 배치 | 주기 | 대상 |
| --- | --- | --- |
| 자동 구매 확정 | 10분마다 (`lecture.batch.auto-confirm-cron`, 기본 `0 */10 * * * *`, D41) | `PURCHASED` && `auto_confirm_at <= now` |
| 지급 대상 선점 | 매일 01:00 (`lecture.batch.payout-cron`, 기본 `0 0 1 * * *`, D50) | `CONFIRMED` && `payout_scheduled_at <= now` && `objection_status IN (NONE, REJECTED)` → `PAYOUT_PENDING` + PAYOUT 작업 (D59) |
| HP 작업 실행·재시도 | 1분마다 (`lecture.hp-task.retry-cron`, 기본 `0 * * * * *`, D57) | `PENDING` && `next_attempt_at <= now`, 또는 `PROCESSING` && `lease_until < now` |
| 강의 이벤트 재발행 | 1분마다 (`lecture.event-republish.cron`, 기본 `5 * * * * *`, D71) | 발행 후 1분이 지난 미완료 강의 이벤트 발행 기록 100건 |
| 서비스 간 HP 대사 | 매일 04:30:25 (`lecture.batch.hp-reconciliation-cron`, 기본 `25 30 4 * * *`, D74) | 최근 7일 안에 HP 작업이 바뀐 항목 + 커서 뒤에 바뀐 지갑 보류 (10.1) |

- 지갑 동시성 제어 방식(`AGENTS.md` 11장 미정)은 강의 모듈이 정하지 않는다. 강의 모듈은 상태 머신·작업 선점·트랜잭션 경계를 보장하고, 지갑의 보장은 billing 연동 계약(6.2)을 따른다.
- 스케줄링은 #157에서 켰다(`apps`의 `SchedulingConfig`). 배치 빈은 global `@ConditionalOnSchedulingEnabled`(`hapbang.scheduling.enabled`)를 붙여 앱에서만 실행되고 테스트에서는 꺼진다. 테스트는 처리 빈의 메서드를 직접 호출한다. cron에는 `zone = "Asia/Seoul"`을 쓴다. 새 배치는 `SchedulingEnabledTest`에 등록 확인을 더한다.

### 10.1 서비스 간 HP 대사 (#580, D74)

주문 항목과 지갑은 database가 따로라 항목 상태와 지갑의 보류·지급을 한 트랜잭션으로 맞출 수 없다. 대사의 주체는 강의이고 지갑은 조회 API만 준다(`docs/api/internal-billing.md` 4장, #577). 강의는 매일 주문 항목 상태를 지갑 기록과 비교해 어긋난 건을 남긴다. **고치지 않는다.** 정정은 원인을 확인한 관리자가 기존 수단(HP 작업 재시도·보류 무효화, reference.md 8.2.1)으로 한다. 출연 대사(#581, `docs/appearance/module.md` 25장)와 같은 운영 모양이다.

**비교: 항목 상태 → 지갑, 한 단계.** 상태 전이와 HP 작업 등록이 같은 트랜잭션이라(D57) 작업의 합을 따로 비교하지 않는다. 강의는 늘 전액 반환·전액 지급이라 보류 상태 이름 대신 합계 금액 네 개(`remaining`·`released`·`paid`·`captured`의 `totalHp`)를 비교한다. 금액이 맞으면 상태(`HELD`·`RELEASED`·`PAID`)도 맞다.

| 항목 상태 | 지갑 보류 | 지급 건 |
| --- | --- | --- |
| `HOLD_PENDING`·`HOLD_COMPENSATING` | 진행 중이라 비교하지 않음 | |
| `HOLD_FAILED` | 없음, 무효화, 또는 남은 보류·지급·확정 차감 0 | 없음 |
| `PURCHASED`·`CONFIRMED`·`CANCEL_PENDING`·`RETURN_PENDING`·`PAYOUT_PENDING` | 최초 = 남은 = 가격, 보류한 회원 = 구매자 | 없음 |
| `CANCELED`·`RETURNED` | 최초 = 반환 = 가격, 남은 0 | 없음 |
| `PAID_OUT` | 최초 = 지급 = 가격, 남은 0 | 하나: 원천 `LECTURE_ORDER_ITEM` + 항목 ID, `NORMAL`, 받는 회원 = 판매자, `paidAmountHp` = 가격, `COMPLETED` |

규칙(`LectureHpReconciliationRule`, 출연과 뜻이 같은 규칙은 같은 이름)

| 규칙 | 어긋남 |
| --- | --- |
| `HOLD_MISSING` | 결제 실패가 아닌데 보류가 없음 |
| `HOLD_MISMATCH` | 최초 보류액 ≠ 가격, 또는 보류한 회원 ≠ 구매자 |
| `HOLD_BALANCE_MISMATCH` | 남은 보류·반환·지급·확정 차감이 기대값과 다름 (예: 취소됐는데 `HELD`) |
| `HOLD_NOT_CLOSED` | 결제 실패인데 남은 보류·지급·확정 차감이 있음 |
| `VOIDED_WHILE_ACTIVE` | 결제 실패가 아닌데 무효화 기록이 있음 |
| `SETTLEMENT_MISSING` | `PAID_OUT`인데 지급 건이 없음 |
| `SETTLEMENT_MISMATCH` | 지급 건의 원천·유형·받는 회원·금액·상태가 다르거나 2건 이상 |
| `SETTLEMENT_UNEXPECTED` | `PAID_OUT`이 아닌데 지급 건이 있음 (#142·#182 시절 지급 예정 건 포함) |
| `WALLET_ONLY_HOLD` | 지갑 보류 목록의 주문 항목 ID가 강의에 없음 |
| `HP_TASK_DEAD` | 표시만 함. 항목에 `DEAD` 작업이 있어 비교하지 않고 어긋난 건 수에서 뺀다(관리자 HP 작업 화면에서 이미 보인다) |

실행

- **대상**: ① 강의 쪽은 비교 범위(`lookback`, 7일) 안에 HP 작업이 바뀐 주문 항목, ② 지갑 쪽은 저장한 커서 뒤에 바뀐 보류(`listHolds`, `hasMore`가 `false`일 때까지). 둘을 합쳐 500개씩 `queryHolds`로 조회한다. 목록 응답의 상태는 강의를 읽은 시점보다 오래됐을 수 있어 비교에는 `queryHolds` 결과만 쓴다. 목록은 대상 모으기와 지갑에만 있는 보류 찾기에 쓴다.
- **빼는 것(진행 중)**: 결제 단계(`HOLD_PENDING`·`HOLD_COMPENSATING`), 같은 주문에 `PENDING`·`PROCESSING` 작업이 있는 항목, 마지막 변경이 진행 중 유예(`settle-grace`, 2시간) 안인 항목. 항목에는 변경 시각 컬럼이 없어 마지막 변경은 그 **주문**의 HP 작업 `updated_at`과 항목의 구매·취소·반환·지급 시각 중 가장 늦은 것이다. 결제 단계가 주문 단위라(D58) 다른 항목의 작업도 본다. 유예는 HP 작업 재시도 최대 간격(1시간)보다 길다.
- **커서**: `lecture_hp_reconciliation_cursor` 한 행. 끝까지 비교한 실행만 앞으로 옮긴다(지갑 `Instant`를 KST로 저장). 처음에는 처음부터 읽는다. 지갑은 최근 10초 안에 바뀐 보류를 주지 않으므로 커서를 되돌려 겹쳐 읽지 않는다.
- **비교하는 사이의 변경**: 강의를 읽은 뒤 지갑을 조회하기 전에 구매 취소의 즉시 반환이나 HP 작업 배치가 지갑을 바꾸면 거짓 어긋남이 생긴다. 어긋남이 나온 항목만 다시 읽어 항목 버전·상태나 마지막 변경 시각이 달라졌으면 이번에는 남기지 않는다(비교 수에도 넣지 않고 다음 실행이 다시 본다).
- **지갑 조회 실패**(회로 열림·결과 불명·거절·응답에서 ID 누락): 그 실행을 `INCOMPLETE`로 끝내고 커서를 옮기지 않는다. 그때까지 찾은 어긋남은 남기고, 결과를 모르는 항목은 어긋남으로 세지 않는다.
- **동시 실행**: 같은 앱에서 한 번만 돈다(실행 중 표시는 앱 메모리). 강의 앱은 `replicas: 1`·`maxSurge: 0`이라 pod가 겹치지 않는다. 배치는 실행 중이면 건너뛰고 관리자 실행은 409 `HP_RECONCILIATION_RUNNING`. 앱이 멈춰 `RUNNING`으로 남은 실행은 다음 실행이 시작될 때 `INCOMPLETE`("앱이 멈춰 끝나지 못한 실행")로 정리한다. replica를 늘리면 단일 실행 장치(billing `JobLock` 같은 advisory lock)를 먼저 둔다.
- **설정** (`lecture.hp-reconciliation.*`): `settle-grace` 2h, `lookback` 7d(유예보다 길어야 기동), `page-size` 500(1~500). 주기는 `lecture.batch.hp-reconciliation-cron`.

결과

- `lecture_hp_reconciliation_run`·`lecture_hp_reconciliation_mismatch`(reference.md 7.1). 오래된 기록은 지우지 않는다. 마이그레이션 `V202611031600`.
- 로그: 어긋난 건마다 ERROR `[LECTURE_HP_RECONCILIATION_MISMATCH] 강의 HP 대사에서 어긋난 건을 찾았다 rule=…, runId=…, orderItemId=…, orderId=…, itemStatus=…, lectureState=[…], walletState=[…]`(D77). DEAD 작업 때문에 비교하지 않은 건(`HP_TASK_DEAD`)은 표식 없이 WARN 한 줄. 끝까지 하지 못하면 WARN 한 줄(`failureReason` 포함).
- 지표: `lecture.hp_reconciliation.mismatches{rule}`(마지막으로 끝까지 비교한 실행의 규칙별 건수, 중단된 실행은 값을 바꾸지 않음), `lecture.hp_reconciliation.runs{status}`(끝난 실행 횟수). alert 규칙은 아직 없다(#343이 생기면 이 지표에 건다).
- 관리자 API: reference.md 8.2.1.1.
- 구조: `LectureHpReconciliationUseCase`(흐름·동시 실행·로그·지표), `LectureHpReconciliationComparer`(DB·지갑을 부르지 않는 순수 비교), `LectureHpReconciliationTarget`(항목 하나의 비교 값), `LectureHpReconciliationSupport`(트랜잭션 단위 읽기·기록, 지갑 호출은 트랜잭션 밖), `LectureHpReconciliationAdminFacade`. 지갑은 shared `BillingApiClient.queryHolds`·`listHolds`를 그대로 쓴다(회로·재시도 정책 같음).

---


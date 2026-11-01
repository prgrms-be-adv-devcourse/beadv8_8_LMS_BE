# Lecture Module — AGENTS.md

강의 모듈 코드를 고칠 때 지킬 것이다. 루트 `AGENTS.md`의 공통 규칙을 따르고, 정책·설계·결정 기록(D번호)은 [`docs/lecture/module.md`](../../docs/lecture/module.md)에 있다.

## 정책 해석

- 구매 확정 기한 "구매일 D+7"은 "구매일 다음 날부터 7일 경과"를 뜻하고, 자동 구매 확정 시각은 `(구매일 + 8일) 00:00 KST`다 (설계 5장, `OrderItem.AUTO_CONFIRM_DAYS = 8`). 리뷰에서 충돌로 지적돼도 구현은 바꾸지 않는다.
- 강의 등록·정보 수정·콘텐츠 교체는 업로드 완료된 본인 파일만 받는다 (#89).

## 주문 잠금 (D46)

- 주문을 닫을 수 있는 변경은 주문 행을 잠가 읽는다: 구매 취소 요청은 `findOrderForUpdate`, 구매자를 모르는 이의 제기 인정은 `findOrderForUpdateByItemId`, 반환·지급·보류 결과 기록(결과 처리기)은 `findForUpdateById`.
- 일반 조회로 읽으면 마지막 두 항목의 반환이 동시에 끝날 때 주문이 `PAID`로 남는다.

## billing 연동

- 강의 → billing은 주문 항목 단위(`LECTURE_ORDER_ITEM`)의 보류·반환·지급뿐이고, 모두 HP 작업(`LectureHpTask`, D57)으로 한다. shared `BillingPort`는 쓰지 않는다(#276·#277).
  - 업무 상태와 작업을 한 강의 트랜잭션에 등록하고(`LectureHpTaskSupport.register`, `MANDATORY`), 지갑 호출은 커밋 뒤 트랜잭션 밖에서 `LectureHpTaskExecutor.execute`가 shared `BillingApiClient`로 한다.
  - 완료 상태(`PAID`·`CANCELED`·`RETURNED`·`PAID_OUT`)는 목적별 결과 처리기(`LectureHpTaskResultHandler`)가 결과 기록 트랜잭션에서만 쓴다. 목적마다 처리기는 하나다.
  - 요청 안에서 한 번 시도하는 것은 주문 보류·보상(D58, 예산 D68)과 구매 취소(D59)다. 이의 제기 인정 반환과 지급은 HP 작업 배치가 실행한다(D70).
- billing 지급 예정 건(`Settlement` SCHEDULED)이 남아 있으면 그 항목의 지급이 `PAYOUT_ALREADY_SETTLED`로 거절되고 작업이 `DEAD`가 된다.

## 테스트

- 강의 등록 등 파일이 필요한 테스트는 `LectureFixture.uploadedFile(판매자, FileType)`을 `FileResourceRepository`로 저장해 ID를 쓴다 (S3 불필요).
- 테스트 주문
  - HP 보류·반환까지 확인할 때는 `LectureOrderFacade.createOrder`로 만든다.
  - 기한이 지난 주문이나 HP와 무관한 주문은 `LectureOrderFixture.order(구매자, 저장한 강의 목록, 구매 시각)`로 만들어 `LectureOrderRepository`로 저장한다 (결제가 끝난 주문, HP 작업 없음). 결제 처리 중 주문은 `pending`, HP 작업 없이 상태만 끝낼 때는 `cancel`·`payOut`·`acceptAndReturn`을 쓴다.
  - 기한 경계는 도메인 메서드에 `now`를 넘기는 단위 테스트로 확인한다.
- 지갑은 테스트 소스 `fake/FakeBillingApiClient`(HTTP 없음)다. `@Component`로 두지 않고 `FakeBillingApiClientConfig`의 `@Bean`으로 등록한다.
  - 기본은 성공이다(보류는 요청 금액, 반환·지급은 `setSettledHp`). `respond`로 다음 응답(실패·예외)을, `setDuringCall`로 호출 중 할 일(늦은 결과·동시 실행)을 정한다. `@BeforeEach`에서 `reset()`한다.
  - `BillingCallException`은 테스트 소스 `shared.billing.out.BillingCallExceptions`로 만든다.
  - 배치가 할 일은 `LectureHpTaskBatchUseCase.runDue()`로 실행한다. 재시도를 기다리는 작업은 `next_attempt_at`·`lease_until`을 당긴다.
  - 실행기 자체만 확인할 때는 `@TestBean(methodName = RecordingCancelResultHandler.FACTORY)`로 운영 `CANCEL` 처리기를 바꿔 끼운다. 같은 팩토리를 써야 테스트 컨텍스트가 늘지 않는다.

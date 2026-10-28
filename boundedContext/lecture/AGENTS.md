# Lecture Module — AGENTS.md

강의 모듈 코드를 고칠 때 지킬 것이다. 루트 `AGENTS.md`의 공통 규칙을 따르고, 정책·설계는 [`docs/lecture/module.md`](../../docs/lecture/module.md)(문서 지도에서 연동·참고 문서로 이어진다), 결정 기록(D번호)은 [`docs/lecture/decisions.md`](../../docs/lecture/decisions.md)에 있다.

## 정책 해석

- 구매 확정 기한 "구매일 D+7"은 "구매일 다음 날부터 7일 경과"를 뜻하고, 자동 구매 확정 시각은 `(구매일 + 8일) 00:00 KST`다 (설계 5장, `OrderItem.AUTO_CONFIRM_DAYS = 8`). 리뷰에서 충돌로 지적돼도 구현은 바꾸지 않는다.
- 강의 등록·정보 수정·콘텐츠 교체는 업로드 완료된 본인 파일만 받는다 (#89).

## 주문 잠금 (D46)

- 주문을 닫을 수 있는 변경은 주문 행을 잠가 읽는다: 구매 취소는 `findOrderForUpdate`, 구매자를 모르는 이의 제기 인정은 `findOrderForUpdateByItemId`.
- 일반 조회로 읽으면 마지막 두 항목을 동시에 처리할 때 주문이 `PAID`로 남는다.

## billing 연동

- 강의 → billing은 주문 항목 단위(`LECTURE_ORDER_ITEM`)의 보류·반환·지급(지급 배치 D50)뿐이다.
- 강의는 shared `BillingPort`(구현 billing `in.BillingPortAdapter`)를 호출한 쪽 트랜잭션 안에서 부른다. 옛 방식이다 (전환 #247~#251은 NOT_PLANNED, #271~#281로 재계획·보류). 새 모듈 간 동기 연동은 [내부 API](../../docs/dev/module-integration.md)로 만든다.
- billing 지급 예정 건(`Settlement` SCHEDULED, `LectureSettlementPort`)은 #196에서 제거됐다. 다시 등록되면 그 항목의 `payout`이 `PAYOUT_ALREADY_SETTLED`로 막힌다.

## 테스트

- 강의 등록 등 파일이 필요한 테스트는 `LectureFixture.uploadedFile(판매자, FileType)`을 `FileResourceRepository`로 저장해 ID를 쓴다 (S3 불필요).
- 테스트 주문
  - HP 보류·반환까지 확인할 때는 `LectureOrderFacade.createOrder`로 만든다 (`FakeBillingPort`가 보류를 기록하고, 구매 취소는 보류가 없으면 실패한다).
  - 기한이 지난 주문이나 HP와 무관한 주문은 `LectureOrderFixture.order(구매자, 저장한 강의 목록, 구매 시각)`로 만들어 `LectureOrderRepository`로 저장한다 (보류 없음, 지난 시각이면 기한이 지난 주문).
  - 기한 경계는 도메인 메서드에 `now`를 넘기는 단위 테스트로 확인한다.
- billing은 테스트 소스 `fake/FakeBillingPort`(@Component, shared `BillingPort` 구현)다.
  - 거절은 `BusinessException(FakeBillingErrorCode.X)`로 던지고 호출한 쪽 트랜잭션을 따른다. `@BeforeEach`에서 `reset()`한다.
  - 잔액 부족은 `setBalance`, 반환·지급액 불일치는 `overrideReleasedHp`·`overridePaidHp`, 동시 반환 경쟁은 `holdReleasesUntil`, 지급 실패는 `failPayoutFor(id[, FakeBillingErrorCode])`, 보류 없는 Fixture 주문은 `assumeHeld(구매자, 항목 ID, 금액)`.
  - 실제 billing 확인은 apps `LectureOrderPayoutIntegrationTest`·`LectureOrderBillingIntegrationTest`에 추가한다.

# Mock Billing 응답 (#391)

출연 테스트에서 지갑 내부 API(`docs/api/internal-billing.md`)를 흉내 내는 WireMock 응답이다. `support/MockBilling`이 쓴다.

- `__files/*-success.json`: 4개 경로(보류·반환·지급·위약금 정산)의 성공 응답(`RsData`). 필드는 `shared.billing.dto`의 `*ResultDto`와 같다.
  요청 본문의 값(참조 대상, 요청 ID, 받는 회원, 금액)을 그대로 돌려준다(WireMock 응답 템플릿). 금액이 `null`인 전액 반환·지급은 남은 보류액을 알 수 없어 0으로 돌려준다.
  `replayed`는 같은 요청 ID 재전송 시나리오에서만 `true`다.
- 실패 응답(`ProblemDetail` + `code`)은 `MockBilling`이 코드로 만든다.
- **지갑 계약 문서나 `*ResultDto`가 바뀌면 이 파일들을 함께 고친다.** `MockBillingTest`가 응답을 실제 `BillingApiClient`로 읽어 확인하므로, 어긋나면 테스트가 실패한다.

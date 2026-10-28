package com.hapbang.billing.out;

import java.time.Instant;

// 송금 요청·조회 결과 (#288). 이 결과는 "Mock 은행이 무엇이라고 답했는지"일 뿐이다. 금융 처리(보류 확정 차감·반환)는 이번 Issue에서
// 하지 않으며, 후속 환전 송금 UseCase가 아래 계약을 지킨다(2026-10-16 확정).
// - Succeeded: Mock 지급 성공 확정 → Billing이 최종 반영할 때 보류 확정 차감(Capture)
// - Failed: Mock 지급 확정 실패. **보류 반환(Release)을 자동으로 하지 않는다.** 같은 환전 건을 다시 송금할 수 있어야 하므로 REVENUE HELD를
//   유지한다. 다시 송금할 때는 새 지급 시도(새 지급 시도 ID·멱등키)로 보내고 앞선 시도 기록과 구분한다(지급 시도 ID 정책은 후속 UseCase에서 설계).
//   Release는 환전 거절·취소가 최종 확정됐을 때만 한다
// - Processing: 결과 미확정 → 보류 유지, 같은 지급 요청 ID로 조회
// - Unknown: 결과 불명확(시간 초과·연결 실패·5xx·해석 실패). 실패가 아니다 → 보류 유지, 같은 ID로 조회(새 ID로 보내지 않는다)
// - NotFound(조회 전용): 그 ID의 기록이 없다. 같은 ID로 다시 보낼지는 후속 UseCase가 판단한다(실패·재송금으로 단정하지 않는다)
// - Conflict(409): 같은 ID로 다른 내용을 보냈다(계약 위반, 우리 쪽 버그) → 자동 처리하지 않고 운영자 확인
// - Rejected: 잘못된 요청 등 그 밖의 4xx로 거절(은행이 송금을 판단하지 않음) → 운영자 확인
public sealed interface PayoutTransferResult {

    record Succeeded(String payoutRequestId, String transactionId, long amountKrw, Instant completedAt)
            implements PayoutTransferResult {
    }

    record Failed(String payoutRequestId, String failureCode, Instant completedAt) implements PayoutTransferResult {
    }

    record Processing(String payoutRequestId) implements PayoutTransferResult {
    }

    record NotFound(String payoutRequestId) implements PayoutTransferResult {
    }

    record Conflict(String payoutRequestId, String code) implements PayoutTransferResult {
    }

    record Rejected(String payoutRequestId, int httpStatus, String code) implements PayoutTransferResult {
    }

    record Unknown(String payoutRequestId, String reason) implements PayoutTransferResult {
    }
}

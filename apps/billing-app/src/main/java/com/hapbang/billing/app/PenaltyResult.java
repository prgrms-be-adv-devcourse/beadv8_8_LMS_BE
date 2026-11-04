package com.hapbang.billing.app;

// 위약금 정산 결과로, 취소 사건 하나의 정산 기준액(반환액 + 지급액)·호스트 반환 결과와 게스트 위약금 지급 결과를 담는다.
// 두 금액은 출연이 계산해 보낸 값 그대로다. 위약금(지급액)이 0인 요청은 받지 않으므로 지급 결과는 항상 있고,
// 반환액이 0이면 반환 상세를 만들지 않으므로 release는 null이다.
public record PenaltyResult(
        Long holdId,
        String cancellationId,
        long settlementAmountHp,
        long releasedHp,
        long paidHp,
        ReleaseResult release,
        PayoutResult payout,
        boolean replayed
) {
}

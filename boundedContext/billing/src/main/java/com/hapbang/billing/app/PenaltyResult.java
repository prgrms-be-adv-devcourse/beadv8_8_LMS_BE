package com.hapbang.billing.app;

// 위약금 정산 결과로, 취소 사건 하나의 정산 기준액·위약금·호스트 반환 결과와 게스트 위약금 지급 결과를 담는다.
// 위약금이 0 HP가 되는 요청은 받지 않으므로 반환·지급 결과가 모두 있다.
public record PenaltyResult(
        Long holdId,
        String cancellationId,
        long baseAmountHp,
        long penaltyHp,
        long releasedHp,
        ReleaseResult release,
        PayoutResult payout,
        boolean replayed
) {
}

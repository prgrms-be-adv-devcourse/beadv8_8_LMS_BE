package com.hapbang.billing.app;

import java.time.Instant;

// 강의·출연의 아직 지급하지 않은 수익을 상태별로 합한 통합 요약이다 (#295). 출처별 소계는 두지 않는다.
// asOf는 Billing이 통합 조회를 시작한 시각이다. 두 출처를 따로 부르므로 같은 시점의 스냅샷을 보장하지 않는다.
public record EarningSummaryResult(
        Amount confirmationPending,
        Amount scheduled,
        Amount onHold,
        Amount processing,
        Amount failed,
        Amount total,
        Instant asOf
) {

    static EarningSummaryResult of(Buckets buckets, Instant asOf) {
        Amount total = buckets.confirmationPending().plus(buckets.scheduled()).plus(buckets.onHold())
                .plus(buckets.processing()).plus(buckets.failed());
        return new EarningSummaryResult(buckets.confirmationPending(), buckets.scheduled(), buckets.onHold(),
                buckets.processing(), buckets.failed(), total, asOf);
    }

    // 한 상태의 금액(HP) 합계와 건수
    public record Amount(long amountHp, long count) {

        static final Amount ZERO = new Amount(0, 0);

        Amount plus(Amount other) {
            return new Amount(Math.addExact(amountHp, other.amountHp), Math.addExact(count, other.count));
        }
    }

    // 미지급 상태 다섯 개의 금액·건수. 출처 하나의 요약이거나 두 출처를 더한 값이다
    record Buckets(Amount confirmationPending, Amount scheduled, Amount onHold, Amount processing, Amount failed) {

        static final Buckets ZERO = new Buckets(Amount.ZERO, Amount.ZERO, Amount.ZERO, Amount.ZERO, Amount.ZERO);

        Buckets plus(Buckets other) {
            return new Buckets(confirmationPending.plus(other.confirmationPending), scheduled.plus(other.scheduled),
                    onHold.plus(other.onHold), processing.plus(other.processing), failed.plus(other.failed));
        }
    }
}

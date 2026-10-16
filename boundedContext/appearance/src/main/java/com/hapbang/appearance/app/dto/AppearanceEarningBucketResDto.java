package com.hapbang.appearance.app.dto;

/**
 * 출연 수익 요약의 상태 하나 (#301): 금액(HP) 합계와 건수. Billing 공통 계약 {@code EarningBucketDto}와 같은 모양이다.
 */
public record AppearanceEarningBucketResDto(long amountHp, long count) {

    public static final AppearanceEarningBucketResDto EMPTY = new AppearanceEarningBucketResDto(0, 0);

    public AppearanceEarningBucketResDto plus(long amountHp, long count) {
        return new AppearanceEarningBucketResDto(this.amountHp + amountHp, this.count + count);
    }
}

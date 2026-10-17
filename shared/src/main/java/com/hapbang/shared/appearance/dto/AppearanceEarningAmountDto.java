package com.hapbang.shared.appearance.dto;

/**
 * 출연 수익 한 상태의 금액 합계와 건수 (#301).
 *
 * @param amountHp 금액 합계 (HP)
 * @param count    건수 (출연 수)
 */
public record AppearanceEarningAmountDto(long amountHp, long count) {

    public static final AppearanceEarningAmountDto ZERO = new AppearanceEarningAmountDto(0, 0);

    public AppearanceEarningAmountDto plus(long amountHp, long count) {
        return new AppearanceEarningAmountDto(this.amountHp + amountHp, this.count + count);
    }
}

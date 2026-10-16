package com.hapbang.appearance.out;

import com.hapbang.appearance.domain.AppearanceStatus;
import com.hapbang.appearance.domain.HpTaskStatus;

/**
 * 출연 수익 요약용 묶음 (#301): (출연 상태, 지급·위약금 작업 상태)별 건수와 출연료·위약금 합계.
 * 같은 묶음은 같은 지급 상태로 분류되므로, 분류는 묶음 단위로 Java에서 한다.
 *
 * @param taskStatus 작업이 없으면 null
 */
public record AppearanceEarningGroup(AppearanceStatus status, HpTaskStatus taskStatus, Long count, Long feeSum,
                                     Long penaltySum) {
}

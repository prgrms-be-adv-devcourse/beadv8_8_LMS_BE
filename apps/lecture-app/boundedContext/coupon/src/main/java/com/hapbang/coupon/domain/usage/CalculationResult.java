package com.hapbang.coupon.domain.usage;

import java.util.List;

/**
 * 주문 전체의 계산 결과.
 *
 * @param recommended 매핑을 생략해 쿠폰이 배치를 고른 결과인가(추천)
 * @param optimal     추천이 할인 합 최대임을 확인했는가. 탐색 상한에 걸려 찾은 최선만 돌려주면 {@code false}이고, 추천이 아니면
 *                    (선택 매핑·미사용) 최대 할인을 주장하지 않으므로 항상 {@code false}다
 * @param items       요청 항목 순서대로의 결과
 */
public record CalculationResult(
        boolean recommended,
        boolean optimal,
        List<ItemResult> items,
        long totalDiscount,
        long totalBuyerAmount
) {

    public CalculationResult {
        items = List.copyOf(items);
    }
}

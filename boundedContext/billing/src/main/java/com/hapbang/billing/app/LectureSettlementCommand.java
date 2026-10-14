package com.hapbang.billing.app;

import java.time.Instant;
import java.util.Objects;

// 강의 주문 항목 하나의 판매 수익 지급 예정 건을 등록하는 Billing 내부 요청이다(#142). 강의 연동(#146) 전이라 shared 계약에 두지 않는다.
// 구매자의 주문 항목 보류(HOLD:LECTURE_ORDER_ITEM:{orderItemId})가 이미 있어야 한다. 판매자가 인증된 인플루언서인지,
// 주문 항목이 유효한 구매인지, 실제 결제 HP·구매 시각은 강의가 정해 보낸다. amountHp는 정가가 아니라 그 주문 항목에 실제 결제한 HP다.
public record LectureSettlementCommand(
        Long buyerUserId,
        Long sellerUserId,
        Long orderItemId,
        long amountHp,
        Instant purchasedAt
) {

    // 필수 값을 확인한다. 금액은 Billing 금융 검증으로 UseCase가 확인한다.
    public LectureSettlementCommand {
        Objects.requireNonNull(buyerUserId, "구매 회원 ID는 필수입니다.");
        Objects.requireNonNull(sellerUserId, "판매 회원 ID는 필수입니다.");
        Objects.requireNonNull(orderItemId, "주문 항목 ID는 필수입니다.");
        Objects.requireNonNull(purchasedAt, "구매 시각은 필수입니다.");
    }
}

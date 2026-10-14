package com.hapbang.shared.billing.dto;

import java.time.Instant;
import java.util.Objects;

// 강의 주문 항목 하나의 판매 수익 지급 예정 건을 등록하는 요청이다 (LectureSettlementPort.schedule, #142·#182).
// 구매자의 주문 항목 보류(BillingPort.hold, LECTURE_ORDER_ITEM + 주문 항목 ID)가 같은 트랜잭션에서 먼저 성공해 있어야 한다.
// 판매자가 인증된 인플루언서인지, 주문 항목이 유효한 구매인지, 실제 결제 HP·구매 시각은 강의가 정해 보낸다.
// amountHp는 정가가 아니라 그 주문 항목에 실제 결제(보류)한 HP다. 지급 가능 시각은 Billing이 구매 시각의 KST 날짜로 계산한다.
// 이 record는 필수 값만 검사한다. 금액(0 이하 거절)·지갑·보류 대조·멱등성은 Billing이 BillingException으로 검증한다.
public record LectureSettlementCommand(
        Long buyerUserId,
        Long sellerUserId,
        Long orderItemId,
        long amountHp,
        Instant purchasedAt
) {

    // 필수 값을 확인한다.
    public LectureSettlementCommand {
        Objects.requireNonNull(buyerUserId, "구매 회원 ID는 필수입니다.");
        Objects.requireNonNull(sellerUserId, "판매 회원 ID는 필수입니다.");
        Objects.requireNonNull(orderItemId, "주문 항목 ID는 필수입니다.");
        Objects.requireNonNull(purchasedAt, "구매 시각은 필수입니다.");
    }
}

package com.hapbang.billing.app;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.hapbang.shared.billing.domain.BillingReferenceType;

// 강의·출연 HP 결제 보류 요청 (#494). 내부 API가 받은 shared HoldCommand를 in.BillingCommands가 바꿔 넘긴다.
// 보류 멱등키는 Billing이 참조 대상으로 만든다(HOLD:{referenceType}:{referenceId}, 참조 대상당 보류 1건).
// amountHp는 구매자 몫이고, promotions는 플랫폼 쿠폰의 플랫폼 몫(쿠폰마다 하나, 쿠폰 ID 오름차순으로 보관)이다. 없으면 빈 목록이고
// 지금까지의 보류와 같다. 금액(0 이하 거절, 구매자 몫 0은 플랫폼 몫이 있을 때만)은 BillingCommandValidator가 검사한다.
public record PaymentHoldCommand(Long userId, BillingReferenceType referenceType, Long referenceId, long amountHp,
        List<PaymentHoldPromotion> promotions) {

    public PaymentHoldCommand {
        Objects.requireNonNull(userId, "회원 ID는 필수입니다.");
        PaymentHoldRequests.requireHoldable(referenceType, referenceId);
        promotions = promotions == null ? List.of() : List.copyOf(promotions);
        if (!promotions.isEmpty() && referenceType != BillingReferenceType.LECTURE_ORDER_ITEM) {
            throw new IllegalArgumentException("플랫폼 몫은 강의 주문 항목에만 보류할 수 있습니다.");
        }
        promotions = promotions.stream().sorted(Comparator.comparing(PaymentHoldPromotion::couponId)).toList();
        Set<Long> couponIds = new HashSet<>();
        for (PaymentHoldPromotion promotion : promotions) {
            if (!couponIds.add(promotion.couponId())) {
                throw new IllegalArgumentException("같은 쿠폰의 플랫폼 몫이 두 번 있습니다: " + promotion.couponId());
            }
        }
    }

    // 플랫폼 몫이 없는 보류(지금까지의 보류와 같다)
    public PaymentHoldCommand(Long userId, BillingReferenceType referenceType, Long referenceId, long amountHp) {
        this(userId, referenceType, referenceId, amountHp, List.of());
    }

    public boolean hasPromotions() {
        return !promotions.isEmpty();
    }
}

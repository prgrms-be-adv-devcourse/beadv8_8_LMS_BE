package com.hapbang.auxiliary.notification.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.event.PayoutCompletedEvent;

/**
 * 하루 묶음에 더할 강의 원천 지급 한 건(ND-013·ND-032).
 *
 * @param settlementId   지급 건 ID. 같은 지급을 한 번만 더하는 키다(ND-103)
 * @param receiverUserId 받는 사람(판매자)
 * @param paidAmountHp   지급 HP
 * @param paidAt         지급 시각. 묶음 날짜와 발생 시각의 기준이다(ND-032·ND-101)
 */
public record DailyPayout(Long settlementId, Long receiverUserId, long paidAmountHp, Instant paidAt) {

    /** 묶음 날짜의 시간대. JVM 시간대와 상관없이 KST로 자른다(ND-032) */
    public static final ZoneId BUNDLE_ZONE = ZoneId.of("Asia/Seoul");

    /** 강의 원천 지급이면 하루 묶음 입력으로 바꾼다. 출연 원천은 한 건씩 만드는 지급 알림이라 비운다(ND-013) */
    public static Optional<DailyPayout> from(PayoutCompletedEvent event) {
        if (event.sourceType() != SettlementSourceType.LECTURE_ORDER_ITEM) {
            return Optional.empty();
        }
        return Optional.of(new DailyPayout(event.settlementId(), event.receiverUserId(), event.paidAmountHp(),
                event.paidAt()));
    }

    /** {@code paidAt}의 KST 날짜 */
    public LocalDate bundleDate() {
        return paidAt.atZone(BUNDLE_ZONE).toLocalDate();
    }

    /** 묶음 키: KST 날짜 {@code yyyy-MM-dd}(ND-103) */
    public String bundleKey() {
        return bundleDate().toString();
    }
}

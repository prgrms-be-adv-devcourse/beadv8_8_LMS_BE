package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

import com.hapbang.shared.billing.domain.PayoutType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.event.PayoutCompletedEvent;

/**
 * 강의 지급 하루 묶음의 입력·묶음 키·문구(ND-013·ND-032·ND-098·ND-100). test-spec NT-BUN-03(단위).
 */
class DailyPayoutTest {

    @Test
    void 강의_원천_지급만_하루_묶음_입력이_된다() {
        Instant paidAt = Instant.parse("2026-11-06T01:00:00Z");

        assertThat(DailyPayout.from(new PayoutCompletedEvent(71L, 20L, PayoutType.NORMAL,
                SettlementSourceType.LECTURE_ORDER_ITEM, 83L, 9_000L, paidAt))).hasValueSatisfying(payout -> {
                    assertThat(payout.settlementId()).isEqualTo(71L);
                    assertThat(payout.receiverUserId()).isEqualTo(20L);
                    assertThat(payout.paidAmountHp()).isEqualTo(9_000L);
                    assertThat(payout.paidAt()).isEqualTo(paidAt);
                });
        assertThat(DailyPayout.from(new PayoutCompletedEvent(72L, 20L, PayoutType.NORMAL,
                SettlementSourceType.APPEARANCE_REQUEST, 84L, 9_000L, paidAt))).isEmpty();
    }

    @Test
    void 묶음_키는_paidAt의_KST_날짜이고_JVM_시간대와_상관없다() {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : new String[] {"UTC", "Asia/Seoul", "America/Los_Angeles"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertThat(payoutAt("2026-11-06T14:59:59Z").bundleDate()).as(zone)
                        .isEqualTo(LocalDate.of(2026, 11, 6));
                assertThat(payoutAt("2026-11-06T15:00:00Z").bundleDate()).as(zone)
                        .isEqualTo(LocalDate.of(2026, 11, 7));
                assertThat(payoutAt("2026-11-06T15:00:00Z").bundleKey()).as(zone).isEqualTo("2026-11-07");
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void 문구는_날짜와_합계와_건수로_만들고_제목은_넣지_않는다() {
        Map<String, Object> payload = PayoutDailyTemplate.payload(LocalDate.of(2026, 11, 6), 12_000L, 50L);

        assertThat(payload).containsEntry("v", PayoutDailyTemplate.PAYLOAD_VERSION);
        assertThat(PayoutDailyTemplate.PAYOUT_COMPLETED_DAILY.kind()).isEqualTo("PAYOUT_COMPLETED_DAILY");
        assertThat(PayoutDailyTemplate.PAYOUT_COMPLETED_DAILY.render(payload))
                .isEqualTo("11월 6일 강의 판매 수익 12,000 HP가 지급되었습니다. (50건)");
    }

    @Test
    void jsonb로_읽어_숫자_타입이_바뀌어도_같은_문구를_만든다() {
        Map<String, Object> stored = Map.of("v", 1, "date", "2026-11-06", "totalPaidAmountHp", 12_000,
                "count", 50);

        assertThat(PayoutDailyTemplate.PAYOUT_COMPLETED_DAILY.render(stored))
                .isEqualTo("11월 6일 강의 판매 수익 12,000 HP가 지급되었습니다. (50건)");
    }

    private static DailyPayout payoutAt(String paidAt) {
        return DailyPayout.from(new PayoutCompletedEvent(1L, 2L, PayoutType.NORMAL,
                SettlementSourceType.LECTURE_ORDER_ITEM, 3L, 100L, Instant.parse(paidAt))).orElseThrow();
    }
}

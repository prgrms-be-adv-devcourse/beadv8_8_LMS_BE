package com.hapbang.shared.billing.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.shared.billing.domain.PayoutType;
import com.hapbang.shared.billing.domain.SettlementSourceType;

import tools.jackson.databind.json.JsonMapper;

/**
 * 알림센터용 지갑 이벤트 (#873, BD-162·BD-163)의 JSON 필드 이름과 호환. 필드 이름은 알림 계약
 * (docs/notification/event-contracts.md 3장)과 지갑 관례(Long ...Id·userId, long ...Hp, Instant ...At)를 따른다.
 */
class BillingNotificationEventJsonTest {

    private static final Instant AT = Instant.parse("2026-11-11T01:30:00Z");
    private static final Instant SCHEDULED = Instant.parse("2026-12-09T15:00:00Z");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("새 이벤트 4종은 계약의 필드 이름으로 나가고, 쓰고 읽어도 같다")
    void newEventsFieldNames() {
        ChargeFailedEvent chargeFailed = new ChargeFailedEvent(1L, 7L, 10_000L, 11_000L, AT);
        RefundFailedEvent refundFailed = new RefundFailedEvent(2L, 7L, 5_000L, AT);
        RefundManualReviewRequiredEvent manualReview = new RefundManualReviewRequiredEvent(3L, 7L, 5_000L, AT);
        ExchangeApprovedEvent approved = new ExchangeApprovedEvent(4L, 7L, 30_000L, SCHEDULED, AT);

        assertThat(jsonMapper.writeValueAsString(chargeFailed)).contains("\"chargeId\":1", "\"userId\":7",
                "\"amountHp\":10000", "\"paymentAmountKrw\":11000", "\"failedAt\":\"2026-11-11T01:30:00Z\"");
        assertThat(jsonMapper.writeValueAsString(refundFailed)).contains("\"refundRequestId\":2", "\"userId\":7",
                "\"requestedAmountHp\":5000", "\"failedAt\"");
        assertThat(jsonMapper.writeValueAsString(manualReview)).contains("\"refundRequestId\":3", "\"userId\":7",
                "\"requestedAmountHp\":5000", "\"manualReviewRequiredAt\"");
        assertThat(jsonMapper.writeValueAsString(approved)).contains("\"exchangeId\":4", "\"userId\":7",
                "\"requestedAmountHp\":30000", "\"payoutScheduledAt\":\"2026-12-09T15:00:00Z\"", "\"approvedAt\"");

        assertThat(roundTrip(chargeFailed)).isEqualTo(chargeFailed);
        assertThat(roundTrip(refundFailed)).isEqualTo(refundFailed);
        assertThat(roundTrip(manualReview)).isEqualTo(manualReview);
        assertThat(roundTrip(approved)).isEqualTo(approved);
    }

    @Test
    @DisplayName("지급 완료 이벤트는 출처 제목을 sourceTitle로 담고, 기존 7인자 생성자와 옛 JSON은 제목 null이다")
    void payoutCompletedSourceTitle() {
        PayoutCompletedEvent titled = new PayoutCompletedEvent(1L, 2L, PayoutType.NORMAL,
                SettlementSourceType.APPEARANCE_REQUEST, 7L, 9_000L, AT, "합방 출연");
        PayoutCompletedEvent untitled = new PayoutCompletedEvent(1L, 2L, PayoutType.NORMAL,
                SettlementSourceType.APPEARANCE_REQUEST, 7L, 9_000L, AT);

        assertThat(untitled.sourceTitle()).isNull();
        assertThat(jsonMapper.writeValueAsString(untitled)).doesNotContain("sourceTitle");
        assertThat(jsonMapper.writeValueAsString(titled)).contains("\"sourceTitle\":\"합방 출연\"");
        assertThat(roundTrip(titled)).isEqualTo(titled);
        assertThat(jsonMapper.readValue("""
                {"settlementId":1,"receiverUserId":2,"payoutType":"NORMAL","sourceType":"APPEARANCE_REQUEST",
                 "sourceId":7,"paidAmountHp":9000,"paidAt":"2026-11-11T01:30:00Z"}
                """, PayoutCompletedEvent.class)).isEqualTo(untitled);
    }

    @SuppressWarnings("unchecked")
    private <T> T roundTrip(T event) {
        return (T) jsonMapper.readValue(jsonMapper.writeValueAsString(event), event.getClass());
    }
}

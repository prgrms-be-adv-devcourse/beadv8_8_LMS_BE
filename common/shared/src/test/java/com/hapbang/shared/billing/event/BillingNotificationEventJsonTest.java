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

    @Test
    @DisplayName("지급 완료 이벤트의 출처 제목은 255자까지 그대로, 넘으면 앞 255자만 담는다(Kafka 메시지 크기 상한, PR #873 리뷰)")
    void payoutCompletedTitleIsCappedAt255() {
        String longest = "가".repeat(255);

        assertThat(payoutCompleted(longest).sourceTitle()).isEqualTo(longest);
        assertThat(payoutCompleted(longest + "나").sourceTitle()).isEqualTo(longest);
        assertThat(payoutCompleted("가".repeat(100_000)).sourceTitle()).isEqualTo(longest);
        assertThat(payoutCompleted("합방 출연").sourceTitle()).isEqualTo("합방 출연");
        assertThat(payoutCompleted(null).sourceTitle()).isNull();
    }

    @Test
    @DisplayName("상한은 문자(code point) 기준이고, 경계의 이모지(서로게이트 쌍)를 반으로 자르지 않는다")
    void payoutCompletedTitleCapCountsCodePoints() {
        String emoji = "🎬"; // 🎬: UTF-16 두 칸, 문자 하나

        String capped = payoutCompleted(emoji.repeat(256)).sourceTitle();

        assertThat(capped.codePointCount(0, capped.length())).isEqualTo(255);
        assertThat(capped).isEqualTo(emoji.repeat(255));
        assertThat(payoutCompleted("가".repeat(254) + emoji + "나").sourceTitle())
                .isEqualTo("가".repeat(254) + emoji);
    }

    @Test
    @DisplayName("발행 기록에 남은 긴 제목의 JSON을 다시 읽어도 앞 255자만 담는다(재발행 본문도 상한을 지킨다)")
    void payoutCompletedJsonWithLongTitleIsCapped() {
        String json = """
                {"settlementId":1,"receiverUserId":2,"payoutType":"NORMAL","sourceType":"APPEARANCE_REQUEST",
                 "sourceId":7,"paidAmountHp":9000,"paidAt":"2026-11-11T01:30:00Z","sourceTitle":"%s"}
                """.formatted("가".repeat(300));

        assertThat(jsonMapper.readValue(json, PayoutCompletedEvent.class).sourceTitle()).isEqualTo("가".repeat(255));
    }

    private static PayoutCompletedEvent payoutCompleted(String sourceTitle) {
        return new PayoutCompletedEvent(1L, 2L, PayoutType.NORMAL, SettlementSourceType.APPEARANCE_REQUEST, 7L,
                9_000L, AT, sourceTitle);
    }

    @SuppressWarnings("unchecked")
    private <T> T roundTrip(T event) {
        return (T) jsonMapper.readValue(jsonMapper.writeValueAsString(event), event.getClass());
    }
}

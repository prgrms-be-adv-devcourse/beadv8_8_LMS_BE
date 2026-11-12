package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.shared.billing.event.ChargeFailedEvent;
import com.hapbang.shared.billing.event.ExchangeApprovedEvent;
import com.hapbang.shared.billing.event.RefundFailedEvent;
import com.hapbang.shared.billing.event.RefundManualReviewRequiredEvent;

/**
 * N7 지갑 4종의 알림 초안과 문구(#907, NT-GEN-19). 받는 사람·원본 대상 ID(ND-106)·이동 대상(ND-107)·발생 시각·문구(ND-108,
 * 알림 종류 0.7)·필수 값 검증.
 */
class WalletAlertsTest {

    private static final long USER = 5L;
    private static final Instant AT = Instant.parse("2026-11-12T01:00:00Z");

    @Test
    void 충전_실패는_충전한_회원에게_충전_ID로_만든다() {
        NotificationDraft draft = WalletAlerts.chargeFailed(new ChargeFailedEvent(11L, USER, 12_000L, 12_000L, AT));

        assertThat(draft.receiverUserId()).isEqualTo(USER);
        assertThat(draft.kind()).isEqualTo("CHARGE_FAILED");
        assertThat(draft.sourceEventType()).isEqualTo("ChargeFailedEvent");
        assertThat(draft.sourceTargetId()).isEqualTo("11");
        assertThat(draft.targetType()).isEqualTo("CHARGE");
        assertThat(draft.targetId()).isEqualTo("11");
        assertThat(draft.occurredAt()).isEqualTo(AT);
        assertThat(draft.payload()).containsEntry(NotificationPayload.VERSION, 1);
        // 문구에 쓰는 값만 남긴다(결제 금액 등 쓰지 않는 값은 저장하지 않는다)
        assertThat(draft.payload()).containsOnlyKeys(NotificationPayload.VERSION, "amountHp");
        assertThat(NotificationPayload.nicknames(draft.payload())).isEmpty();
        assertThat(render(draft)).isEqualTo("12,000 HP 충전에 실패했습니다.");
    }

    @Test
    void 환불_실패는_신청한_회원에게_환불_요청_ID로_만든다() {
        NotificationDraft draft = WalletAlerts.refundFailed(new RefundFailedEvent(21L, USER, 3_500L, AT));

        assertThat(draft.receiverUserId()).isEqualTo(USER);
        assertThat(draft.kind()).isEqualTo("REFUND_FAILED");
        assertThat(draft.sourceEventType()).isEqualTo("RefundFailedEvent");
        assertThat(draft.sourceTargetId()).isEqualTo("21");
        assertThat(draft.targetType()).isEqualTo("REFUND_REQUEST");
        assertThat(draft.targetId()).isEqualTo("21");
        assertThat(draft.occurredAt()).isEqualTo(AT);
        assertThat(draft.payload()).containsOnlyKeys(NotificationPayload.VERSION, "requestedAmountHp");
        assertThat(render(draft)).isEqualTo("3,500 HP 환불에 실패했습니다. 신청한 HP는 지갑으로 돌아왔습니다.");
    }

    @Test
    void 환불_운영자_확인_필요는_같은_환불_요청_ID라도_원본_이벤트_종류가_다르다() {
        NotificationDraft review = WalletAlerts.refundManualReviewRequired(
                new RefundManualReviewRequiredEvent(21L, USER, 3_500L, AT));
        NotificationDraft failed = WalletAlerts.refundFailed(new RefundFailedEvent(21L, USER, 3_500L, AT));

        assertThat(review.receiverUserId()).isEqualTo(USER);
        assertThat(review.kind()).isEqualTo("REFUND_MANUAL_REVIEW_REQUIRED");
        assertThat(review.sourceEventType()).isEqualTo("RefundManualReviewRequiredEvent");
        assertThat(review.sourceTargetId()).isEqualTo(failed.sourceTargetId());
        assertThat(review.sourceEventType()).isNotEqualTo(failed.sourceEventType());
        assertThat(review.targetType()).isEqualTo("REFUND_REQUEST");
        assertThat(review.targetId()).isEqualTo("21");
        assertThat(review.occurredAt()).isEqualTo(AT);
        assertThat(review.payload()).containsOnlyKeys(NotificationPayload.VERSION, "requestedAmountHp");
        assertThat(render(review)).isEqualTo("3,500 HP 환불은 운영자 확인이 필요해 처리가 늦어지고 있습니다.");
    }

    @Test
    void 환전_승인은_송금_예정일을_KST_날짜로_넣어_만든다() {
        NotificationDraft draft = WalletAlerts.exchangeApproved(new ExchangeApprovedEvent(31L, USER, 1_234_567L,
                Instant.parse("2026-12-10T01:00:00Z"), AT));

        assertThat(draft.receiverUserId()).isEqualTo(USER);
        assertThat(draft.kind()).isEqualTo("EXCHANGE_APPROVED");
        assertThat(draft.sourceEventType()).isEqualTo("ExchangeApprovedEvent");
        assertThat(draft.sourceTargetId()).isEqualTo("31");
        assertThat(draft.targetType()).isEqualTo("EXCHANGE");
        assertThat(draft.targetId()).isEqualTo("31");
        assertThat(draft.occurredAt()).isEqualTo(AT);
        assertThat(draft.payload()).containsOnlyKeys(NotificationPayload.VERSION, "requestedAmountHp",
                "payoutScheduledAt");
        assertThat(render(draft)).isEqualTo("1,234,567 HP 환전 신청이 승인되었습니다. 12월 10일에 송금됩니다.");
    }

    @Test
    void 송금_예정_시각이_UTC로_전날이어도_KST_날짜로_쓴다() {
        // 2026-12-09T15:30Z = 2026-12-10 00:30 KST
        NotificationDraft draft = WalletAlerts.exchangeApproved(new ExchangeApprovedEvent(32L, USER, 1L,
                Instant.parse("2026-12-09T15:30:00Z"), AT));

        assertThat(render(draft)).isEqualTo("1 HP 환전 신청이 승인되었습니다. 12월 10일에 송금됩니다.");
    }

    @Test
    void 필수_값이_비면_거절한다() {
        assertThatThrownBy(() -> WalletAlerts.chargeFailed(new ChargeFailedEvent(null, USER, 1L, 1L, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WalletAlerts.refundFailed(new RefundFailedEvent(21L, null, 1L, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WalletAlerts.refundManualReviewRequired(
                new RefundManualReviewRequiredEvent(21L, USER, 1L, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WalletAlerts.exchangeApproved(new ExchangeApprovedEvent(31L, USER, 1L, null, AT)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String render(NotificationDraft draft) {
        return WalletAlertTemplates.valueOf(draft.kind()).render(draft.payload());
    }
}

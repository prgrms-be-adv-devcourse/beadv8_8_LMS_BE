package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.shared.appearance.event.AppearanceApplicationSubmittedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;
import com.hapbang.shared.billing.domain.PayoutType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.event.ChargeCompletedEvent;
import com.hapbang.shared.billing.event.ExchangeCompletedEvent;
import com.hapbang.shared.billing.event.ExchangeFinalFailedEvent;
import com.hapbang.shared.billing.event.ExchangePayoutFailedEvent;
import com.hapbang.shared.billing.event.ExchangeRejectedEvent;
import com.hapbang.shared.billing.event.PayoutCompletedEvent;
import com.hapbang.shared.billing.event.RefundCompletedEvent;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * N5 11종의 알림 초안(ND-069): 받는 사람, 종류 코드, 원본 이벤트 종류·대상 ID(ND-073), 이동 대상(ND-074), 발생 시각(ND-021·ND-081),
 * 문구(ND-075, 카탈로그 0.5)와 값으로 같은 문구를 다시 만드는지(ND-020) 본다.
 * test-spec NT-GEN-10·13·15, NT-KAF-07, NT-IDEM-05
 */
class AlertDraftsTest {

    private static final Instant RECORD_TIME = Instant.parse("2026-11-11T05:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void 출연_요청_도착은_게스트에게_호스트_닉네임과_응답_기한으로_만들고_발생_시각은_레코드_시각이다() {
        AppearanceRequestSentEvent event = new AppearanceRequestSentEvent(new AppearanceRequestDto(31L, 10L, 20L,
                "겨울 합방", 50_000L, LocalDateTime.parse("2026-11-20T20:00:00"),
                LocalDateTime.parse("2026-11-13T18:30:00")));

        NotificationDraft draft = AlertDrafts.appearanceRequestSent(event, "호스트님", RECORD_TIME);

        assertDraft(draft, 20L, "APPEARANCE_REQUEST_SENT", "AppearanceRequestSentEvent", "31",
                "APPEARANCE_REQUEST", "31", RECORD_TIME);
        assertThat(NotificationPayload.nicknames(draft.payload())).containsExactly(Map.entry("10", "호스트님"));
        assertMessage(draft, "호스트님님이 '겨울 합방' 출연을 요청했습니다. 11월 13일 18:30까지 응답해 주세요.");
    }

    @Test
    void 모집_지원_도착은_호스트에게_게스트_닉네임으로_만들고_발생_시각은_지원_시각_KST다() {
        AppearanceApplicationSubmittedEvent event = new AppearanceApplicationSubmittedEvent(
                new AppearanceApplicationDto(41L, 5L, "봄 모집", 10L, 20L, LocalDateTime.parse("2026-11-11T09:15:00")));

        NotificationDraft draft = AlertDrafts.applicationSubmitted(event, "게스트님");

        assertDraft(draft, 10L, "APPEARANCE_APPLICATION_SUBMITTED", "AppearanceApplicationSubmittedEvent", "41",
                "APPEARANCE_RECRUIT", "5", Instant.parse("2026-11-11T00:15:00Z"));
        assertThat(NotificationPayload.nicknames(draft.payload())).containsExactly(Map.entry("20", "게스트님"));
        assertMessage(draft, "게스트님님이 '봄 모집' 모집에 지원했습니다.");
    }

    @Test
    void 충전_완료() {
        Instant at = Instant.parse("2026-11-11T01:00:00Z");
        NotificationDraft draft = AlertDrafts.chargeCompleted(new ChargeCompletedEvent(51L, 7L, 12_000L, 12_000L, at));

        assertDraft(draft, 7L, "CHARGE_COMPLETED", "ChargeCompletedEvent", "51", "CHARGE", "51", at);
        assertMessage(draft, "12,000 HP 충전이 완료되었습니다.");
    }

    @Test
    void 환불_완료는_전부_환불과_일부_환불의_문구가_다르다() {
        Instant at = Instant.parse("2026-11-11T01:00:00Z");
        NotificationDraft full = AlertDrafts.refundCompleted(new RefundCompletedEvent(61L, 7L, 3_000L, 3_000L, at));
        NotificationDraft partial = AlertDrafts.refundCompleted(new RefundCompletedEvent(62L, 7L, 5_000L, 2_000L, at));

        assertDraft(full, 7L, "REFUND_COMPLETED", "RefundCompletedEvent", "61", "REFUND_REQUEST", "61", at);
        assertMessage(full, "3,000 HP 환불이 완료되었습니다.");
        assertMessage(partial, "환불 신청 5,000 HP 중 2,000 HP가 환불되었습니다.");
    }

    @Test
    void 출연_원천_지급은_출연_ID로_이동하고_출연료와_위약금_문구가_다르며_제목은_없다() {
        Instant at = Instant.parse("2026-11-11T01:00:00Z");
        NotificationDraft normal = AlertDrafts.payoutCompleted(new PayoutCompletedEvent(71L, 20L, PayoutType.NORMAL,
                SettlementSourceType.APPEARANCE_REQUEST, 81L, 50_000L, at)).orElseThrow();
        NotificationDraft penalty = AlertDrafts.payoutCompleted(new PayoutCompletedEvent(72L, 20L, PayoutType.PENALTY,
                SettlementSourceType.RECRUIT_APPEARANCE, 82L, 5_000L, at)).orElseThrow();

        assertDraft(normal, 20L, "PAYOUT_COMPLETED", "PayoutCompletedEvent", "71", "APPEARANCE", "81", at);
        assertMessage(normal, "출연료 50,000 HP가 지급되었습니다.");
        assertThat(penalty.targetId()).isEqualTo("82");
        assertMessage(penalty, "위약금 5,000 HP가 지급되었습니다.");
    }

    @Test
    void 강의_원천_지급은_이_종류로_만들지_않는다() {
        assertThat(AlertDrafts.payoutCompleted(new PayoutCompletedEvent(73L, 20L, PayoutType.NORMAL,
                SettlementSourceType.LECTURE_ORDER_ITEM, 83L, 9_000L, Instant.now()))).isEmpty();
    }

    @Test
    void 환전_4종은_환전으로_이동하고_송금_실패만_시도_ID로_한_번씩_만든다() {
        Instant at = Instant.parse("2026-11-11T01:00:00Z");
        NotificationDraft rejected = AlertDrafts.exchangeRejected(new ExchangeRejectedEvent(91L, 7L, 30_000L, at));
        NotificationDraft completed = AlertDrafts.exchangeCompleted(
                new ExchangeCompletedEvent(91L, 7L, 30_000L, 1_500L, 28_500L, at));
        NotificationDraft failed = AlertDrafts.exchangePayoutFailed(
                new ExchangePayoutFailedEvent(91L, 301L, 2, 7L, 28_500L, at));
        NotificationDraft finalFailed = AlertDrafts.exchangeFinalFailed(
                new ExchangeFinalFailedEvent(91L, 7L, 30_000L, at));

        assertDraft(rejected, 7L, "EXCHANGE_REJECTED", "ExchangeRejectedEvent", "91", "EXCHANGE", "91", at);
        assertMessage(rejected, "30,000 HP 환전 신청이 거절되었습니다.");
        assertDraft(completed, 7L, "EXCHANGE_COMPLETED", "ExchangeCompletedEvent", "91", "EXCHANGE", "91", at);
        assertMessage(completed, "30,000 HP 환전 송금이 완료되었습니다. 입금액 28,500원 (수수료 1,500 HP)");
        assertDraft(failed, 7L, "EXCHANGE_PAYOUT_FAILED", "ExchangePayoutFailedEvent", "301", "EXCHANGE", "91", at);
        assertMessage(failed, "환전 송금(28,500원)이 지연되고 있습니다. 다시 시도합니다.");
        assertDraft(finalFailed, 7L, "EXCHANGE_FINAL_FAILED", "ExchangeFinalFailedEvent", "91", "EXCHANGE", "91",
                at);
        assertMessage(finalFailed, "환전 송금에 실패해 30,000 HP를 돌려드렸습니다.");
    }

    @Test
    void 가입_환영은_이동_대상이_없고_발생_시각은_레코드_시각이다() {
        NotificationDraft draft = AlertDrafts.userJoined(
                new UserDto(7L, "새회원", Role.USER, UserStatus.ACTIVE, 1L), RECORD_TIME);

        assertDraft(draft, 7L, "USER_JOINED", "UserJoinedEvent", "7", null, null, RECORD_TIME);
        assertMessage(draft, "합방에 오신 것을 환영합니다.");
    }

    @Test
    void 승급_승인은_회원_ID와_버전으로_사건을_나누고_이동_대상이_없다() {
        NotificationDraft draft = AlertDrafts.upgradeApproved(
                new UserDto(7L, "인플", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 4L), RECORD_TIME);

        assertDraft(draft, 7L, "INFLUENCER_UPGRADE_REQUEST_APPROVED", "UserModifiedEvent", "7:4", null, null,
                RECORD_TIME);
        assertMessage(draft, "인증된 인플루언서로 승급되었습니다.");
    }

    private static void assertDraft(NotificationDraft draft, long receiver, String kind, String sourceEventType,
            String sourceTargetId, String targetType, String targetId, Instant occurredAt) {
        assertThat(draft.receiverUserId()).isEqualTo(receiver);
        assertThat(draft.kind()).isEqualTo(kind);
        assertThat(draft.sourceEventType()).isEqualTo(sourceEventType);
        assertThat(draft.sourceTargetId()).isEqualTo(sourceTargetId);
        assertThat(draft.targetType()).isEqualTo(targetType);
        assertThat(draft.targetId()).isEqualTo(targetId);
        assertThat(draft.occurredAt()).isEqualTo(occurredAt);
        assertThat(draft.payload()).containsKey(NotificationPayload.VERSION);
    }

    /** 문구가 카탈로그 0.5와 같고, jsonb로 저장했다 읽은 값(숫자 타입이 바뀜)으로도 같은 문구가 나온다(ND-020) */
    @SuppressWarnings("unchecked")
    private static void assertMessage(NotificationDraft draft, String expected) {
        AlertTemplate template = AlertTemplate.valueOf(draft.kind());
        assertThat(template.render(draft.payload())).isEqualTo(expected);
        Map<String, Object> stored = JSON.readValue(JSON.writeValueAsString(draft.payload()), Map.class);
        assertThat(template.render(stored)).isEqualTo(expected);
    }
}

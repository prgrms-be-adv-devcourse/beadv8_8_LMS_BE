package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.shared.lecture.domain.LectureConfirmationType;
import com.hapbang.shared.lecture.dto.LectureOrderItemDto;
import com.hapbang.shared.lecture.event.LectureApprovedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionFiledEvent;
import com.hapbang.shared.lecture.event.LectureOrderHoldFailedEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemCanceledEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemConfirmedEvent;
import com.hapbang.shared.lecture.event.LectureOrderPaidEvent;
import com.hapbang.shared.lecture.event.LectureRejectedEvent;

/**
 * 강의 진행 알림 7종의 알림 초안과 문구(N7 강의, NT-GEN-21). 범위(ND-114)·원본 대상 ID(ND-115)·이동 대상(ND-116)·
 * 문구(ND-117, 알림 종류 0.9)·필수 값 검증.
 */
class LectureProgressAlertsTest {

    private static final long BUYER = 10L;
    private static final long SELLER = 20L;
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-11T10:00:00+09:00");
    private static final Instant AT_INSTANT = Instant.parse("2026-11-11T01:00:00Z");

    @Test
    void 검수_승인은_판매자에게_강의로_이동하고_검수_회차를_원본_대상_ID에_넣는다() {
        NotificationDraft draft = LectureProgressAlerts.approved(new LectureApprovedEvent(7L, SELLER, "자바 입문", 2, AT));

        assertThat(draft.receiverUserId()).isEqualTo(SELLER);
        assertThat(draft.kind()).isEqualTo("LECTURE_APPROVED");
        assertThat(draft.sourceEventType()).isEqualTo("LectureApprovedEvent");
        assertThat(draft.sourceTargetId()).isEqualTo("7:2");
        assertThat(draft.targetType()).isEqualTo("LECTURE");
        assertThat(draft.targetId()).isEqualTo("7");
        assertThat(draft.occurredAt()).isEqualTo(AT_INSTANT);
        assertThat(draft.payload()).containsEntry(NotificationPayload.VERSION, 1);
        assertThat(NotificationPayload.nicknames(draft.payload())).isEmpty();
        assertThat(render(draft)).isEqualTo("'자바 입문' 강의 검수가 승인되어 판매가 시작되었습니다.");
    }

    @Test
    void 검수_반려는_판매자에게_반려_사유를_넣어_만든다() {
        NotificationDraft draft = LectureProgressAlerts.rejected(
                new LectureRejectedEvent(7L, SELLER, "자바 입문", "목차와 영상이 다릅니다.", 3, AT));

        assertThat(draft.receiverUserId()).isEqualTo(SELLER);
        assertThat(draft.kind()).isEqualTo("LECTURE_REJECTED");
        assertThat(draft.sourceEventType()).isEqualTo("LectureRejectedEvent");
        assertThat(draft.sourceTargetId()).isEqualTo("7:3");
        assertThat(draft.targetType()).isEqualTo("LECTURE");
        assertThat(draft.targetId()).isEqualTo("7");
        assertThat(draft.occurredAt()).isEqualTo(AT_INSTANT);
        assertThat(render(draft)).isEqualTo("'자바 입문' 강의 검수가 반려되었습니다. 사유: 목차와 영상이 다릅니다.");
    }

    @Test
    void 구매_완료는_구매자에게_주문으로_이동하고_항목이_하나면_제목만_쓴다() {
        NotificationDraft draft = LectureProgressAlerts.paid(
                new LectureOrderPaidEvent(300L, BUYER, List.of(item(1L, "자바 입문")), AT));

        assertThat(draft.receiverUserId()).isEqualTo(BUYER);
        assertThat(draft.kind()).isEqualTo("LECTURE_ORDER_PAID");
        assertThat(draft.sourceEventType()).isEqualTo("LectureOrderPaidEvent");
        assertThat(draft.sourceTargetId()).isEqualTo("300");
        assertThat(draft.targetType()).isEqualTo("LECTURE_ORDER");
        assertThat(draft.targetId()).isEqualTo("300");
        assertThat(draft.occurredAt()).isEqualTo(AT_INSTANT);
        assertThat(render(draft)).isEqualTo("'자바 입문' 강의 구매가 완료되었습니다.");
    }

    @Test
    void 구매_완료_항목이_여럿이면_첫_항목_제목과_나머지_수를_쓴다() {
        NotificationDraft draft = LectureProgressAlerts.paid(new LectureOrderPaidEvent(300L, BUYER,
                List.of(item(1L, "자바 입문"), item(2L, "스프링"), item(3L, "JPA")), AT));

        assertThat(render(draft)).isEqualTo("'자바 입문' 외 2개 강의 구매가 완료되었습니다.");
    }

    @Test
    void 결제_실패는_구매자에게_원인_없이_주문으로_이동한다() {
        NotificationDraft one = LectureProgressAlerts.holdFailed(
                new LectureOrderHoldFailedEvent(301L, BUYER, List.of(item(1L, "자바 입문")), AT));
        NotificationDraft many = LectureProgressAlerts.holdFailed(new LectureOrderHoldFailedEvent(302L, BUYER,
                List.of(item(1L, "자바 입문"), item(2L, "스프링")), AT));

        assertThat(one.receiverUserId()).isEqualTo(BUYER);
        assertThat(one.kind()).isEqualTo("LECTURE_ORDER_HOLD_FAILED");
        assertThat(one.sourceEventType()).isEqualTo("LectureOrderHoldFailedEvent");
        assertThat(one.sourceTargetId()).isEqualTo("301");
        assertThat(one.targetType()).isEqualTo("LECTURE_ORDER");
        assertThat(one.targetId()).isEqualTo("301");
        assertThat(one.occurredAt()).isEqualTo(AT_INSTANT);
        assertThat(render(one)).isEqualTo("'자바 입문' 강의 결제에 실패했습니다.");
        assertThat(render(many)).isEqualTo("'자바 입문' 외 1개 강의 결제에 실패했습니다.");
    }

    @Test
    void 구매_취소는_구매자에게_주문_항목으로_이동하고_반환_HP를_천_단위로_쓴다() {
        NotificationDraft draft = LectureProgressAlerts.canceled(
                new LectureOrderItemCanceledEvent(900L, BUYER, "자바 입문", 12_000L, AT));

        assertThat(draft.receiverUserId()).isEqualTo(BUYER);
        assertThat(draft.kind()).isEqualTo("LECTURE_ORDER_ITEM_CANCELED");
        assertThat(draft.sourceEventType()).isEqualTo("LectureOrderItemCanceledEvent");
        assertThat(draft.sourceTargetId()).isEqualTo("900");
        assertThat(draft.targetType()).isEqualTo("LECTURE_ORDER_ITEM");
        assertThat(draft.targetId()).isEqualTo("900");
        assertThat(draft.occurredAt()).isEqualTo(AT_INSTANT);
        assertThat(render(draft)).isEqualTo("'자바 입문' 구매가 취소되어 12,000 HP를 돌려드렸습니다.");
    }

    @Test
    void 반환_HP가_0인_구매_취소는_HP를_말하지_않는다() {
        NotificationDraft draft = LectureProgressAlerts.canceled(
                new LectureOrderItemCanceledEvent(900L, BUYER, "무료 강의", 0L, AT));

        assertThat(render(draft)).isEqualTo("'무료 강의' 구매가 취소되었습니다.");
    }

    @Test
    void 자동_구매_확정만_구매자에게_주문_항목으로_이동해_만든다() {
        List<NotificationDraft> drafts = LectureProgressAlerts.confirmed(confirmed(LectureConfirmationType.AUTO));

        assertThat(drafts).singleElement().satisfies(draft -> {
            assertThat(draft.receiverUserId()).isEqualTo(BUYER);
            assertThat(draft.kind()).isEqualTo("LECTURE_ORDER_ITEM_CONFIRMED");
            assertThat(draft.sourceEventType()).isEqualTo("LectureOrderItemConfirmedEvent");
            assertThat(draft.sourceTargetId()).isEqualTo("900");
            assertThat(draft.targetType()).isEqualTo("LECTURE_ORDER_ITEM");
            assertThat(draft.targetId()).isEqualTo("900");
            assertThat(draft.occurredAt()).isEqualTo(AT_INSTANT);
            assertThat(render(draft)).isEqualTo("구매 확정 기한이 지나 '자바 입문' 구매가 확정되었습니다.");
        });
    }

    @Test
    void 직접_확정과_첫_재생_확정은_알림을_만들지_않는다() {
        assertThat(LectureProgressAlerts.confirmed(confirmed(LectureConfirmationType.DIRECT))).isEmpty();
        assertThat(LectureProgressAlerts.confirmed(confirmed(LectureConfirmationType.FIRST_PLAY))).isEmpty();
    }

    @Test
    void 이의_제기_접수는_구매자와_판매자에게_문구를_나눠_하나씩_만든다() {
        List<NotificationDraft> drafts = LectureProgressAlerts.objectionFiled(
                new LectureObjectionFiledEvent(55L, 900L, BUYER, SELLER, "자바 입문", AT));

        assertThat(drafts).extracting(NotificationDraft::receiverUserId).containsExactly(BUYER, SELLER);
        assertThat(drafts).allSatisfy(draft -> {
            assertThat(draft.kind()).isEqualTo("LECTURE_OBJECTION_FILED");
            assertThat(draft.sourceEventType()).isEqualTo("LectureObjectionFiledEvent");
            assertThat(draft.sourceTargetId()).isEqualTo("55");
            assertThat(draft.targetType()).isEqualTo("LECTURE_ORDER_ITEM");
            assertThat(draft.targetId()).isEqualTo("900");
            assertThat(draft.occurredAt()).isEqualTo(AT_INSTANT);
        });
        assertThat(render(drafts.get(0))).isEqualTo("'자바 입문' 이의 제기가 접수되었습니다.");
        assertThat(render(drafts.get(1))).isEqualTo("'자바 입문' 이의 제기가 접수되어 지급이 멈췄습니다.");
    }

    @Test
    void 필수_값이_비면_거절한다() {
        assertThatThrownBy(() -> LectureProgressAlerts.approved(new LectureApprovedEvent(7L, SELLER, "자바", null, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LectureProgressAlerts.approved(new LectureApprovedEvent(7L, null, "자바", 1, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LectureProgressAlerts.rejected(
                new LectureRejectedEvent(7L, SELLER, "자바", null, 1, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LectureProgressAlerts.paid(new LectureOrderPaidEvent(300L, BUYER, List.of(), AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LectureProgressAlerts.paid(new LectureOrderPaidEvent(300L, BUYER, null, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LectureProgressAlerts.holdFailed(
                new LectureOrderHoldFailedEvent(301L, BUYER, List.of(item(1L, null)), AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LectureProgressAlerts.canceled(
                new LectureOrderItemCanceledEvent(900L, BUYER, "자바", null, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LectureProgressAlerts.confirmed(
                new LectureOrderItemConfirmedEvent(900L, BUYER, "자바", null, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LectureProgressAlerts.confirmed(
                new LectureOrderItemConfirmedEvent(900L, BUYER, "자바", LectureConfirmationType.AUTO, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LectureProgressAlerts.objectionFiled(
                new LectureObjectionFiledEvent(55L, 900L, BUYER, null, "자바", AT)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static LectureOrderItemDto item(long lectureId, String title) {
        return new LectureOrderItemDto(lectureId + 1000, lectureId, title, 5_000L);
    }

    private static LectureOrderItemConfirmedEvent confirmed(LectureConfirmationType type) {
        return new LectureOrderItemConfirmedEvent(900L, BUYER, "자바 입문", type, AT);
    }

    private static String render(NotificationDraft draft) {
        return LectureProgressTemplates.valueOf(draft.kind()).render(draft.payload());
    }
}

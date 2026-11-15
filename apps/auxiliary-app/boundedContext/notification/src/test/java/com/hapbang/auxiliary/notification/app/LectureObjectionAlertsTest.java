package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnActionRequiredEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnCompletedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionWithdrawnEvent;

/**
 * 이의 제기 결과 4종의 알림 초안과 문구(N6, NT-GEN-18, NT-IDEM-06). 받는 사람·원본 대상 ID(ND-083)·이동 대상(ND-084)·
 * 문구(ND-085, 알림 종류 0.6)·필수 값 검증(ND-086).
 */
class LectureObjectionAlertsTest {

    private static final long BUYER = 10L;
    private static final long SELLER = 20L;
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-11T10:00:00+09:00");
    private static final Instant AT_INSTANT = Instant.parse("2026-11-11T01:00:00Z");

    @Test
    void 인정_판단은_구매자와_판매자에게_받는_사람별_문구로_하나씩_만든다() {
        List<NotificationDraft> drafts = LectureObjectionAlerts.resolved(resolved(true));

        assertThat(drafts).extracting(NotificationDraft::receiverUserId).containsExactly(BUYER, SELLER);
        assertThat(drafts).allSatisfy(draft -> {
            assertThat(draft.kind()).isEqualTo("LECTURE_OBJECTION_RESOLVED");
            assertThat(draft.sourceEventType()).isEqualTo("LectureObjectionResolvedEvent");
            assertThat(draft.sourceTargetId()).isEqualTo("55");
            assertThat(draft.targetType()).isEqualTo("LECTURE_ORDER_ITEM");
            assertThat(draft.targetId()).isEqualTo("900");
            assertThat(draft.occurredAt()).isEqualTo(AT_INSTANT);
            assertThat(draft.payload()).containsEntry(NotificationPayload.VERSION, 1);
            assertThat(NotificationPayload.nicknames(draft.payload())).isEmpty();
        });
        assertThat(render(drafts.get(0))).isEqualTo("'자바 입문' 이의 제기가 인정되어 HP를 반환합니다.");
        assertThat(render(drafts.get(1))).isEqualTo("'자바 입문' 이의 제기가 인정되었습니다.");
    }

    @Test
    void 기각_판단은_구매자와_판매자_모두_기각_문구로_만든다() {
        List<NotificationDraft> drafts = LectureObjectionAlerts.resolved(resolved(false));

        assertThat(drafts).extracting(NotificationDraft::receiverUserId).containsExactly(BUYER, SELLER);
        assertThat(drafts).extracting(LectureObjectionAlertsTest::render)
                .containsExactly("'자바 입문' 이의 제기가 기각되었습니다.", "'자바 입문' 이의 제기가 기각되었습니다.");
    }

    @Test
    void 반환_완료는_구매자에게만_만든다() {
        NotificationDraft draft = LectureObjectionAlerts.returnCompleted(
                new LectureObjectionReturnCompletedEvent(55L, 900L, BUYER, SELLER, "자바 입문", AT));

        assertThat(draft.receiverUserId()).isEqualTo(BUYER);
        assertThat(draft.kind()).isEqualTo("LECTURE_OBJECTION_RETURN_COMPLETED");
        assertThat(draft.sourceEventType()).isEqualTo("LectureObjectionReturnCompletedEvent");
        assertThat(draft.sourceTargetId()).isEqualTo("55");
        assertThat(draft.targetType()).isEqualTo("LECTURE_ORDER_ITEM");
        assertThat(draft.targetId()).isEqualTo("900");
        assertThat(draft.occurredAt()).isEqualTo(AT_INSTANT);
        assertThat(render(draft)).isEqualTo("'자바 입문' 이의 제기 HP 반환이 완료되었습니다.");
    }

    @Test
    void 철회는_판매자에게만_만든다() {
        NotificationDraft draft = LectureObjectionAlerts.withdrawn(
                new LectureObjectionWithdrawnEvent("c2", 55L, 900L, BUYER, SELLER, "자바 입문", AT));

        assertThat(draft.receiverUserId()).isEqualTo(SELLER);
        assertThat(draft.kind()).isEqualTo("LECTURE_OBJECTION_WITHDRAWN");
        assertThat(draft.sourceEventType()).isEqualTo("LectureObjectionWithdrawnEvent");
        assertThat(draft.sourceTargetId()).isEqualTo("55");
        assertThat(draft.targetId()).isEqualTo("900");
        assertThat(draft.occurredAt()).isEqualTo(AT_INSTANT);
        assertThat(render(draft)).isEqualTo("'자바 입문' 이의 제기가 철회되었습니다.");
    }

    @Test
    void 반환_지연은_구매자에게_회차마다_원본_대상_ID를_나눠_만든다() {
        NotificationDraft first = LectureObjectionAlerts.returnActionRequired(returnDelay(1));
        NotificationDraft second = LectureObjectionAlerts.returnActionRequired(returnDelay(2));

        assertThat(first.receiverUserId()).isEqualTo(BUYER);
        assertThat(first.kind()).isEqualTo("LECTURE_OBJECTION_RETURN_ACTION_REQUIRED");
        assertThat(first.sourceEventType()).isEqualTo("LectureObjectionReturnActionRequiredEvent");
        assertThat(first.sourceTargetId()).isEqualTo("55:1");
        assertThat(second.sourceTargetId()).isEqualTo("55:2");
        assertThat(first.targetType()).isEqualTo("LECTURE_ORDER_ITEM");
        assertThat(first.targetId()).isEqualTo("900");
        assertThat(render(first)).isEqualTo("'자바 입문' HP 반환이 늦어지고 있습니다. 완료되면 다시 알려 드립니다.");
    }

    @Test
    void 필수_값이_비면_거절한다() {
        assertThatThrownBy(() -> LectureObjectionAlerts.resolved(
                new LectureObjectionResolvedEvent("c1", 55L, 900L, true, null, SELLER, "자바 입문", AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LectureObjectionAlerts.resolved(
                new LectureObjectionResolvedEvent("c1", 55L, 900L, true, BUYER, SELLER, null, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LectureObjectionAlerts.returnCompleted(
                new LectureObjectionReturnCompletedEvent(55L, 900L, BUYER, SELLER, "자바 입문", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LectureObjectionAlerts.withdrawn(
                new LectureObjectionWithdrawnEvent("c2", 55L, 900L, BUYER, null, "자바 입문", AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LectureObjectionAlerts.returnActionRequired(
                new LectureObjectionReturnActionRequiredEvent(55L, 900L, BUYER, SELLER, "자바 입문", null, AT)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static LectureObjectionResolvedEvent resolved(boolean accepted) {
        return new LectureObjectionResolvedEvent("c1", 55L, 900L, accepted, BUYER, SELLER, "자바 입문", AT);
    }

    private static LectureObjectionReturnActionRequiredEvent returnDelay(int sequence) {
        return new LectureObjectionReturnActionRequiredEvent(55L, 900L, BUYER, SELLER, "자바 입문", sequence, AT);
    }

    private static String render(NotificationDraft draft) {
        return LectureObjectionTemplates.valueOf(draft.kind()).render(draft.payload());
    }
}

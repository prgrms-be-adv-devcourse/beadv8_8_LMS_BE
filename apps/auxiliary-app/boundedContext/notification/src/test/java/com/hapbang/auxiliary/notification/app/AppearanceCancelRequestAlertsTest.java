package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.shared.appearance.dto.AppearanceCancelRequestDto;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestAcceptedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestRejectedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestedEvent;

/**
 * 출연 취소 요청 3종의 알림 초안과 문구(N6, NT-GEN-17). 받는 사람·원본 대상 ID(ND-083)·이동 대상(ND-084)·문구(ND-085, 알림 종류
 * 0.6)·필수 값 검증(ND-086).
 */
class AppearanceCancelRequestAlertsTest {

    private static final long HOST = 1L;
    private static final long GUEST = 2L;
    /** KST 2026-11-11 10:00 = UTC 01:00 */
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 11, 10, 0);
    private static final Instant AT_INSTANT = Instant.parse("2026-11-11T01:00:00Z");

    @Test
    void 취소_요청_도착은_호스트에게_게스트를_언급_회원으로_넣어_만든다() {
        NotificationDraft draft = AppearanceCancelRequestAlerts.requested(
                new AppearanceCancelRequestedEvent(cancelRequest(31L), AT), "게스트닉");

        assertThat(draft.receiverUserId()).isEqualTo(HOST);
        assertThat(draft.kind()).isEqualTo("APPEARANCE_CANCEL_REQUESTED");
        assertThat(draft.sourceEventType()).isEqualTo("AppearanceCancelRequestedEvent");
        assertThat(draft.sourceTargetId()).isEqualTo("31");
        assertThat(draft.targetType()).isEqualTo("APPEARANCE");
        assertThat(draft.targetId()).isEqualTo("7");
        assertThat(draft.occurredAt()).isEqualTo(AT_INSTANT);
        assertThat(draft.payload()).containsEntry(NotificationPayload.VERSION, 1);
        assertThat(NotificationPayload.nicknames(draft.payload())).isEqualTo(Map.of("2", "게스트닉"));
        assertThat(render(draft)).isEqualTo("게스트닉님이 '봄 합방' 출연 취소를 요청했습니다.");
    }

    @Test
    void 수락은_게스트에게_합의_취소_문구로_만든다() {
        NotificationDraft draft = AppearanceCancelRequestAlerts.accepted(
                new AppearanceCancelRequestAcceptedEvent(cancelRequest(31L), AT));

        assertThat(draft.receiverUserId()).isEqualTo(GUEST);
        assertThat(draft.kind()).isEqualTo("APPEARANCE_CANCEL_REQUEST_ACCEPTED");
        assertThat(draft.sourceEventType()).isEqualTo("AppearanceCancelRequestAcceptedEvent");
        assertThat(draft.sourceTargetId()).isEqualTo("31");
        assertThat(draft.targetType()).isEqualTo("APPEARANCE");
        assertThat(draft.targetId()).isEqualTo("7");
        assertThat(draft.occurredAt()).isEqualTo(AT_INSTANT);
        assertThat(NotificationPayload.nicknames(draft.payload())).isEmpty();
        assertThat(render(draft)).isEqualTo("'봄 합방' 출연 취소 요청이 수락되어 출연이 취소되었습니다.");
    }

    @Test
    void 거절과_자동_거절은_게스트에게_서로_다른_문구로_만든다() {
        NotificationDraft rejected = AppearanceCancelRequestAlerts.rejected(
                new AppearanceCancelRequestRejectedEvent(cancelRequest(31L), false, AT));
        NotificationDraft autoRejected = AppearanceCancelRequestAlerts.rejected(
                new AppearanceCancelRequestRejectedEvent(cancelRequest(31L), true, AT));

        assertThat(rejected.receiverUserId()).isEqualTo(GUEST);
        assertThat(rejected.kind()).isEqualTo("APPEARANCE_CANCEL_REQUEST_REJECTED");
        assertThat(rejected.sourceTargetId()).isEqualTo("31");
        assertThat(rejected.targetId()).isEqualTo("7");
        assertThat(render(rejected)).isEqualTo("'봄 합방' 출연 취소 요청이 거절되었습니다. 출연은 예정대로 진행됩니다.");
        assertThat(render(autoRejected))
                .isEqualTo("호스트가 응답하지 않아 '봄 합방' 출연 취소 요청이 자동 거절되었습니다. 출연은 예정대로 진행됩니다.");
    }

    @Test
    void 같은_출연의_두_번째_취소_요청은_원본_대상_ID가_달라_따로_만든다() {
        NotificationDraft first = AppearanceCancelRequestAlerts.requested(
                new AppearanceCancelRequestedEvent(cancelRequest(31L), AT), "게스트닉");
        NotificationDraft second = AppearanceCancelRequestAlerts.requested(
                new AppearanceCancelRequestedEvent(cancelRequest(32L), AT), "게스트닉");

        assertThat(first.sourceTargetId()).isEqualTo("31");
        assertThat(second.sourceTargetId()).isEqualTo("32");
        assertThat(second.targetId()).isEqualTo(first.targetId());
    }

    @Test
    void 필수_값이_비면_거절한다() {
        assertThatThrownBy(() -> AppearanceCancelRequestAlerts.requested(
                new AppearanceCancelRequestedEvent(cancelRequest(31L), null), "게스트닉"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppearanceCancelRequestAlerts.accepted(
                new AppearanceCancelRequestAcceptedEvent(null, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppearanceCancelRequestAlerts.rejected(new AppearanceCancelRequestRejectedEvent(
                new AppearanceCancelRequestDto(31L, 7L, null, HOST, GUEST, 1, AT.plusDays(20)), false, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppearanceCancelRequestAlerts.accepted(new AppearanceCancelRequestAcceptedEvent(
                new AppearanceCancelRequestDto(31L, 7L, "봄 합방", HOST, null, 1, AT.plusDays(20)), AT)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AppearanceCancelRequestDto cancelRequest(long cancelRequestId) {
        return new AppearanceCancelRequestDto(cancelRequestId, 7L, "봄 합방", HOST, GUEST, 1, AT.plusDays(20));
    }

    private static String render(NotificationDraft draft) {
        return AppearanceCancelRequestTemplates.valueOf(draft.kind()).render(draft.payload());
    }
}

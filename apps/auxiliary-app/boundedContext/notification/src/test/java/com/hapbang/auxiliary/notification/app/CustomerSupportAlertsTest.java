package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.shared.customersupport.event.InquiryAnsweredEvent;
import com.hapbang.shared.customersupport.event.LectureObjectionFileRejectedEvent;

/**
 * N7 고객센터 2종의 알림 초안과 문구(#918, NT-GEN-23). 받는 사람·원본 대상 ID(ND-124)·이동 대상(ND-125)·발생 시각·문구(ND-126,
 * 알림 종류 0.11)·필수 값 검증.
 */
class CustomerSupportAlertsTest {

    private static final long USER = 7L;
    private static final Instant AT = Instant.parse("2026-11-12T03:00:00Z");

    @Test
    void 문의_답변_도착은_문의한_회원에게_답변_ID로_만들고_문의로_이동한다() {
        NotificationDraft draft = CustomerSupportAlerts.inquiryAnswered(
                new InquiryAnsweredEvent(41L, 401L, USER, "환불 문의", "SERVICE_COMPLAINT", AT));

        assertThat(draft.receiverUserId()).isEqualTo(USER);
        assertThat(draft.kind()).isEqualTo("INQUIRY_ANSWERED");
        assertThat(draft.sourceEventType()).isEqualTo("InquiryAnsweredEvent");
        assertThat(draft.sourceTargetId()).isEqualTo("401");
        assertThat(draft.targetType()).isEqualTo("INQUIRY");
        assertThat(draft.targetId()).isEqualTo("41");
        assertThat(draft.occurredAt()).isEqualTo(AT);
        // 문구에 쓰는 값만 남긴다(문의 유형은 넣지 않는다, ND-126)
        assertThat(draft.payload()).containsEntry(NotificationPayload.VERSION, 1)
                .containsOnlyKeys(NotificationPayload.VERSION, "title");
        assertThat(NotificationPayload.nicknames(draft.payload())).isEmpty();
        assertThat(render(draft)).isEqualTo("'환불 문의' 문의에 답변이 등록되었습니다.");
    }

    @Test
    void 같은_문의의_두_번째_답변은_원본_대상_ID가_다르다() {
        NotificationDraft first = CustomerSupportAlerts.inquiryAnswered(
                new InquiryAnsweredEvent(41L, 401L, USER, "환불 문의", "OTHER", AT));
        NotificationDraft second = CustomerSupportAlerts.inquiryAnswered(
                new InquiryAnsweredEvent(41L, 402L, USER, "환불 문의", "OTHER", AT.plusSeconds(60)));

        assertThat(second.sourceTargetId()).isNotEqualTo(first.sourceTargetId());
        assertThat(second.targetId()).isEqualTo(first.targetId());
    }

    @Test
    void 이의_제기_접수_불가는_구매자에게_문의_ID로_만들고_문의로_이동한다() {
        NotificationDraft draft = CustomerSupportAlerts.lectureObjectionFileRejected(
                new LectureObjectionFileRejectedEvent(51L, 501L, USER, AT));

        assertThat(draft.receiverUserId()).isEqualTo(USER);
        assertThat(draft.kind()).isEqualTo("LECTURE_OBJECTION_FILE_REJECTED");
        assertThat(draft.sourceEventType()).isEqualTo("LectureObjectionFileRejectedEvent");
        assertThat(draft.sourceTargetId()).isEqualTo("51");
        assertThat(draft.targetType()).isEqualTo("INQUIRY");
        assertThat(draft.targetId()).isEqualTo("51");
        assertThat(draft.occurredAt()).isEqualTo(AT);
        assertThat(draft.payload()).containsOnlyKeys(NotificationPayload.VERSION);
        assertThat(render(draft)).isEqualTo("강의 내용 다름 이의 제기를 접수하지 못했습니다. 문의 내용을 확인해 주세요.");
    }

    @Test
    void 문의_제목이_빈_문자열이나_공백뿐이면_거절한다() {
        assertThatThrownBy(() -> CustomerSupportAlerts.inquiryAnswered(
                new InquiryAnsweredEvent(41L, 401L, USER, "", "OTHER", AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerSupportAlerts.inquiryAnswered(
                new InquiryAnsweredEvent(41L, 401L, USER, " \t ", "OTHER", AT)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정상_제목은_앞뒤_공백까지_받은_그대로_쓴다() {
        NotificationDraft draft = CustomerSupportAlerts.inquiryAnswered(
                new InquiryAnsweredEvent(41L, 401L, USER, " 환불 문의 ", "OTHER", AT));

        assertThat(draft.payload()).containsEntry("title", " 환불 문의 ");
        assertThat(render(draft)).isEqualTo("' 환불 문의 ' 문의에 답변이 등록되었습니다.");
    }

    @Test
    void 필수_값이_비면_거절한다() {
        assertThatThrownBy(() -> CustomerSupportAlerts.inquiryAnswered(
                new InquiryAnsweredEvent(null, 401L, USER, "t", "OTHER", AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerSupportAlerts.inquiryAnswered(
                new InquiryAnsweredEvent(41L, null, USER, "t", "OTHER", AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerSupportAlerts.inquiryAnswered(
                new InquiryAnsweredEvent(41L, 401L, null, "t", "OTHER", AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerSupportAlerts.inquiryAnswered(
                new InquiryAnsweredEvent(41L, 401L, USER, null, "OTHER", AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerSupportAlerts.inquiryAnswered(
                new InquiryAnsweredEvent(41L, 401L, USER, "t", "OTHER", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerSupportAlerts.lectureObjectionFileRejected(
                new LectureObjectionFileRejectedEvent(null, 501L, USER, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerSupportAlerts.lectureObjectionFileRejected(
                new LectureObjectionFileRejectedEvent(51L, 501L, null, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerSupportAlerts.lectureObjectionFileRejected(
                new LectureObjectionFileRejectedEvent(51L, 501L, USER, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String render(NotificationDraft draft) {
        return CustomerSupportAlertTemplates.valueOf(draft.kind()).render(draft.payload());
    }
}

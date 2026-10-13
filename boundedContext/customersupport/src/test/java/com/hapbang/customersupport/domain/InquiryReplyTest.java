package com.hapbang.customersupport.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class InquiryReplyTest {

    private static final Long USER_ID = 1L;
    private static final Long ADMIN_ID = 9L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 17, 0);

    @Test
    void 답변하면_문의가_답변_완료가_된다() {
        Inquiry inquiry = inquiry();

        InquiryReply reply = inquiry.reply(ADMIN_ID, " 확인했습니다. ");

        assertThat(reply.getInquiry()).isSameAs(inquiry);
        assertThat(reply.getAdminUserId()).isEqualTo(ADMIN_ID);
        assertThat(reply.getContent()).isEqualTo("확인했습니다.");
        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.ANSWERED);
        assertThat(inquiry.getReplies()).containsExactly(reply);
    }

    @Test
    void 답변_완료인_문의에도_답변을_더_달_수_있다() {
        Inquiry inquiry = inquiry();
        InquiryReply first = inquiry.reply(ADMIN_ID, "첫 답변");

        InquiryReply second = inquiry.reply(ADMIN_ID, "추가 답변");

        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.ANSWERED);
        assertThat(inquiry.getReplies()).containsExactly(first, second);
    }

    @Test
    void 삭제된_문의에는_답변할_수_없다() {
        Inquiry inquiry = inquiry();
        inquiry.delete(NOW);

        assertThatIllegalStateException().isThrownBy(() -> inquiry.reply(ADMIN_ID, "답변"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n"})
    void 답변_내용이_비어_있으면_달_수_없고_상태도_바뀌지_않는다(String blank) {
        Inquiry inquiry = inquiry();

        assertThatIllegalStateException().isThrownBy(() -> inquiry.reply(ADMIN_ID, blank));
        assertThat(inquiry.isWaiting()).isTrue();
        assertThat(inquiry.getReplies()).isEmpty();
    }

    @Test
    void 답변을_고치면_내용만_바뀌고_상태는_그대로다() {
        Inquiry inquiry = inquiry();
        InquiryReply reply = saved(inquiry.reply(ADMIN_ID, "첫 답변"), 1L);

        inquiry.editReply(1L, " 고친 답변 ");

        assertThat(reply.getContent()).isEqualTo("고친 답변");
        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.ANSWERED);
    }

    @Test
    void 답변을_빈_내용으로_고칠_수_없다() {
        Inquiry inquiry = inquiry();
        saved(inquiry.reply(ADMIN_ID, "첫 답변"), 1L);

        assertThatIllegalStateException().isThrownBy(() -> inquiry.editReply(1L, " "));
    }

    @Test
    void 남은_답변이_있으면_답변을_지워도_답변_완료다() {
        Inquiry inquiry = inquiry();
        InquiryReply first = saved(inquiry.reply(ADMIN_ID, "첫 답변"), 1L);
        InquiryReply second = saved(inquiry.reply(ADMIN_ID, "추가 답변"), 2L);

        inquiry.deleteReply(1L, NOW);

        assertThat(first.getDeletedAt()).isEqualTo(NOW);
        assertThat(inquiry.getReplies()).containsExactly(second);
        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.ANSWERED);
    }

    @Test
    void 마지막_답변을_지우면_답변_대기로_돌아간다() {
        Inquiry inquiry = inquiry();
        saved(inquiry.reply(ADMIN_ID, "첫 답변"), 1L);

        inquiry.deleteReply(1L, NOW);

        assertThat(inquiry.getReplies()).isEmpty();
        assertThat(inquiry.isWaiting()).isTrue();
    }

    @Test
    void 없거나_삭제된_답변은_고치거나_지울_수_없다() {
        Inquiry inquiry = inquiry();
        saved(inquiry.reply(ADMIN_ID, "첫 답변"), 1L);
        saved(inquiry.reply(ADMIN_ID, "추가 답변"), 2L);
        inquiry.deleteReply(1L, NOW);

        assertThat(inquiry.hasReply(1L)).isFalse();
        assertThat(inquiry.hasReply(3L)).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> inquiry.editReply(1L, "답변"));
        assertThatIllegalStateException().isThrownBy(() -> inquiry.deleteReply(1L, NOW));
        assertThatIllegalStateException().isThrownBy(() -> inquiry.deleteReply(3L, NOW));
    }

    @Test
    void 답변_목록은_밖에서_바꿀_수_없다() {
        Inquiry inquiry = inquiry();
        inquiry.reply(ADMIN_ID, "답변");

        assertThatThrownBy(() -> inquiry.getReplies().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static Inquiry inquiry() {
        return Inquiry.create(USER_ID, InquiryCategory.OTHER, "제목", "내용");
    }

    /** 저장된 것처럼 ID를 준다 (단위 테스트라 DB가 없다) */
    private static InquiryReply saved(InquiryReply reply, Long id) {
        ReflectionTestUtils.setField(reply, "id", id);
        return reply;
    }
}

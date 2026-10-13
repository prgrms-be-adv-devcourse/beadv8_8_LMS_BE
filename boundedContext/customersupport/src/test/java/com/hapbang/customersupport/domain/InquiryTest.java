package com.hapbang.customersupport.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class InquiryTest {

    private static final Long USER_ID = 1L;

    @Test
    void 문의를_만들면_답변_대기_상태다() {
        Inquiry inquiry = Inquiry.create(USER_ID, InquiryCategory.OTHER, " 모집 글 수정 가능한가요? ", "수정하고 싶어요.\n");

        assertThat(inquiry.getUserId()).isEqualTo(USER_ID);
        assertThat(inquiry.getCategory()).isEqualTo(InquiryCategory.OTHER);
        assertThat(inquiry.getTitle()).isEqualTo("모집 글 수정 가능한가요?");
        assertThat(inquiry.getContent()).isEqualTo("수정하고 싶어요.");
        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.WAITING);
        assertThat(inquiry.getDeletedAt()).isNull();
    }

    @Test
    void 작성자인지_확인한다() {
        Inquiry inquiry = Inquiry.create(USER_ID, InquiryCategory.OTHER, "제목", "내용");

        assertThat(inquiry.isWrittenBy(USER_ID)).isTrue();
        assertThat(inquiry.isWrittenBy(2L)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n"})
    void 제목이나_내용이_비어_있으면_만들_수_없다(String blank) {
        assertThatIllegalStateException()
                .isThrownBy(() -> Inquiry.create(USER_ID, InquiryCategory.OTHER, blank, "내용"));
        assertThatIllegalStateException()
                .isThrownBy(() -> Inquiry.create(USER_ID, InquiryCategory.OTHER, "제목", blank));
    }

    @Test
    void 제목이_최대_길이를_넘으면_만들_수_없다() {
        String tooLong = "가".repeat(Inquiry.TITLE_MAX_LENGTH + 1);

        assertThatIllegalStateException()
                .isThrownBy(() -> Inquiry.create(USER_ID, InquiryCategory.OTHER, tooLong, "내용"));
    }

    @Test
    void 답변_대기인_문의는_삭제하면_삭제_시각이_남는다() {
        Inquiry inquiry = Inquiry.create(USER_ID, InquiryCategory.OTHER, "제목", "내용");
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 16, 0);

        inquiry.delete(now);

        assertThat(inquiry.getDeletedAt()).isEqualTo(now);
        assertThat(inquiry.getContent()).isEqualTo("내용");
    }

    @Test
    void 답변이_달린_문의는_삭제할_수_없다() {
        Inquiry inquiry = Inquiry.create(USER_ID, InquiryCategory.OTHER, "제목", "내용");
        ReflectionTestUtils.setField(inquiry, "status", InquiryStatus.ANSWERED);

        assertThat(inquiry.isWaiting()).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> inquiry.delete(LocalDateTime.now()));
    }

    @Test
    void 이미_삭제된_문의는_다시_삭제할_수_없다() {
        Inquiry inquiry = Inquiry.create(USER_ID, InquiryCategory.OTHER, "제목", "내용");
        inquiry.delete(LocalDateTime.now());

        assertThatIllegalStateException().isThrownBy(() -> inquiry.delete(LocalDateTime.now()));
    }
}

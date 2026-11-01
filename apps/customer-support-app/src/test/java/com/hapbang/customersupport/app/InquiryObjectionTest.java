package com.hapbang.customersupport.app;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.customersupport.app.exception.CustomerSupportErrorCode;
import com.hapbang.customersupport.app.exception.CustomerSupportException;
import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.InquiryCategory;
import com.hapbang.customersupport.domain.InquiryStatus;
import com.hapbang.customersupport.out.InquiryRepository;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 강의 내용 다름 이의 제기 접수·판단·철회. 강의 연동(#278·#279) 전까지 모두 {@code OBJECTION_UNAVAILABLE}로 거절하고
 * 문의를 바꾸지 않는다 (#433).
 */
@IntegrationTest
class InquiryObjectionTest {

    private static final Long USER_ID = 1L;
    private static final Long ADMIN_ID = 9L;
    private static final Long ORDER_ITEM_ID = 100L;

    @Autowired
    private InquiryUseCase inquiryUseCase;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Test
    void 이의_제기는_강의_연동_전까지_접수하지_않는다() {
        assertThatThrownBy(() -> inquiryUseCase.create(USER_ID, InquiryCategory.LECTURE_CONTENT_DISPUTE,
                "강의 내용이 달라요", "커리큘럼과 내용이 달라요.", ORDER_ITEM_ID))
                .isInstanceOf(CustomerSupportException.class)
                .extracting("errorCode").isEqualTo(CustomerSupportErrorCode.OBJECTION_UNAVAILABLE);
        assertThat(inquiryRepository.count()).isZero();
    }

    @Test
    void 이의_제기에_주문_항목이_없으면_실패한다() {
        assertThatThrownBy(() -> inquiryUseCase.create(USER_ID, InquiryCategory.LECTURE_CONTENT_DISPUTE, "제목",
                "내용", null))
                .isInstanceOf(CustomerSupportException.class)
                .extracting("errorCode").isEqualTo(CustomerSupportErrorCode.INQUIRY_TARGET_REQUIRED);
    }

    @Test
    void 다른_유형에_주문_항목을_보내면_실패한다() {
        assertThatThrownBy(() -> inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용", ORDER_ITEM_ID))
                .isInstanceOf(CustomerSupportException.class)
                .extracting("errorCode").isEqualTo(CustomerSupportErrorCode.INQUIRY_TARGET_NOT_ALLOWED);
    }

    @Test
    void 이의_제기_판단은_강의_연동_전까지_저장하지_않는다() {
        Long inquiryId = saveDispute();

        assertThatThrownBy(() -> inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, true, "인정합니다."))
                .isInstanceOf(CustomerSupportException.class)
                .extracting("errorCode").isEqualTo(CustomerSupportErrorCode.OBJECTION_UNAVAILABLE);

        Inquiry inquiry = inquiryRepository.findWithRepliesByIdAndDeletedAtIsNull(inquiryId).orElseThrow();
        assertThat(inquiry.isObjectionResolved()).isFalse();
        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.WAITING);
        assertThat(inquiry.getReplies()).isEmpty();
    }

    @Test
    void 이의_제기_문의가_아니면_판단할_수_없다() {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용", null).getId();

        assertThatThrownBy(() -> inquiryUseCase.resolveObjection(inquiryId, ADMIN_ID, true, "인정합니다."))
                .isInstanceOf(CustomerSupportException.class)
                .extracting("errorCode").isEqualTo(CustomerSupportErrorCode.NOT_OBJECTION_INQUIRY);
    }

    @Test
    void 이의_제기_문의는_강의_연동_전까지_삭제하지_않는다() {
        Long inquiryId = saveDispute();

        assertThatThrownBy(() -> inquiryUseCase.delete(inquiryId, USER_ID))
                .isInstanceOf(CustomerSupportException.class)
                .extracting("errorCode").isEqualTo(CustomerSupportErrorCode.OBJECTION_UNAVAILABLE);
        assertThat(inquiryRepository.findByIdAndDeletedAtIsNull(inquiryId)).isPresent();
    }

    @Test
    void 일반_문의는_삭제할_수_있다() {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용", null).getId();

        inquiryUseCase.delete(inquiryId, USER_ID);

        assertThat(inquiryRepository.findByIdAndDeletedAtIsNull(inquiryId)).isEmpty();
    }

    /** 강의 연동 전 접수된 이의 제기 문의. 유스케이스로는 더 만들 수 없어 저장소에 직접 넣는다 */
    private Long saveDispute() {
        return inquiryRepository.save(Inquiry.createLectureDispute(USER_ID, "강의 내용이 달라요", "커리큘럼과 내용이 달라요.",
                ORDER_ITEM_ID)).getId();
    }
}

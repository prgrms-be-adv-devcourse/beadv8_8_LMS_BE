package com.hapbang.customersupport.fixture;

import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.InquiryStatus;
import com.hapbang.customersupport.out.InquiryRepository;

public final class InquiryFixture {

    private InquiryFixture() {
    }

    /** 저장된 문의를 답변 완료로 바꾼다. 관리자 답변 기능 전이라 상태를 직접 바꾼다 */
    public static void markAnswered(InquiryRepository inquiryRepository, Long inquiryId) {
        Inquiry inquiry = inquiryRepository.findById(inquiryId).orElseThrow();
        ReflectionTestUtils.setField(inquiry, "status", InquiryStatus.ANSWERED);
        inquiryRepository.save(inquiry);
    }
}

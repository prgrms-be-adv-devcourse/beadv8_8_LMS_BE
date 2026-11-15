package com.hapbang.customersupport.fixture;

import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.out.InquiryRepository;

/** 테스트용 강의 내용 다름 이의 제기 문의 (#279) */
public final class LectureObjectionFixture {

    private LectureObjectionFixture() {
    }

    /** 강의 접수 처리 중(FILING)인 이의 제기 문의. 접수 명령 ID까지 정한 상태다 */
    public static Long filing(TransactionTemplate transactionTemplate, InquiryRepository inquiryRepository, Long userId,
                              Long orderItemId) {
        return transactionTemplate.execute(status -> {
            Inquiry inquiry = inquiryRepository.save(Inquiry.createLectureDispute(userId, "강의 내용이 달라요",
                    "커리큘럼과 내용이 달라요.", orderItemId));
            inquiry.assignFileCommand();
            return inquiry.getId();
        });
    }

    /** 강의에 접수된(FILED) 이의 제기 문의. 접수 명령 ID까지 정한 상태다 */
    public static Long filed(TransactionTemplate transactionTemplate, InquiryRepository inquiryRepository, Long userId,
                             Long orderItemId) {
        return transactionTemplate.execute(status -> {
            Inquiry inquiry = inquiryRepository.save(Inquiry.createLectureDispute(userId, "강의 내용이 달라요",
                    "커리큘럼과 내용이 달라요.", orderItemId));
            inquiry.assignFileCommand();
            inquiry.markFiled();
            return inquiry.getId();
        });
    }
}

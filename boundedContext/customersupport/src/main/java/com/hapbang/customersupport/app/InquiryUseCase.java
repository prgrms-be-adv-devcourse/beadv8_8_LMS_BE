package com.hapbang.customersupport.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.customersupport.app.exception.CustomerSupportErrorCode;
import com.hapbang.customersupport.app.exception.CustomerSupportException;
import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.InquiryCategory;
import com.hapbang.customersupport.out.InquiryRepository;

import lombok.RequiredArgsConstructor;

/**
 * 문의 쓰기 기능. 엔티티를 그대로 돌려주고, 응답 DTO는 커밋 뒤 {@link InquiryFacade}가 만든다.
 * 그래서 새 행의 ID·작성 시각(커밋 때 INSERT)이 응답에 담긴다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class InquiryUseCase {

    private final InquiryRepository inquiryRepository;

    public Inquiry create(Long userId, InquiryCategory category, String title, String content) {
        return inquiryRepository.save(Inquiry.create(userId, category, title, content));
    }

    /** 내 문의를 삭제한다(Soft Delete). 답변 대기일 때만 할 수 있다. 다른 회원의 문의나 이미 삭제된 문의는 404 */
    public void delete(Long inquiryId, Long userId) {
        Inquiry inquiry = inquiryRepository.findByIdAndDeletedAtIsNull(inquiryId)
                .filter(found -> found.isWrittenBy(userId))
                .orElseThrow(() -> new CustomerSupportException(CustomerSupportErrorCode.INQUIRY_NOT_FOUND));
        if (!inquiry.isWaiting()) {
            throw new CustomerSupportException(CustomerSupportErrorCode.INQUIRY_NOT_DELETABLE);
        }
        inquiry.delete(LocalDateTime.now());
    }

    /** 관리자 답변. 답변을 달면 문의가 답변 완료가 된다. 회원이 삭제한 문의는 404 */
    public Inquiry reply(Long inquiryId, Long adminUserId, String content) {
        Inquiry inquiry = findWithReplies(inquiryId);
        inquiry.reply(adminUserId, content);
        return inquiry;
    }

    /** 답변 수정. 어느 관리자든 고칠 수 있다. 없거나 삭제된 답변은 404 */
    public Inquiry editReply(Long inquiryId, Long replyId, String content) {
        Inquiry inquiry = findWithReplies(inquiryId);
        requireReply(inquiry, replyId);
        inquiry.editReply(replyId, content);
        return inquiry;
    }

    /** 답변 삭제(Soft Delete). 남은 답변이 없으면 문의가 답변 대기로 돌아간다. 없거나 삭제된 답변은 404 */
    public void deleteReply(Long inquiryId, Long replyId) {
        Inquiry inquiry = findWithReplies(inquiryId);
        requireReply(inquiry, replyId);
        inquiry.deleteReply(replyId, LocalDateTime.now());
    }

    /**
     * 답변까지 함께 불러온다(EntityGraph). 커밋 뒤 트랜잭션 밖에서 응답을 만들 때 답변 목록을 읽을 수 있다.
     */
    private Inquiry findWithReplies(Long inquiryId) {
        return inquiryRepository.findWithRepliesByIdAndDeletedAtIsNull(inquiryId)
                .orElseThrow(() -> new CustomerSupportException(CustomerSupportErrorCode.INQUIRY_NOT_FOUND));
    }

    private static void requireReply(Inquiry inquiry, Long replyId) {
        if (!inquiry.hasReply(replyId)) {
            throw new CustomerSupportException(CustomerSupportErrorCode.INQUIRY_REPLY_NOT_FOUND);
        }
    }
}

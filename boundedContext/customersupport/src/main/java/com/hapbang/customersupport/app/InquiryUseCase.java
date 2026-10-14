package com.hapbang.customersupport.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.customersupport.app.exception.CustomerSupportErrorCode;
import com.hapbang.customersupport.app.exception.CustomerSupportException;
import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.InquiryCategory;
import com.hapbang.customersupport.out.InquiryRepository;
import com.hapbang.shared.lecture.exception.LectureObjectionRefusedException;
import com.hapbang.shared.lecture.port.LectureObjectionPort;

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
    private final LectureObjectionPort lectureObjectionPort;

    /**
     * 문의를 접수한다. 강의 내용 다름 이의 제기면 같은 트랜잭션에서 강의 모듈에 이의 제기를 접수해 지급을 멈춘다.
     * 강의 모듈이 접수를 거절하면 문의도 저장되지 않는다.
     *
     * @param orderItemId 이의 제기할 강의 주문 항목. 이의 제기에만 필수이고 다른 유형에는 보낼 수 없다
     */
    public Inquiry create(Long userId, InquiryCategory category, String title, String content, Long orderItemId) {
        if (!category.requiresTarget()) {
            if (orderItemId != null) {
                throw new CustomerSupportException(CustomerSupportErrorCode.INQUIRY_TARGET_NOT_ALLOWED);
            }
            return inquiryRepository.save(Inquiry.create(userId, category, title, content));
        }
        if (orderItemId == null) {
            throw new CustomerSupportException(CustomerSupportErrorCode.INQUIRY_TARGET_REQUIRED);
        }
        // 강의 모듈이 접수한 문의 ID를 기억하므로 먼저 저장해 ID를 받는다
        Inquiry inquiry = inquiryRepository.save(Inquiry.createLectureDispute(userId, title, content, orderItemId));
        try {
            lectureObjectionPort.fileObjection(orderItemId, userId, inquiry.getId(), inquiry.getContent());
        } catch (LectureObjectionRefusedException exception) {
            throw refused(exception);
        }
        return inquiry;
    }

    /**
     * 관리자가 이의 제기를 인정·기각하고 판단 안내 답변을 단다(답변 완료). 같은 트랜잭션에서 강의 모듈에 반영한다.
     * 인정하면 강의 모듈이 보류 HP를 구매자에게 반환하며, 강의·지갑 쪽이 실패하면 판단도 저장되지 않는다.
     */
    public Inquiry resolveObjection(Long inquiryId, Long adminUserId, boolean accepted, String replyContent) {
        Inquiry inquiry = findWithReplies(inquiryId);
        if (!inquiry.isLectureDispute()) {
            throw new CustomerSupportException(CustomerSupportErrorCode.NOT_OBJECTION_INQUIRY);
        }
        if (inquiry.isObjectionResolved()) {
            throw new CustomerSupportException(CustomerSupportErrorCode.OBJECTION_ALREADY_RESOLVED);
        }
        inquiry.resolveObjection(adminUserId, accepted, replyContent, LocalDateTime.now());
        try {
            lectureObjectionPort.resolveObjection(inquiry.getTargetId(), inquiryId, accepted, adminUserId);
        } catch (LectureObjectionRefusedException exception) {
            throw refused(exception);
        }
        return inquiry;
    }

    /**
     * 내 문의를 삭제한다(Soft Delete). 답변 대기일 때만 할 수 있다. 다른 회원의 문의나 이미 삭제된 문의는 404.
     * 이의 제기 문의는 강의 모듈의 철회 연동(#203) 전까지 삭제할 수 없다.
     */
    public void delete(Long inquiryId, Long userId) {
        Inquiry inquiry = inquiryRepository.findByIdAndDeletedAtIsNull(inquiryId)
                .filter(found -> found.isWrittenBy(userId))
                .orElseThrow(() -> new CustomerSupportException(CustomerSupportErrorCode.INQUIRY_NOT_FOUND));
        if (inquiry.isLectureDispute()) {
            throw new CustomerSupportException(CustomerSupportErrorCode.OBJECTION_INQUIRY_NOT_DELETABLE);
        }
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

    /** 강의 모듈의 거절을 사유별 고객센터 예외로 바꾼다. 트랜잭션은 이미 롤백 전용이라 문의 변경도 함께 롤백된다 */
    private static CustomerSupportException refused(LectureObjectionRefusedException exception) {
        return new CustomerSupportException(CustomerSupportErrorCode.of(exception.getReason()), exception.getMessage());
    }

    private static void requireReply(Inquiry inquiry, Long replyId) {
        if (!inquiry.hasReply(replyId)) {
            throw new CustomerSupportException(CustomerSupportErrorCode.INQUIRY_REPLY_NOT_FOUND);
        }
    }
}

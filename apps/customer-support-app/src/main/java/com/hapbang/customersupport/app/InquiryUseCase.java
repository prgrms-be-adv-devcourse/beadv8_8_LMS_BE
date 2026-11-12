package com.hapbang.customersupport.app;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.customersupport.app.exception.CustomerSupportErrorCode;
import com.hapbang.customersupport.app.exception.CustomerSupportException;
import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.InquiryCategory;
import com.hapbang.customersupport.domain.InquiryReply;
import com.hapbang.customersupport.domain.ObjectionLinkStatus;
import com.hapbang.customersupport.out.InquiryRepository;
import com.hapbang.shared.customersupport.event.InquiryAnsweredEvent;
import com.hapbang.shared.customersupport.event.LectureObjectionResolutionRequestedEvent;
import com.hapbang.shared.customersupport.event.LectureObjectionWithdrawalRequestedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 문의 쓰기 기능. 엔티티를 그대로 돌려주고, 응답 DTO는 커밋 뒤 {@link InquiryFacade}가 만든다.
 * 그래서 새 행의 ID·작성 시각(커밋 때 INSERT)이 응답에 담긴다.
 * <ul>
 *     <li>문의를 바꾸는 쓰기는 문의 행을 잠근 뒤 바꾼다({@link InquiryRepository#findForUpdate}). 강의 결과 반영
 *     ({@link LectureObjectionResultUseCase})과 겹쳐도 서로의 변경을 덮어쓰지 않는다.</li>
 *     <li>강의 내용 다름 이의 제기는 강의와 연동한다(#279, D60·D71). 접수는 문의를 커밋한 뒤 {@link LectureObjectionFiler}가
 *     강의 내부 HTTP로 하고, 판단·철회는 연동 상태를 선점하고 같은 트랜잭션에 고객센터 이벤트를 기록한다.
 *     강의 HTTP는 이 트랜잭션 안에서 부르지 않는다. 결과는 강의 이벤트로 받아 기록한다.</li>
 * </ul>
 */
@Service
@Transactional
@RequiredArgsConstructor
public class InquiryUseCase {

    private final InquiryRepository inquiryRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 문의를 저장한다. 강의 내용 다름 이의 제기는 접수 처리 중({@code FILING})과 접수 명령 ID로 저장하고,
     * 강의 접수는 커밋 뒤에 한다({@link LectureObjectionFiler#fileNow}).
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
        // 접수 명령 ID에 문의 ID가 들어가므로 먼저 저장해 ID를 받는다
        Inquiry inquiry = inquiryRepository.save(Inquiry.createLectureDispute(userId, title, content, orderItemId));
        inquiry.assignFileCommand();
        return inquiry;
    }

    /**
     * 관리자가 이의 제기를 인정·기각한다 (D60 (2)). 접수됨이면 판단 처리 중으로 선점하고 판단 요청 이벤트를 같은 트랜잭션에 기록한다.
     * 판단과 답변은 강의가 반영한 뒤 기록된다. 반환한 문의의 연동 상태로 응답을 정한다(처리 중 202, 끝났으면 200).
     * <ul>
     *     <li>같은 판단(인정 여부·답변·관리자)이 다시 오면 처리 중이면 그대로, 끝났으면 그 결과를 돌려준다.</li>
     *     <li>다른 내용이나 철회 처리 중이면 {@code OBJECTION_IN_PROGRESS}, 이미 다른 판단으로 끝났으면 {@code OBJECTION_ALREADY_RESOLVED}.</li>
     *     <li>강의가 거절한 문의는 운영 복구(#267)를 기다리므로 {@code OBJECTION_RECOVERY_REQUIRED}.</li>
     * </ul>
     */
    public Inquiry resolveObjection(Long inquiryId, Long adminUserId, boolean accepted, String replyContent) {
        Inquiry inquiry = lockActive(inquiryId);
        if (!inquiry.isLectureDispute()) {
            throw new CustomerSupportException(CustomerSupportErrorCode.NOT_OBJECTION_INQUIRY);
        }
        String commandHash = ObjectionCommandHash.resolution(accepted, replyContent, adminUserId);
        switch (linkStatusOf(inquiry)) {
            case FILED -> {
                inquiry.requestResolution(adminUserId, replyContent, commandHash);
                eventPublisher.publishEvent(new LectureObjectionResolutionRequestedEvent(inquiry.getObjectionCommandId(),
                        inquiry.getId(), inquiry.getTargetId(), accepted, adminUserId));
            }
            case RESOLUTION_PENDING -> requireSameCommand(inquiry, commandHash, CustomerSupportErrorCode.OBJECTION_IN_PROGRESS);
            case RESOLVED -> requireSameCommand(inquiry, commandHash, CustomerSupportErrorCode.OBJECTION_ALREADY_RESOLVED);
            case FILING, WITHDRAWAL_PENDING -> throw new CustomerSupportException(
                    CustomerSupportErrorCode.OBJECTION_IN_PROGRESS);
            case RESOLUTION_FAILED, WITHDRAWAL_FAILED -> throw new CustomerSupportException(
                    CustomerSupportErrorCode.OBJECTION_RECOVERY_REQUIRED);
            case FILE_REJECTED, WITHDRAWN -> throw new CustomerSupportException(
                    CustomerSupportErrorCode.OBJECTION_NOT_FILED);
        }
        return inquiry;
    }

    /**
     * 내 문의를 삭제한다(Soft Delete). 답변 대기일 때만 할 수 있다. 다른 회원의 문의나 삭제된 문의는 404.
     * 강의 내용 다름 이의 제기 문의는 이의 제기 철회다 (D52, D60 (2)). 접수됨이면 철회 처리 중으로 선점하고 철회 요청 이벤트를
     * 기록한다. 문의는 강의가 철회를 반영한 뒤 삭제된다. 반환한 문의가 철회 처리 중이면 202, 그 밖에는 204로 응답한다.
     * <ul>
     *     <li>철회가 끝나 삭제된 문의에 다시 삭제를 보내면 그대로 끝난 것으로 본다(204).</li>
     *     <li>강의가 접수를 거절한 문의(접수 불가)는 강의에 알릴 것이 없어 바로 삭제한다.</li>
     *     <li>접수·판단 처리 중이면 {@code OBJECTION_IN_PROGRESS}, 강의가 거절한 문의는 {@code OBJECTION_RECOVERY_REQUIRED}.</li>
     * </ul>
     */
    public Inquiry delete(Long inquiryId, Long userId) {
        Inquiry inquiry = inquiryRepository.findForUpdate(inquiryId)
                .filter(found -> found.isWrittenBy(userId))
                .orElseThrow(() -> new CustomerSupportException(CustomerSupportErrorCode.INQUIRY_NOT_FOUND));
        // 커밋 뒤 Facade가 응답을 만들 때 답변 목록을 읽는다
        inquiry.getReplies();
        if (inquiry.getDeletedAt() != null) {
            if (inquiry.getObjectionLinkStatus() == ObjectionLinkStatus.WITHDRAWN) {
                return inquiry;
            }
            throw new CustomerSupportException(CustomerSupportErrorCode.INQUIRY_NOT_FOUND);
        }
        if (!inquiry.isWaiting()) {
            throw new CustomerSupportException(CustomerSupportErrorCode.INQUIRY_NOT_DELETABLE);
        }
        if (!inquiry.isLectureDispute()) {
            inquiry.delete(LocalDateTime.now());
            return inquiry;
        }
        switch (linkStatusOf(inquiry)) {
            case FILED -> {
                inquiry.requestWithdrawal(ObjectionCommandHash.withdrawal(userId));
                eventPublisher.publishEvent(new LectureObjectionWithdrawalRequestedEvent(inquiry.getObjectionCommandId(),
                        inquiry.getId(), inquiry.getTargetId(), userId));
            }
            // 철회할 수 있는 사람은 작성자뿐이라 같은 요청이다
            case WITHDRAWAL_PENDING -> {
            }
            case FILE_REJECTED -> inquiry.delete(LocalDateTime.now());
            case FILING, RESOLUTION_PENDING -> throw new CustomerSupportException(
                    CustomerSupportErrorCode.OBJECTION_IN_PROGRESS);
            case RESOLUTION_FAILED, WITHDRAWAL_FAILED -> throw new CustomerSupportException(
                    CustomerSupportErrorCode.OBJECTION_RECOVERY_REQUIRED);
            // 판단을 마친 문의는 답변 완료라 위에서 걸리고, 철회된 문의는 삭제되어 있다
            case RESOLVED, WITHDRAWN -> throw new CustomerSupportException(
                    CustomerSupportErrorCode.INQUIRY_NOT_DELETABLE);
        }
        return inquiry;
    }

    /**
     * 관리자 답변. 답변을 달면 문의가 답변 완료가 된다. 회원이 삭제한 문의는 404.
     * 철회 처리 중인 이의 제기 문의에는 달 수 없다(철회가 끝나면 문의가 삭제되므로).
     * 답변 도착 이벤트를 같은 트랜잭션에 기록한다. 이 이벤트는 관리자가 직접 다는 답변에서만 낸다.
     * 이의 제기 판단 안내 답변({@link Inquiry#applyResolved})은 판단 알림이 강의 결과 이벤트로 가므로 내지 않는다.
     */
    public Inquiry reply(Long inquiryId, Long adminUserId, String content) {
        Inquiry inquiry = lockActive(inquiryId);
        if (inquiry.getObjectionLinkStatus() == ObjectionLinkStatus.WITHDRAWAL_PENDING) {
            throw new CustomerSupportException(CustomerSupportErrorCode.OBJECTION_IN_PROGRESS);
        }
        InquiryReply reply = inquiry.reply(adminUserId, content);
        // 답변 ID와 작성 시각은 INSERT 때 생기므로 이벤트를 만들기 전에 저장한다
        inquiryRepository.flush();
        eventPublisher.publishEvent(new InquiryAnsweredEvent(inquiry.getId(), reply.getId(), inquiry.getUserId(),
                inquiry.getTitle(), inquiry.getCategory().name(),
                reply.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant()));
        return inquiry;
    }

    /** 답변 수정. 어느 관리자든 고칠 수 있다. 없거나 삭제된 답변은 404. 수정은 알리지 않으므로 이벤트를 내지 않는다 */
    public Inquiry editReply(Long inquiryId, Long replyId, String content) {
        Inquiry inquiry = lockActive(inquiryId);
        requireReply(inquiry, replyId);
        inquiry.editReply(replyId, content);
        return inquiry;
    }

    /** 답변 삭제(Soft Delete). 남은 답변이 없으면 문의가 답변 대기로 돌아간다. 없거나 삭제된 답변은 404 */
    public void deleteReply(Long inquiryId, Long replyId) {
        Inquiry inquiry = lockActive(inquiryId);
        requireReply(inquiry, replyId);
        inquiry.deleteReply(replyId, LocalDateTime.now());
    }

    /**
     * 삭제되지 않은 문의를 잠가 읽고 답변도 지금 읽어 둔다. 커밋 뒤 트랜잭션 밖(Facade)에서 응답을 만들 때 답변 목록을 읽을 수 있다.
     */
    private Inquiry lockActive(Long inquiryId) {
        Inquiry inquiry = inquiryRepository.findForUpdate(inquiryId)
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> new CustomerSupportException(CustomerSupportErrorCode.INQUIRY_NOT_FOUND));
        inquiry.getReplies();
        return inquiry;
    }

    /** #433 이전에 만든 연동 상태 없는 이의 제기 문의는 운영 DB에 없다. 있으면 강의에 접수되지 않은 문의로 본다 */
    private static ObjectionLinkStatus linkStatusOf(Inquiry inquiry) {
        return inquiry.getObjectionLinkStatus() == null ? ObjectionLinkStatus.FILE_REJECTED
                : inquiry.getObjectionLinkStatus();
    }

    private static void requireSameCommand(Inquiry inquiry, String commandHash, CustomerSupportErrorCode otherwise) {
        if (!inquiry.hasCommandHash(commandHash)) {
            throw new CustomerSupportException(otherwise);
        }
    }

    private static void requireReply(Inquiry inquiry, Long replyId) {
        if (!inquiry.hasReply(replyId)) {
            throw new CustomerSupportException(CustomerSupportErrorCode.INQUIRY_REPLY_NOT_FOUND);
        }
    }
}

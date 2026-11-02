package com.hapbang.customersupport.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import com.hapbang.customersupport.app.dto.InquiryResponse;
import com.hapbang.customersupport.app.dto.InquirySort;
import com.hapbang.customersupport.app.dto.InquirySummaryResponse;
import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.InquiryCategory;
import com.hapbang.customersupport.domain.InquiryStatus;

import lombok.RequiredArgsConstructor;

/**
 * Controller가 문의 기능을 쓰는 진입점. Controller는 UseCase·Supporter를 직접 쓰지 않고 이 Facade를 통한다.
 * 역할 검사({@code @PreAuthorize})는 여기서 한다(팀 규칙). 트랜잭션은 열지 않는다.
 * 쓰기는 UseCase가 커밋한 뒤 여기서 응답 DTO로 바꾼다. 그래서 새 행의 ID·작성 시각이 응답에 담긴다.
 * 강의 내용 다름 이의 제기 접수는 문의를 커밋한 뒤 강의 내부 HTTP로 한 번 시도한다({@link LectureObjectionFiler}, #279).
 */
@Component
@RequiredArgsConstructor
public class InquiryFacade {

    private final InquiryUseCase inquiryUseCase;
    private final InquirySupporter inquirySupporter;
    private final LectureObjectionFiler lectureObjectionFiler;

    @PreAuthorize(CustomerSupportRoles.MEMBER)
    public InquiryResponse create(Long userId, InquiryCategory category, String title, String content,
                                  Long orderItemId) {
        Inquiry inquiry = inquiryUseCase.create(userId, category, title, content, orderItemId);
        if (inquiry.isLectureDispute()) {
            // 문의 커밋 뒤 트랜잭션 밖에서 강의에 접수한다. 거절이면 예외, 결과를 모르면 접수 처리 중(202)이다
            inquiry = lectureObjectionFiler.fileNow(inquiry);
        }
        return InquiryResponse.of(inquiry);
    }

    @PreAuthorize(CustomerSupportRoles.MEMBER)
    public Page<InquirySummaryResponse> getMyInquiries(Long userId, String keyword, InquirySort sort,
                                                       Pageable pageable) {
        return inquirySupporter.getMyInquiries(userId, keyword, sort, pageable);
    }

    @PreAuthorize(CustomerSupportRoles.MEMBER)
    public InquiryResponse getMyInquiry(Long inquiryId, Long userId) {
        return inquirySupporter.getMyInquiry(inquiryId, userId);
    }

    /** 삭제한(철회 요청한) 문의. 이의 제기 철회 처리 중이면 그 상태를 응답한다 */
    @PreAuthorize(CustomerSupportRoles.MEMBER)
    public InquiryResponse delete(Long inquiryId, Long userId) {
        return InquiryResponse.of(inquiryUseCase.delete(inquiryId, userId));
    }

    @PreAuthorize(CustomerSupportRoles.ADMIN)
    public Page<InquirySummaryResponse> getInquiries(InquiryStatus status, InquirySort sort, Pageable pageable) {
        return inquirySupporter.getInquiries(status, sort, pageable);
    }

    @PreAuthorize(CustomerSupportRoles.ADMIN)
    public InquiryResponse getInquiry(Long inquiryId) {
        return inquirySupporter.getInquiry(inquiryId);
    }

    @PreAuthorize(CustomerSupportRoles.ADMIN)
    public InquiryResponse reply(Long inquiryId, Long adminUserId, String content) {
        return InquiryResponse.ofAdmin(inquiryUseCase.reply(inquiryId, adminUserId, content));
    }

    @PreAuthorize(CustomerSupportRoles.ADMIN)
    public InquiryResponse editReply(Long inquiryId, Long replyId, String content) {
        return InquiryResponse.ofAdmin(inquiryUseCase.editReply(inquiryId, replyId, content));
    }

    @PreAuthorize(CustomerSupportRoles.ADMIN)
    public InquiryResponse resolveObjection(Long inquiryId, Long adminUserId, boolean accepted, String replyContent) {
        return InquiryResponse.ofAdmin(inquiryUseCase.resolveObjection(inquiryId, adminUserId, accepted, replyContent));
    }

    @PreAuthorize(CustomerSupportRoles.ADMIN)
    public void deleteReply(Long inquiryId, Long replyId) {
        inquiryUseCase.deleteReply(inquiryId, replyId);
    }
}

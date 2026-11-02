package com.hapbang.customersupport.app.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.customersupport.app.exception.CustomerSupportErrorCode;
import com.hapbang.customersupport.domain.HpReturnStatus;
import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.InquiryCategory;
import com.hapbang.customersupport.domain.InquiryStatus;
import com.hapbang.customersupport.domain.InquiryTargetType;
import com.hapbang.customersupport.domain.ObjectionLinkStatus;
import com.hapbang.customersupport.domain.ObjectionResult;

/**
 * 문의 상세 (답변 포함, 오래된 답변부터).
 *
 * @param targetType           문의 대상 종류. 강의 내용 다름 이의 제기만 있고 나머지는 null
 * @param targetId             문의 대상 ID (강의 주문 항목 ID)
 * @param objectionResult      이의 제기 판단. 판단 전이거나 이의 제기가 아니면 null
 * @param objectionResolvedAt  이의 제기 판단 시각
 * @param objectionLinkStatus  이의 제기의 강의 연동 상태 (#279). 이의 제기가 아니면 null
 * @param hpReturnStatus       인정한 이의 제기의 HP 반환 상태. 인정 전이거나 기각이면 null
 * @param objectionNotice      사용자에게 보여 줄 안내. 접수 불가면 "접수 불가 · 사유", 강의가 판단·철회를 거절했으면
 *                             "처리 지연 · 확인 중"이고 그 밖에는 null
 * @param objectionFailureCode 강의 거절 사유 코드. 관리자 응답에만 있다
 * @param objectionCommandId   지금(마지막으로) 강의에 보낸 명령 ID. 관리자 응답에만 있다
 */
public record InquiryResponse(
        Long inquiryId,
        Long userId,
        InquiryCategory category,
        String title,
        String content,
        InquiryStatus status,
        LocalDateTime createdAt,
        List<InquiryReplyResponse> replies,
        InquiryTargetType targetType,
        Long targetId,
        ObjectionResult objectionResult,
        LocalDateTime objectionResolvedAt,
        ObjectionLinkStatus objectionLinkStatus,
        HpReturnStatus hpReturnStatus,
        String objectionNotice,
        String objectionFailureCode,
        String objectionCommandId
) {

    static final String DELAYED_NOTICE = "처리 지연 · 확인 중";

    /** 회원용. 강의 거절 사유 코드와 명령 ID는 싣지 않는다 (D60 (4)). 답변 목록을 읽으므로 답변이 로딩된 문의로 부른다 */
    public static InquiryResponse of(Inquiry inquiry) {
        return of(inquiry, false);
    }

    /** 관리자용. 강의 거절 사유 코드와 명령 ID를 함께 싣는다 */
    public static InquiryResponse ofAdmin(Inquiry inquiry) {
        return of(inquiry, true);
    }

    private static InquiryResponse of(Inquiry inquiry, boolean admin) {
        return new InquiryResponse(inquiry.getId(), inquiry.getUserId(), inquiry.getCategory(), inquiry.getTitle(),
                inquiry.getContent(), inquiry.getStatus(), inquiry.getCreatedAt(),
                inquiry.getReplies().stream().map(InquiryReplyResponse::from).toList(), inquiry.getTargetType(),
                inquiry.getTargetId(), inquiry.getObjectionResult(), inquiry.getObjectionResolvedAt(),
                inquiry.getObjectionLinkStatus(), inquiry.getHpReturnStatus(), noticeOf(inquiry),
                admin && inquiry.getObjectionFailureCode() != null ? inquiry.getObjectionFailureCode().name() : null,
                admin ? inquiry.getObjectionCommandId() : null);
    }

    private static String noticeOf(Inquiry inquiry) {
        ObjectionLinkStatus linkStatus = inquiry.getObjectionLinkStatus();
        if (linkStatus == ObjectionLinkStatus.FILE_REJECTED && inquiry.getObjectionFailureCode() != null) {
            return "접수 불가 · " + CustomerSupportErrorCode.of(inquiry.getObjectionFailureCode()).getMessage();
        }
        return linkStatus != null && linkStatus.isFailed() ? DELAYED_NOTICE : null;
    }
}

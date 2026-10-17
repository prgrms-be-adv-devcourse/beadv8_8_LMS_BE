package com.hapbang.customersupport.app.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.InquiryCategory;
import com.hapbang.customersupport.domain.InquiryStatus;
import com.hapbang.customersupport.domain.InquiryTargetType;
import com.hapbang.customersupport.domain.ObjectionResult;

/**
 * 문의 상세 (답변 포함, 오래된 답변부터).
 *
 * @param targetType          문의 대상 종류. 강의 내용 다름 이의 제기만 있고 나머지는 null
 * @param targetId            문의 대상 ID (강의 주문 항목 ID)
 * @param objectionResult     이의 제기 판단. 판단 전이거나 이의 제기가 아니면 null
 * @param objectionResolvedAt 이의 제기 판단 시각
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
        LocalDateTime objectionResolvedAt
) {

    /** 답변 목록({@code inquiry.getReplies()})을 읽으므로 답변이 로딩된 문의나 트랜잭션 안에서 부른다 */
    public static InquiryResponse of(Inquiry inquiry) {
        return new InquiryResponse(inquiry.getId(), inquiry.getUserId(), inquiry.getCategory(), inquiry.getTitle(),
                inquiry.getContent(), inquiry.getStatus(), inquiry.getCreatedAt(),
                inquiry.getReplies().stream().map(InquiryReplyResponse::from).toList(), inquiry.getTargetType(),
                inquiry.getTargetId(), inquiry.getObjectionResult(), inquiry.getObjectionResolvedAt());
    }
}

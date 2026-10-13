package com.hapbang.customersupport.app.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.InquiryCategory;
import com.hapbang.customersupport.domain.InquiryStatus;

/** 문의 상세 (답변 포함, 오래된 답변부터) */
public record InquiryResponse(
        Long inquiryId,
        Long userId,
        InquiryCategory category,
        String title,
        String content,
        InquiryStatus status,
        LocalDateTime createdAt,
        List<InquiryReplyResponse> replies
) {

    /** 답변 목록({@code inquiry.getReplies()})을 읽으므로 답변이 로딩된 문의나 트랜잭션 안에서 부른다 */
    public static InquiryResponse of(Inquiry inquiry) {
        return new InquiryResponse(inquiry.getId(), inquiry.getUserId(), inquiry.getCategory(), inquiry.getTitle(),
                inquiry.getContent(), inquiry.getStatus(), inquiry.getCreatedAt(),
                inquiry.getReplies().stream().map(InquiryReplyResponse::from).toList());
    }
}

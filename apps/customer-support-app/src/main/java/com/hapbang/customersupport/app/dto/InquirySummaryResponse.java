package com.hapbang.customersupport.app.dto;

import java.time.LocalDateTime;

import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.InquiryCategory;
import com.hapbang.customersupport.domain.InquiryStatus;

/** 문의 목록의 한 줄. 본문은 상세에서 본다 */
public record InquirySummaryResponse(
        Long inquiryId,
        Long userId,
        InquiryCategory category,
        String title,
        InquiryStatus status,
        LocalDateTime createdAt
) {

    public static InquirySummaryResponse from(Inquiry inquiry) {
        return new InquirySummaryResponse(inquiry.getId(), inquiry.getUserId(), inquiry.getCategory(),
                inquiry.getTitle(), inquiry.getStatus(), inquiry.getCreatedAt());
    }
}

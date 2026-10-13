package com.hapbang.customersupport.app.dto;

import java.time.LocalDateTime;

import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.InquiryCategory;
import com.hapbang.customersupport.domain.InquiryStatus;

/** 문의 상세 */
public record InquiryResponse(
        Long inquiryId,
        Long userId,
        InquiryCategory category,
        String title,
        String content,
        InquiryStatus status,
        LocalDateTime createdAt
) {

    public static InquiryResponse of(Inquiry inquiry) {
        return new InquiryResponse(inquiry.getId(), inquiry.getUserId(), inquiry.getCategory(), inquiry.getTitle(),
                inquiry.getContent(), inquiry.getStatus(), inquiry.getCreatedAt());
    }
}

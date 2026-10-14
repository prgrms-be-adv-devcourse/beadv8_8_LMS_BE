package com.hapbang.customersupport.in.dto;

import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.InquiryCategory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * @param orderItemId 이의 제기할 강의 주문 항목 ID. 강의 내용 다름 이의 제기(LECTURE_CONTENT_DISPUTE)에만 필수이고 다른 유형에는 보내지 않는다
 */
public record CreateInquiryRequest(
        @NotNull(message = "문의 유형은 필수입니다.")
        InquiryCategory category,

        @NotBlank(message = "문의 제목은 필수입니다.")
        @Size(max = Inquiry.TITLE_MAX_LENGTH, message = "문의 제목은 " + Inquiry.TITLE_MAX_LENGTH + "자 이하여야 합니다.")
        String title,

        @NotBlank(message = "문의 내용은 필수입니다.")
        String content,
        @Positive(message = "주문 항목 ID는 양수여야 합니다.")
        Long orderItemId
) {
}

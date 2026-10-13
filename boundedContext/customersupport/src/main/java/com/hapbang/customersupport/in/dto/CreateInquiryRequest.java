package com.hapbang.customersupport.in.dto;

import com.hapbang.customersupport.domain.Inquiry;
import com.hapbang.customersupport.domain.InquiryCategory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateInquiryRequest(
        @NotNull(message = "문의 유형은 필수입니다.")
        InquiryCategory category,

        @NotBlank(message = "문의 제목은 필수입니다.")
        @Size(max = Inquiry.TITLE_MAX_LENGTH, message = "문의 제목은 " + Inquiry.TITLE_MAX_LENGTH + "자 이하여야 합니다.")
        String title,

        @NotBlank(message = "문의 내용은 필수입니다.")
        String content
) {
}

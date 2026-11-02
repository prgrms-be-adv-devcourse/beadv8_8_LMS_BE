package com.hapbang.customersupport.in.dto;

import jakarta.validation.constraints.NotBlank;

/** 답변 등록·수정 요청 */
public record InquiryReplyRequest(
        @NotBlank(message = "답변 내용은 필수입니다.")
        String content
) {
}

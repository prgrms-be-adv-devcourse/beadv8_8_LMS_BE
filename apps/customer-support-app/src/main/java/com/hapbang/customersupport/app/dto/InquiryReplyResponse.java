package com.hapbang.customersupport.app.dto;

import java.time.LocalDateTime;

import com.hapbang.customersupport.domain.InquiryReply;

/** 관리자 답변. 답변한 관리자 ID는 회원에게 보이지 않는다 */
public record InquiryReplyResponse(
        Long replyId,
        String content,
        LocalDateTime createdAt
) {

    public static InquiryReplyResponse from(InquiryReply reply) {
        return new InquiryReplyResponse(reply.getId(), reply.getContent(), reply.getCreatedAt());
    }
}

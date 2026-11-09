package com.hapbang.auxiliary.chatbot.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** FAQ 대화 요청. 대화 기억이 없는 FAQ 전용 대화라 질문 하나만 받는다 */
public record FaqChatRequest(
        @Schema(description = "질문(1~500자)", example = "출연을 취소하면 HP는 돌려받나요?")
        @NotBlank @Size(max = 500) String message) {
}

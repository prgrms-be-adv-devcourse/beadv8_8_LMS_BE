package com.hapbang.auxiliary.chatbot.in.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.hapbang.auxiliary.chatbot.app.ChatHistory;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 자유 질문 요청 (#720). 서버는 대화를 저장하지 않는다. FE가 화면에 그린 대화를 오래된 것부터 {@code history}로 보낸다.
 * Tool 결과·추천 카드는 보내지 않는다(받는 필드가 없다).
 */
public record ChatMessageRequest(
        @Schema(description = "질문(1~500자)", example = "데이터 분석 강의 추천해 줘")
        @NotBlank @Size(max = 500) String message,
        @Schema(description = "이전 대화(오래된 것부터). role은 USER·ASSISTANT만, content는 USER 1~500자·ASSISTANT 1~2,000자, "
                + "최근 12개만 쓰고(넘으면 앞에서 버림) 쓰는 대화의 글자 수 합은 8,000자까지. 어기면 400", nullable = true)
        @Nullable List<ChatHistory.Entry> history) {

    public List<ChatHistory.Entry> historyOrEmpty() {
        return history == null ? List.of() : history;
    }
}

package com.hapbang.auxiliary.chatbot.in.dto;

import org.jspecify.annotations.Nullable;

import com.hapbang.auxiliary.chatbot.app.ChatHistory;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 자유 질문 요청의 이전 대화 하나 (#720, #835). 검사는 app의 {@link ChatHistory}가 한다. 빠진 값은 그대로 넘겨 거기서 400이 된다.
 */
public record ChatHistoryEntryReqDto(
        @Schema(description = "USER(사용자 메시지), ASSISTANT(챗봇 답변). 그 밖의 값은 400") @Nullable Role role,
        @Schema(description = "내용. USER 1~500자, ASSISTANT 1~2,000자(서버 설정 기본값)") @Nullable String content) {

    /**
     * 대화의 역할. 모르는 값의 오류 메시지에 enum 이름({@code Role})과 값이 나가므로 이름과 값을 바꾸지 않는다.
     */
    @Getter
    @RequiredArgsConstructor
    public enum Role {
        USER("사용자 메시지"),
        ASSISTANT("챗봇 답변");

        private final String description;
    }

    /** app의 대화로 바꾼다. 항목이 null이면 null(검사에서 400) */
    static ChatHistory.@Nullable Entry toEntry(@Nullable ChatHistoryEntryReqDto entry) {
        if (entry == null) {
            return null;
        }
        return new ChatHistory.Entry(toRole(entry.role()), entry.content());
    }

    private static ChatHistory.@Nullable Role toRole(@Nullable Role role) {
        if (role == null) {
            return null;
        }
        return switch (role) {
            case USER -> ChatHistory.Role.USER;
            case ASSISTANT -> ChatHistory.Role.ASSISTANT;
        };
    }
}

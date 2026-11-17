package com.hapbang.auxiliary.chatbot.in.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 회원 이벤트 다시 처리 요청 (#976) */
public record MemberEventRetryReqDto(
        @Schema(description = "다시 처리할 실패 기록 ID(최대 100개). 비우거나 빼면 처리 대기 기록 전체(오래된 순, 한 번에 최대 100건. 서버 설정 기본값)")
        @Size(max = 100) @Nullable List<@NotNull Long> ids
) {
}

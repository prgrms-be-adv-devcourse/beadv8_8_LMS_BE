package com.hapbang.auxiliary.chatbot.in.dto;

import org.jspecify.annotations.Nullable;

import com.hapbang.auxiliary.chatbot.app.RecommendInterest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

/** 챗봇 추천 버튼 요청 (#720). 본문을 비우거나 interest를 빼면 활동 기록만으로 찾는다 */
public record RecommendButtonReqDto(
        @Schema(description = "관심사(앞뒤 공백을 뺀 1~" + RecommendInterest.MAX_LENGTH + "자, 저장하지 않음). 없으면 활동 기록만으로", example = "데이터 분석 입문",
                nullable = true)
        @Pattern(regexp = RecommendInterest.PATTERN,
                message = RecommendInterest.MESSAGE)
        @Nullable String interest) {

    /** 본문이 없을 때 */
    public static @Nullable String interestOf(@Nullable RecommendButtonReqDto request) {
        return request == null ? null : request.interest();
    }
}

package com.hapbang.auxiliary.chatbot.app.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 인플루언서 추천 결과 (#719). 후보와 순서는 코드가 정한다(같은 상태면 같은 결과).
 *
 * @param fallback LLM 설명 경로에서 LLM 답을 쓰지 못하고 이유 코드 문장으로 답했는지. 위젯 API·Tool은 늘 false
 * @param notice   추천하지 못한 까닭을 사용자에게 보여 줄 정해진 문구. 추천했으면 없다
 */
@Schema(description = "인플루언서 추천 결과")
public record InfluencerRecommendResDto(
        @Schema(description = "추천 인플루언서. 설정한 수(hapbang.chatbot.recommend.influencer.size, 기본 5명)이고, "
                + "추천할 수 있는 인플루언서가 모자라면 그보다 적다")
        List<InfluencerItemResDto> items,
        @Schema(description = "내 프로필·함께 출연한 상대가 없어 인기순으로만 골랐다. 대화 화면이 관심사를 물어볼 수 있다")
        boolean needsInterest,
        @Schema(description = "LLM 설명 경로에서 LLM 답을 쓰지 못해 정해진 문장으로 답했다(실패·시간 초과·쓸 만한 답 없음)")
        boolean fallback,
        @Schema(description = "추천하지 못했을 때 화면에 띄울 정해진 문구(예: 가입 직후 회원 정보를 아직 받지 못함). 추천했으면 빠진다",
                nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Nullable String notice
) {
}

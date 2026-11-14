package com.hapbang.auxiliary.chatbot.app.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.hapbang.auxiliary.chatbot.domain.RecommendBasis;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 자유 질문에서 추천 Tool이 만든 카드 (#720). FE 카드는 이 목록만 그리고, LLM 글은 설명으로만 쓴다
 * (스트림 글에서는 후보 ID를 검사할 수 없다). 카드는 코드가 만든 것이라 LLM이 실패해도 맞다.
 *
 * @param lectures    강의 추천 Tool이 고른 카드. Tool을 부르지 않았으면 없다
 * @param influencers 인플루언서 추천 Tool이 고른 카드(회원 ID·함께 출연한 이력 포함, LLM에는 가지 않는다). 부르지 않았으면 없다
 */
@Schema(description = "추천 카드. 이번 요청에서 추천 Tool을 부른 종류만 온다")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCardsResDto(
        @Schema(description = "강의 카드. 강의 추천 Tool을 부르지 않았으면 없다", nullable = true) @Nullable LectureCards lectures,
        @Schema(description = "인플루언서 카드. 인플루언서 추천 Tool을 부르지 않았으면 없다", nullable = true)
        @Nullable InfluencerCards influencers) {

    @JsonIgnore
    public boolean isEmpty() {
        return lectures == null && influencers == null;
    }

    /**
     * 강의 카드. 근거 코드는 위젯 API와 같다. 이유 문장(reasonText)은 기준 강의 제목 없이 쓴다(대화 화면에 그대로 나가고 LLM 글과
     * 같은 근거를 보이게. 위젯 API의 문장에는 제목이 들어갈 수 있다).
     */
    public record LectureCards(RecommendBasis basis, List<LectureRecommendResDto.Item> items) {
    }

    /** 인플루언서 카드. 근거 코드는 위젯 API와 같다 */
    public record InfluencerCards(RecommendBasis basis, List<InfluencerItemResDto> items) {
    }
}

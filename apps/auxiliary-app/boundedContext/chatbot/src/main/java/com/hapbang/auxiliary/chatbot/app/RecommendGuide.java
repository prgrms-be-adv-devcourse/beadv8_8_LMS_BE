package com.hapbang.auxiliary.chatbot.app;

import org.jspecify.annotations.Nullable;

import com.hapbang.auxiliary.chatbot.domain.RecommendBasis;

/**
 * 추천 Tool 결과에 넣는 안내 한 줄 (#720). 검색이 잠시 실패해 인기순으로 답했으면({@link RecommendBasis#POPULAR_TEMPORARY})
 * 대화 LLM이 관심사를 반영하지 못했다고 말하게 한다. 강의·인플루언서 Tool이 같은 문장을 쓴다.
 */
final class RecommendGuide {

    static final String TEMPORARY = "지금은 맞춤 추천을 준비하지 못해 인기순으로 골랐다. 사용자에게 관심사를 반영하지 못했고 "
            + "잠시 뒤 다시 물어보면 맞춤으로 찾을 수 있다고 알린다.";

    private RecommendGuide() {
    }

    static @Nullable String of(RecommendBasis basis) {
        return basis == RecommendBasis.POPULAR_TEMPORARY ? TEMPORARY : null;
    }
}

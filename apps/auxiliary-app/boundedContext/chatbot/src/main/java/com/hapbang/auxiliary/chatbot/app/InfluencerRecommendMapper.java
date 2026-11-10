package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.InfluencerRecommendUseCase.Candidate;
import com.hapbang.auxiliary.chatbot.app.InfluencerRecommendUseCase.InfluencerRecommendation;
import com.hapbang.auxiliary.chatbot.app.dto.InfluencerItemResDto;
import com.hapbang.auxiliary.chatbot.app.dto.InfluencerRecommendResDto;

import lombok.RequiredArgsConstructor;

/** 추천 후보 → 응답·Tool 항목 (#719). API와 Tool이 같은 모양을 쓴다 */
@Component
@RequiredArgsConstructor
class InfluencerRecommendMapper {

    private final ContentCategoryNames categoryNames;

    /**
     * 추천 응답.
     *
     * @param explanations 후보 회원 ID → 이유 문장. 설명이 없는 경로(위젯·Tool)면 null
     * @param fallback     LLM 답을 쓰지 못하고 이유 코드 문장으로 답했는지
     */
    InfluencerRecommendResDto response(InfluencerRecommendation recommendation, @Nullable Map<Long, String> explanations,
            boolean fallback) {
        return new InfluencerRecommendResDto(items(recommendation.items(), explanations), recommendation.needsInterest(),
                fallback);
    }

    /** @param explanations 후보 회원 ID → 이유 문장. 설명이 없는 경로면 null */
    List<InfluencerItemResDto> items(List<Candidate> candidates, @Nullable Map<Long, String> explanations) {
        List<InfluencerItemResDto> items = new ArrayList<>();
        for (Candidate candidate : candidates) {
            items.add(new InfluencerItemResDto(candidate.memberId(), categoryNames.namesOf(candidate.categories()),
                    candidate.completedAppearances(), candidate.averageRating(), candidate.orderedReasons(),
                    explanations == null ? null : explanations.get(candidate.memberId())));
        }
        return items;
    }
}

package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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

    /**
     * 요청 회원이 숨김일 때 화면에 띄우는 문구. 로그인한 회원이 숨김인 경우는 대개 가입 직후 동기화 전이지만, 탈퇴·마스킹 회원에게도
     * 틀리지 않게 까닭을 단정하지 않는다.
     */
    static final String REQUESTER_HIDDEN_NOTICE = "지금은 인플루언서를 추천할 수 없어요. 가입한 지 얼마 안 됐다면 잠시 뒤 다시 시도해 주세요.";

    private final ChatbotCategoryNames categoryNames;

    /** 설명 없는 응답(위젯 API·Tool) */
    InfluencerRecommendResDto response(InfluencerRecommendation recommendation) {
        return response(recommendation, candidate -> null, false);
    }

    /**
     * 설명을 붙인 응답(설명 API).
     *
     * @param explanations 후보 회원 ID → 이유 문장(모든 후보)
     * @param fallback     LLM 답을 쓰지 못하고 이유 코드 문장으로 답했는지
     */
    InfluencerRecommendResDto explained(InfluencerRecommendation recommendation, Map<Long, String> explanations,
            boolean fallback) {
        return response(recommendation, candidate -> explanations.get(candidate.memberId()), fallback);
    }

    private InfluencerRecommendResDto response(InfluencerRecommendation recommendation,
            Function<Candidate, @Nullable String> explanation, boolean fallback) {
        List<InfluencerItemResDto> items = new ArrayList<>();
        for (Candidate candidate : recommendation.items()) {
            items.add(new InfluencerItemResDto(candidate.memberId(), categoryNames.namesOf(candidate.categories()),
                    candidate.completedAppearances(), candidate.averageRating(), candidate.orderedReasons(),
                    explanation.apply(candidate)));
        }
        return new InfluencerRecommendResDto(items, recommendation.needsInterest(), fallback,
                recommendation.requesterHidden() ? REQUESTER_HIDDEN_NOTICE : null);
    }
}

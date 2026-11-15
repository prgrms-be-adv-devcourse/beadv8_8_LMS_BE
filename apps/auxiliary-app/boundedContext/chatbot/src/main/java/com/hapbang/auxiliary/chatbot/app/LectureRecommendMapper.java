package com.hapbang.auxiliary.chatbot.app;

import java.util.List;

import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.dto.LectureRecommendResDto;

/** 추천 결과 → 강의 추천 응답 (#718, #835). 인플루언서 추천({@link InfluencerRecommendMapper})과 같은 자리에서 바꾼다 */
@Component
class LectureRecommendMapper {

    /** 위젯 응답(LLM 없음) */
    LectureRecommendResDto response(LectureRecommendation recommendation) {
        return response(recommendation, false);
    }

    /**
     * @param fallback LLM 답을 쓰지 못하고 이유 코드 문장으로 답했는지
     */
    LectureRecommendResDto response(LectureRecommendation recommendation, boolean fallback) {
        List<LectureRecommendResDto.Item> items = recommendation.items().stream()
                .map(item -> new LectureRecommendResDto.Item(item.lectureId(), item.title(), item.score(), item.reason(),
                        item.reasonText()))
                .toList();
        return new LectureRecommendResDto(items, recommendation.needsInterest(), recommendation.basis(), fallback,
                recommendation.requesterHidden() ? LectureRecommendResDto.REQUESTER_HIDDEN_NOTICE : null);
    }
}

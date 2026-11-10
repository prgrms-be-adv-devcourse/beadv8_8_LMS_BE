package com.hapbang.auxiliary.chatbot.app;

import java.util.List;
import java.util.function.Function;

import com.hapbang.auxiliary.chatbot.domain.LectureRecommendReason;

/**
 * 강의 추천 결과 (#718).
 *
 * @param needsInterest 활동 신호도 관심사도 없어 인기순만 줬다. 관심사를 물어볼 때다
 * @param personalized  맞춤 후보가 하나라도 있다. false면 모두 인기순이다(신호 없음, 또는 색인·임베딩을 못 써서 인기순으로 답함)
 */
public record LectureRecommendation(List<Item> items, boolean needsInterest, boolean personalized) {

    public LectureRecommendation {
        items = List.copyOf(items);
    }

    /** 요청 회원이 숨김(탈퇴·마스킹) 상태일 때 */
    static LectureRecommendation empty() {
        return new LectureRecommendation(List.of(), false, false);
    }

    /** 이유 문장만 바꾼 결과(LLM 문장) */
    LectureRecommendation withReasonTexts(Function<Item, String> reasonText) {
        List<Item> replaced = items.stream()
                .map(item -> new Item(item.lectureId(), item.title(), item.score(), item.reason(), reasonText.apply(item)))
                .toList();
        return new LectureRecommendation(replaced, needsInterest, personalized);
    }

    /**
     * @param score      최종 점수({@code (1−β)·유사도 + β·인기}). 인기순으로 채운 강의는 0
     * @param reasonText 카드에 보여 줄 이유 문장. 기본은 이유 코드의 정해진 문장, 버튼 경로는 LLM 문장
     */
    public record Item(long lectureId, String title, double score, LectureRecommendReason reason, String reasonText) {
    }
}

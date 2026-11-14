package com.hapbang.auxiliary.chatbot.app;

import java.util.List;
import java.util.function.Function;

import com.hapbang.auxiliary.chatbot.domain.LectureRecommendReason;
import com.hapbang.auxiliary.chatbot.domain.RecommendBasis;

/**
 * 강의 추천 결과 (#718, #720).
 *
 * @param needsInterest   지금은 맞춤으로 고를 수 없어 인기순을 줬고, 관심사를 주면 맞춤으로 찾을 수 있다(활동 신호가 없거나 신호 강의가
 *                        아직 색인 전). 관심사를 물어볼 때다. 모델·ES를 끈 실행이거나 검색이 잠시 실패했으면 관심사도 소용없어 false다
 * @param basis           후보의 근거. 검색이 잠시 실패해 인기순으로 대신 답했으면 {@link RecommendBasis#POPULAR_TEMPORARY}
 * @param requesterHidden 요청 회원이 숨김(사본 없음·탈퇴·마스킹)이라 추천하지 않았다(items는 비어 있다)
 */
public record LectureRecommendation(List<Item> items, boolean needsInterest, RecommendBasis basis, boolean requesterHidden) {

    public LectureRecommendation {
        items = List.copyOf(items);
    }

    /** 요청 회원이 숨김 상태일 때 */
    static LectureRecommendation forHiddenRequester() {
        return new LectureRecommendation(List.of(), false, RecommendBasis.POPULAR, true);
    }

    /** 이유 문장만 바꾼 결과(LLM 문장) */
    LectureRecommendation withReasonTexts(Function<Item, String> reasonText) {
        List<Item> replaced = items.stream()
                .map(item -> new Item(item.lectureId(), item.title(), item.score(), item.reason(), reasonText.apply(item)))
                .toList();
        return new LectureRecommendation(replaced, needsInterest, basis, requesterHidden);
    }

    /**
     * @param score      최종 점수({@code (1−β)·유사도 + β·인기}). 인기순으로 채운 강의는 0
     * @param reasonText 카드에 보여 줄 이유 문장. 기본은 이유 코드의 정해진 문장, 버튼 경로는 LLM 문장
     */
    public record Item(long lectureId, String title, double score, LectureRecommendReason reason, String reasonText) {
    }
}

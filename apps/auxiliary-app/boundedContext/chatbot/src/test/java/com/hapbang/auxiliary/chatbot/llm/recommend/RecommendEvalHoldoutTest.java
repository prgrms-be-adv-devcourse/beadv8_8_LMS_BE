package com.hapbang.auxiliary.chatbot.llm.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Activity;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Answer;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Appearance;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.AppearanceReview;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Rating;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Split;

class RecommendEvalHoldoutTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 1, 10, 0);

    private static RecommendEvalData data(List<Activity> purchases, List<Activity> carts, List<Rating> reviews,
            List<Appearance> appearances, List<AppearanceReview> appearanceReviews, List<Answer> lectureAnswers,
            List<Answer> influencerAnswers) {
        Split empty = new Split(List.of(), List.of());
        return new RecommendEvalData(1, List.of(), List.of(), List.of(), List.of(), purchases, carts, reviews, List.of(),
                appearances, appearanceReviews, lectureAnswers, influencerAnswers, empty, empty);
    }

    @Test
    void hidesAnswerLectureFromPurchaseCartAndReviewOfThatMemberOnly() {
        // 회원 1의 정답은 강의 10. 회원 2도 강의 10을 샀다(남아야 한다)
        RecommendEvalData data = data(
                List.of(new Activity(1, 1, 10, true, AT), new Activity(2, 1, 11, true, AT), new Activity(3, 2, 10, true, AT)),
                List.of(new Activity(1, 1, 10, true, AT), new Activity(2, 1, 12, false, AT)),
                List.of(new Rating(1, 1, 10, 5), new Rating(2, 1, 11, 4), new Rating(3, 2, 10, 2)),
                List.of(), List.of(), List.of(new Answer(1, 10)), List.of());

        RecommendEvalData visible = RecommendEvalHoldout.hide(data);

        assertThat(visible.purchases()).extracting(Activity::id).containsExactly(2L, 3L);
        assertThat(visible.carts()).extracting(Activity::id).containsExactly(2L);
        assertThat(visible.lectureReviews()).extracting(Rating::id).containsExactly(2L, 3L);
    }

    @Test
    void hidesEveryAppearanceOfAnswerPairAndItsReviews() {
        // 2001의 정답은 2002. 둘은 두 번 함께했다(호스트·게스트 순서가 다름). 2001-2003은 남는다
        RecommendEvalData data = data(List.of(), List.of(), List.of(),
                List.of(new Appearance(1, 2001, 2002), new Appearance(2, 2002, 2001), new Appearance(3, 2001, 2003)),
                List.of(new AppearanceReview(1, 1, 2002, 5), new AppearanceReview(2, 2, 2001, 4),
                        new AppearanceReview(3, 3, 2003, 3)),
                List.of(), List.of(new Answer(2001, 2002)));

        RecommendEvalData visible = RecommendEvalHoldout.hide(data);

        assertThat(visible.appearances()).extracting(Appearance::id).containsExactly(3L);
        assertThat(visible.appearanceReviews()).extracting(AppearanceReview::id).containsExactly(3L);
    }
}

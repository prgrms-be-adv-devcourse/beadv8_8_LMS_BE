package com.hapbang.auxiliary.chatbot.llm.recommend;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Activity;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Answer;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Appearance;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.AppearanceReview;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Rating;

/**
 * 정답 숨기기(#867). 모든 요청 회원의 정답을 한 번에 빼고 사본에 넣는다(DB를 한 번만 채운다).
 * <ul>
 *     <li>강의: 그 회원의 정답 강의 구매·장바구니·리뷰 행을 모두 뺀다. 남으면 신호({@code activities}·{@code ratings})나
 *     제외 목록({@code excludedLectures}, 이미 산·담은 강의)에 걸려 절대 맞힐 수 없다. 숨긴 구매는 그 강의의 인기 수에서도 빠진다.</li>
 *     <li>인플루언서: 회원과 정답 상대가 함께 마친 출연과 그 출연의 리뷰를 뺀다. 상대는 제외 목록이 없어 신호({@code completedPartners})에서만
 *     빠지면 된다. 한 출연을 빼면 상대 쪽 신호에서도 빠지지만 그 상대의 정답이 새는 것은 아니다.</li>
 * </ul>
 */
final class RecommendEvalHoldout {

    private RecommendEvalHoldout() {
    }

    /** 정답 행을 뺀 데이터 */
    static RecommendEvalData hide(RecommendEvalData data) {
        Set<String> lecturePairs = new HashSet<>();
        for (Answer answer : data.lectureAnswers()) {
            lecturePairs.add(answer.requesterId() + ":" + answer.answerId());
        }
        List<Activity> purchases = new ArrayList<>();
        for (Activity purchase : data.purchases()) {
            if (!lecturePairs.contains(purchase.memberId() + ":" + purchase.lectureId())) {
                purchases.add(purchase);
            }
        }
        List<Activity> carts = new ArrayList<>();
        for (Activity cart : data.carts()) {
            if (!lecturePairs.contains(cart.memberId() + ":" + cart.lectureId())) {
                carts.add(cart);
            }
        }
        List<Rating> reviews = new ArrayList<>();
        for (Rating review : data.lectureReviews()) {
            if (!lecturePairs.contains(review.memberId() + ":" + review.lectureId())) {
                reviews.add(review);
            }
        }

        Set<String> partnerPairs = new HashSet<>();
        for (Answer answer : data.influencerAnswers()) {
            partnerPairs.add(RecommendEvalDataGenerator.pairKey(answer.requesterId(), answer.answerId()));
        }
        List<Appearance> appearances = new ArrayList<>();
        Set<Long> removed = new HashSet<>();
        for (Appearance appearance : data.appearances()) {
            if (partnerPairs.contains(RecommendEvalDataGenerator.pairKey(appearance.hostId(), appearance.guestId()))) {
                removed.add(appearance.id());
            } else {
                appearances.add(appearance);
            }
        }
        List<AppearanceReview> appearanceReviews = new ArrayList<>();
        for (AppearanceReview review : data.appearanceReviews()) {
            if (!removed.contains(review.appearanceId())) {
                appearanceReviews.add(review);
            }
        }
        return data.withRows(purchases, carts, reviews, appearances, appearanceReviews);
    }
}

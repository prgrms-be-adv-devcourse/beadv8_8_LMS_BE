package com.hapbang.auxiliary.chatbot.domain;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 인플루언서 인기 점수 계산 (#719). 사본에서 센 값으로 요청할 때 계산한다(색인에 넣지 않는다).
 * <ul>
 *     <li>평균 별점은 리뷰 수로 보정한다(베이지안 평균 {@code (C·m + Σ별점) / (C + n)}, m은 전체 평균). 리뷰가 적은 회원이 별점 하나로
 *     맨 위에 오지 않게 한다.</li>
 *     <li>원점수 = {@code w1·ln(1 + 완료 출연 수) + w2·보정 평균}.</li>
 *     <li>섞을 때는 후보 안에서 0~1 min-max 정규화한다({@link #normalize}). 모두 같으면 0이다.</li>
 * </ul>
 *
 * @param completedWeight 완료 출연 수 가중치 w1
 * @param ratingWeight    보정 평균 별점 가중치 w2
 * @param priorReviews    베이지안 평균의 C
 */
public record InfluencerPopularity(double completedWeight, double ratingWeight, double priorReviews) {

    /**
     * 회원 한 명의 인기 재료.
     *
     * @param completed   함께 마친 출연 수(호스트·게스트 모두, 원본 없음 제외)
     * @param reviewCount 받은 출연 리뷰 수(원본 없음 제외)
     * @param ratingSum   받은 출연 리뷰 별점 합
     */
    public record Stats(long memberId, long completed, long reviewCount, long ratingSum) {

        public static Stats empty(long memberId) {
            return new Stats(memberId, 0, 0, 0);
        }
    }

    /**
     * 리뷰 수로 보정한 평균 별점. 리뷰가 없으면 전체 평균이다.
     *
     * @param overallMean 모든 리뷰의 평균 별점(리뷰가 하나도 없으면 0)
     */
    public double bayesianRating(Stats stats, double overallMean) {
        double weight = priorReviews + stats.reviewCount();
        if (weight == 0) {
            return overallMean;
        }
        return (priorReviews * overallMean + stats.ratingSum()) / weight;
    }

    /** 원점수 */
    public double raw(Stats stats, double overallMean) {
        return completedWeight * Math.log1p(stats.completed()) + ratingWeight * bayesianRating(stats, overallMean);
    }

    /** 회원 ID → 원점수 */
    public Map<Long, Double> rawScores(Collection<Stats> stats, double overallMean) {
        Map<Long, Double> scores = new HashMap<>();
        for (Stats one : stats) {
            scores.put(one.memberId(), raw(one, overallMean));
        }
        return scores;
    }

    /** 후보 안 0~1 min-max 정규화. 후보가 없거나 모두 같으면 모두 0이다 */
    public static Map<Long, Double> normalize(Map<Long, Double> rawScores) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double score : rawScores.values()) {
            min = Math.min(min, score);
            max = Math.max(max, score);
        }
        Map<Long, Double> normalized = new HashMap<>();
        for (Map.Entry<Long, Double> entry : rawScores.entrySet()) {
            double value = max > min ? (entry.getValue() - min) / (max - min) : 0;
            normalized.put(entry.getKey(), value);
        }
        return normalized;
    }
}

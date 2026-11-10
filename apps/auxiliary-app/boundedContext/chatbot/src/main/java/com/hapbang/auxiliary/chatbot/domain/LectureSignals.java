package com.hapbang.auxiliary.chatbot.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntToDoubleFunction;

/**
 * 회원 활동에서 강의별 신호 가중을 정한다 (#718).
 * <ul>
 *     <li>강의 하나에 구매와 장바구니가 함께 있으면 구매로 본다(가중이 큰 쪽).</li>
 *     <li>최근 활동 순으로 {@code limit}개만 쓴다.</li>
 *     <li>가중 = 기본(구매·장바구니) + 그 강의에 남긴 리뷰 별점 보정. 0 아래로 내리지 않고, 0인 강의는 신호에서 뺀다.
 *     리뷰만 있고 구매·장바구니가 없는 강의는 신호가 아니다(보정할 기본이 없다).</li>
 * </ul>
 */
public final class LectureSignals {

    private LectureSignals() {
    }

    /** 신호의 출처 */
    public enum Source {

        PURCHASED("구매(OWNED)"),
        CART("장바구니(삭제 안 됨)");

        private final String description;

        Source(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    /** 회원 활동 하나 */
    public record Activity(long lectureId, Source source, LocalDateTime at) {
    }

    /** 강의 하나의 신호. weight는 0보다 크다 */
    public record Signal(long lectureId, Source source, double weight) {
    }

    /**
     * @param owned  구매 기본 가중
     * @param cart   장바구니 기본 가중
     * @param rating 별점(1~5) → 보정값
     */
    public record Weights(double owned, double cart, IntToDoubleFunction rating) {

        double base(Source source) {
            return source == Source.PURCHASED ? owned : cart;
        }
    }

    /**
     * @param activities 구매·장바구니 활동
     * @param ratings    강의 ID → 회원이 남긴 리뷰 별점
     * @param limit      쓸 신호 강의 수(최근 순)
     * @return 최근 순 신호. 가중이 0인 강의는 빠진다
     */
    public static List<Signal> weigh(List<Activity> activities, Map<Long, Integer> ratings, int limit, Weights weights) {
        Map<Long, Activity> byLecture = new LinkedHashMap<>();
        for (Activity activity : activities) {
            Activity previous = byLecture.get(activity.lectureId());
            byLecture.put(activity.lectureId(), previous == null ? activity : merge(previous, activity));
        }
        List<Activity> recent = new ArrayList<>(byLecture.values());
        recent.sort(Comparator.comparing(Activity::at).reversed().thenComparing(Activity::lectureId, Comparator.reverseOrder()));

        List<Signal> signals = new ArrayList<>();
        for (Activity activity : recent.subList(0, Math.min(limit, recent.size()))) {
            double weight = weights.base(activity.source());
            Integer stars = ratings.get(activity.lectureId());
            if (stars != null) {
                weight += weights.rating().applyAsDouble(stars);
            }
            if (weight > 0) {
                signals.add(new Signal(activity.lectureId(), activity.source(), weight));
            }
        }
        return signals;
    }

    // 같은 강의의 활동 둘: 구매가 있으면 구매, 시각은 늦은 쪽
    private static Activity merge(Activity a, Activity b) {
        Source source = a.source() == Source.PURCHASED || b.source() == Source.PURCHASED ? Source.PURCHASED : Source.CART;
        LocalDateTime at = a.at().isAfter(b.at()) ? a.at() : b.at();
        return new Activity(a.lectureId(), source, at);
    }
}

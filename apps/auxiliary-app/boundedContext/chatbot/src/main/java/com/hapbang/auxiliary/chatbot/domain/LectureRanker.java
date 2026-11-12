package com.hapbang.auxiliary.chatbot.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 추천 후보의 순서를 정하고 고른다 (#718).
 * <ul>
 *     <li>최종 점수 = {@code (1−β)·유사도 + β·인기}. 인기는 후보 안에서 0~1로 min-max 정규화한다(모두 같으면 0).</li>
 *     <li>점수가 같으면 강의 ID 내림차순(최근 강의 먼저).</li>
 *     <li>판매자 한 명의 강의는 {@code maxPerSeller}개까지, 모두 {@code size}개까지 고른다.</li>
 *     <li>모자라면 인기순 목록으로 같은 판매자 규칙을 지켜 채운다.</li>
 * </ul>
 */
public final class LectureRanker {

    private LectureRanker() {
    }

    /**
     * 벡터 검색 후보.
     *
     * @param similarity 코사인 유사도
     * @param ownedCount 구매({@code OWNED}) 수(인기)
     */
    public record Candidate(long lectureId, long sellerId, double similarity, long ownedCount) {
    }

    /** 인기순 목록의 강의(구매 수 내림차순, 같으면 강의 ID 내림차순으로 이미 정렬됨) */
    public record Popular(long lectureId, long sellerId) {
    }

    /**
     * 고른 강의.
     *
     * @param score   최종 점수. 인기순으로 채운 강의는 0
     * @param popular 인기순으로 채웠는지
     */
    public record Ranked(long lectureId, long sellerId, double score, boolean popular) {
    }

    /**
     * 맞춤 후보를 고르고 모자라면 인기순으로 채운다({@link #rank(List, double, int, int)} 뒤 {@link #fill}).
     *
     * @param popularityWeight β
     */
    public static List<Ranked> rank(List<Candidate> candidates, List<Popular> popular, double popularityWeight, int size,
            int maxPerSeller) {
        return fill(rank(candidates, popularityWeight, size, maxPerSeller), popular, size, maxPerSeller);
    }

    /**
     * 맞춤 후보만 점수 순으로 {@code size}개까지 고른다(판매자당 상한 적용, 인기순 채움 없음). 버튼 경로의 후보 풀이다.
     *
     * @param popularityWeight β
     */
    public static List<Ranked> rank(List<Candidate> candidates, double popularityWeight, int size, int maxPerSeller) {
        List<Ranked> scored = score(candidates, popularityWeight);
        scored.sort(Comparator.comparingDouble(Ranked::score).reversed()
                .thenComparing(Ranked::lectureId, Comparator.reverseOrder()));

        List<Ranked> picked = new ArrayList<>();
        Set<Long> pickedIds = new HashSet<>();
        Map<Long, Integer> perSeller = new HashMap<>();
        for (Ranked ranked : scored) {
            if (picked.size() >= size) {
                break;
            }
            if (take(ranked.lectureId(), ranked.sellerId(), pickedIds, perSeller, maxPerSeller)) {
                picked.add(ranked);
            }
        }
        return picked;
    }

    /**
     * 이미 고른 강의 뒤에 인기순 목록으로 {@code size}개까지 채운다. 판매자당 상한은 고른 강의까지 함께 센다.
     */
    public static List<Ranked> fill(List<Ranked> chosen, List<Popular> popular, int size, int maxPerSeller) {
        List<Ranked> picked = new ArrayList<>();
        Set<Long> pickedIds = new HashSet<>();
        Map<Long, Integer> perSeller = new HashMap<>();
        for (Ranked ranked : chosen) {
            if (take(ranked.lectureId(), ranked.sellerId(), pickedIds, perSeller, Integer.MAX_VALUE)) {
                picked.add(ranked);
            }
        }
        for (Popular item : popular) {
            if (picked.size() >= size) {
                break;
            }
            if (take(item.lectureId(), item.sellerId(), pickedIds, perSeller, maxPerSeller)) {
                picked.add(new Ranked(item.lectureId(), item.sellerId(), 0, true));
            }
        }
        return picked;
    }

    private static List<Ranked> score(List<Candidate> candidates, double popularityWeight) {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (Candidate candidate : candidates) {
            min = Math.min(min, candidate.ownedCount());
            max = Math.max(max, candidate.ownedCount());
        }
        List<Ranked> scored = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            double popularity = max > min ? (double) (candidate.ownedCount() - min) / (max - min) : 0;
            double score = (1 - popularityWeight) * candidate.similarity() + popularityWeight * popularity;
            scored.add(new Ranked(candidate.lectureId(), candidate.sellerId(), score, false));
        }
        return scored;
    }

    private static boolean take(long lectureId, long sellerId, Set<Long> pickedIds, Map<Long, Integer> perSeller,
            int maxPerSeller) {
        if (pickedIds.contains(lectureId) || perSeller.getOrDefault(sellerId, 0) >= maxPerSeller) {
            return false;
        }
        pickedIds.add(lectureId);
        perSeller.merge(sellerId, 1, Integer::sum);
        return true;
    }
}

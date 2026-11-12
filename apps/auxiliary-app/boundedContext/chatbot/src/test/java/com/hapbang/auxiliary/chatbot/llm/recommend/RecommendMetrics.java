package com.hapbang.auxiliary.chatbot.llm.recommend;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 추천 평가 지표(#867). 정답은 회원마다 하나다.
 * <ul>
 *     <li>hit@5: 정답이 사용자가 보는 5개(인기순으로 채운 것 포함) 안에 있다.</li>
 *     <li>nDCG@5: 정답이 r위(1~5)면 {@code 1/log2(r+1)}, 없으면 0. 정답이 하나라 이상적인 값은 1이다.</li>
 *     <li>풀 recall@20: 정답이 결과 수 20으로 받은 목록의 <b>맞춤 후보</b> 안에 있다. 인기순으로 채운 항목은 세지 않는다(리랭킹이 고를 풀).</li>
 *     <li>서로 다른 항목 수: 모든 회원의 상위 5개를 합친 서로 다른 ID 수. β를 올릴 때 인기 있는 것만 나오는지(쏠림) 본다.</li>
 * </ul>
 */
public final class RecommendMetrics {

    static final int TOP = 5;
    static final int POOL = 20;

    private RecommendMetrics() {
    }

    /**
     * 추천 결과 한 항목.
     *
     * @param personalized 맞춤 후보(false면 인기순으로 채운 항목)
     */
    public record Pick(long id, boolean personalized) {
    }

    /** 정답의 순위(1부터). 앞 {@code limit}개 안에 없으면 0 */
    static int rank(long answer, List<Pick> picks, int limit) {
        int size = Math.min(limit, picks.size());
        for (int i = 0; i < size; i++) {
            if (picks.get(i).id() == answer) {
                return i + 1;
            }
        }
        return 0;
    }

    static double ndcgAt5(long answer, List<Pick> top) {
        int rank = rank(answer, top, TOP);
        return rank == 0 ? 0 : 1 / (Math.log(rank + 1) / Math.log(2));
    }

    static boolean recallAt20(long answer, List<Pick> pool) {
        int size = Math.min(POOL, pool.size());
        for (int i = 0; i < size; i++) {
            Pick pick = pool.get(i);
            if (pick.personalized() && pick.id() == answer) {
                return true;
            }
        }
        return false;
    }

    /** 여러 회원의 결과를 모은다 */
    static final class Tally {

        private int members;
        private int hits;
        private double ndcg;
        private int recalled;
        private final Set<Long> distinctTop = new HashSet<>();

        /**
         * @param top  결과 수 5로 받은 목록
         * @param pool 결과 수 20으로 받은 목록
         */
        void add(long answer, List<Pick> top, List<Pick> pool) {
            members++;
            if (rank(answer, top, TOP) > 0) {
                hits++;
            }
            ndcg += RecommendMetrics.ndcgAt5(answer, top);
            if (RecommendMetrics.recallAt20(answer, pool)) {
                recalled++;
            }
            int size = Math.min(TOP, top.size());
            for (int i = 0; i < size; i++) {
                distinctTop.add(top.get(i).id());
            }
        }

        int members() {
            return members;
        }

        double hitAt5() {
            return members == 0 ? 0 : (double) hits / members;
        }

        double ndcgAt5() {
            return members == 0 ? 0 : ndcg / members;
        }

        double recallAt20() {
            return members == 0 ? 0 : (double) recalled / members;
        }

        int distinctTop5() {
            return distinctTop.size();
        }
    }
}

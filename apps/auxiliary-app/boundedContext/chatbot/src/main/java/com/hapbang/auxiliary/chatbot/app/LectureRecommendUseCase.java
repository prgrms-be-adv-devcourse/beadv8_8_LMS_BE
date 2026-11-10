package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.config.ChatbotLectureRecommendProperties;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.LectureRanker;
import com.hapbang.auxiliary.chatbot.domain.LectureRecommendReason;
import com.hapbang.auxiliary.chatbot.domain.LectureSignals;
import com.hapbang.auxiliary.chatbot.domain.LectureSnapshot;
import com.hapbang.auxiliary.chatbot.domain.TasteVectors;
import com.hapbang.auxiliary.chatbot.exception.ChatbotErrorCode;
import com.hapbang.auxiliary.chatbot.exception.ChatbotException;
import com.hapbang.auxiliary.chatbot.out.LectureRecommendStore;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 강의 추천 후보 생성 (#718). <b>후보는 코드가 정한다</b>(같은 입력이면 같은 결과). LLM을 부르지 않는다.
 * <ol>
 *     <li>요청 회원이 숨김(탈퇴·마스킹)이면 빈 결과.</li>
 *     <li>신호: 구매 {@code OWNED}·장바구니에 리뷰 별점 보정, 최근 순 상한, 0 아래로 내리지 않음({@link LectureSignals}).</li>
 *     <li>질의 벡터: 신호 강의 벡터의 가중 평균. 관심사가 함께 있으면 {@code (1−α)·신호 + α·관심사}, 신호가 없으면 관심사만.
 *     둘 다 없으면 인기순만 주고 {@code needsInterest}.</li>
 *     <li>kNN(판매 중·삭제 안 됨·원본에 있음 필터) 뒤 코드에서 뺀다: 구매 {@code OWNED}·{@code PENDING}, 장바구니, 본인 강의,
 *     숨김 판매자, 사본으로 다시 본 추천 불가 상태(색인이 늦은 경우).</li>
 *     <li>{@link LectureRanker}: β 혼합, 동점 순서, 판매자당 상한, 인기순 채우기.</li>
 *     <li>이유 코드: 고른 강의마다 구매 중심·장바구니 중심·관심사 벡터 중 가장 가까운 쪽. 구매·장바구니면 가장 가까운 신호 강의 제목을
 *     문장에 넣는다(공개 정보인 강의 제목).</li>
 * </ol>
 * 색인·임베딩을 쓸 수 없으면(연결 없음·호출 실패) 관심사 없는 요청은 인기순({@code personalized=false})으로 답하고,
 * 관심사를 넣은 요청은 {@link ChatbotErrorCode#LECTURE_RECOMMEND_UNAVAILABLE}이다(관심사를 무시한 결과를 맞춤처럼 보이지 않게).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LectureRecommendUseCase {

    private final LectureRecommendStore store;
    private final RecommendVectorIndex index;
    private final ChatbotEmbedder embedder;
    private final MemberVisibility visibility;
    private final ChatbotLectureRecommendProperties properties;

    /**
     * @param interest 사용자가 직접 입력한 관심사 문장. 없으면 null(저장하지 않는다)
     */
    public LectureRecommendation recommend(long memberId, @Nullable String interest) {
        if (!visibility.isVisible(memberId)) {
            return LectureRecommendation.empty();
        }
        Set<Long> excluded = store.excludedLectures(memberId);
        List<LectureSignals.Signal> signals = LectureSignals.weigh(store.activities(memberId), store.ratings(memberId),
                properties.signalLimit(), weights());
        if (signals.isEmpty() && interest == null) {
            return popularOnly(memberId, excluded, true);
        }
        if (!index.available() || !embedder.available()) {
            return unavailable(memberId, excluded, interest, null);
        }
        try {
            return personalized(memberId, interest, signals, excluded);
        } catch (RuntimeException e) {
            return unavailable(memberId, excluded, interest, e);
        }
    }

    private LectureRecommendation personalized(long memberId, @Nullable String interest, List<LectureSignals.Signal> signals,
            Set<Long> excluded) {
        Map<Long, float[]> signalVectors = vectors(signals.stream().map(LectureSignals.Signal::lectureId).toList());
        Map<LectureSignals.Source, float[]> centroids = centroids(signals, signalVectors);
        float[] signalVector = TasteVectors.weightedAverage(signalVectors, weightsById(signals, null));
        float[] interestVector = interest == null ? null : embedder.embedQuery(interest);
        float[] query = query(signalVector, interestVector);
        if (query == null) {
            // 신호 강의가 아직 색인되지 않았다. 신호가 없는 것과 같다
            return popularOnly(memberId, excluded, true);
        }

        List<RecommendVectorIndex.ScoredId> hits = index.knn(IndexType.LECTURE, query, properties.knnK(),
                properties.numCandidates(), LectureIndexDocument.RECOMMENDABLE);
        Map<Long, LectureSnapshot> lectures = byId(store.lectures(hits.stream().map(hit -> Long.parseLong(hit.id())).toList()));
        List<LectureRanker.Candidate> candidates = candidates(memberId, hits, lectures, excluded);
        List<LectureRanker.Popular> popular = popularPool(memberId, excluded);
        List<LectureRanker.Ranked> ranked = LectureRanker.rank(candidates, popular, properties.popularityWeight(),
                properties.resultSize(), properties.maxPerSeller());

        Map<Long, Reasoned> reasons = reasons(ranked, signals, signalVectors, centroids, interestVector);
        return toRecommendation(ranked, reasons, false);
    }

    // 코드에서 빼는 규칙. kNN 필터를 지나도 사본으로 다시 본다(색인이 늦게 따라온 경우)
    private List<LectureRanker.Candidate> candidates(long memberId, List<RecommendVectorIndex.ScoredId> hits,
            Map<Long, LectureSnapshot> lectures, Set<Long> excluded) {
        Set<Long> sellers = new HashSet<>();
        for (LectureSnapshot lecture : lectures.values()) {
            sellers.add(lecture.sellerId());
        }
        Set<Long> visibleSellers = visibility.visibleOf(sellers);
        Map<Long, Double> similarity = new LinkedHashMap<>();
        for (RecommendVectorIndex.ScoredId hit : hits) {
            long lectureId = Long.parseLong(hit.id());
            LectureSnapshot lecture = lectures.get(lectureId);
            if (lecture == null || !lecture.recommendable() || excluded.contains(lectureId) || lecture.sellerId() == memberId
                    || !visibleSellers.contains(lecture.sellerId())) {
                continue;
            }
            similarity.put(lectureId, hit.score());
        }
        Map<Long, Long> owned = store.ownedCounts(similarity.keySet());
        List<LectureRanker.Candidate> candidates = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : similarity.entrySet()) {
            long lectureId = entry.getKey();
            candidates.add(new LectureRanker.Candidate(lectureId, lectures.get(lectureId).sellerId(), entry.getValue(),
                    owned.get(lectureId)));
        }
        return candidates;
    }

    private LectureRecommendation popularOnly(long memberId, Set<Long> excluded, boolean needsInterest) {
        List<LectureRanker.Ranked> ranked = LectureRanker.rank(List.of(), popularPool(memberId, excluded),
                properties.popularityWeight(), properties.resultSize(), properties.maxPerSeller());
        return toRecommendation(ranked, Map.of(), needsInterest);
    }

    private LectureRecommendation unavailable(long memberId, Set<Long> excluded, @Nullable String interest,
            @Nullable RuntimeException cause) {
        if (interest != null) {
            log.warn("강의 추천 색인·임베딩을 쓸 수 없어 관심사 추천을 거절한다", cause);
            throw cause == null ? new ChatbotException(ChatbotErrorCode.LECTURE_RECOMMEND_UNAVAILABLE)
                    : new ChatbotException(ChatbotErrorCode.LECTURE_RECOMMEND_UNAVAILABLE, cause);
        }
        log.warn("강의 추천 색인·임베딩을 쓸 수 없어 인기순으로 답한다", cause);
        return popularOnly(memberId, excluded, false);
    }

    // 숨김 판매자를 뺀 인기순 목록
    private List<LectureRanker.Popular> popularPool(long memberId, Set<Long> excluded) {
        List<LectureRanker.Popular> pool = store.popular(memberId, excluded, properties.popularPoolSize());
        Set<Long> sellers = new HashSet<>();
        for (LectureRanker.Popular item : pool) {
            sellers.add(item.sellerId());
        }
        Set<Long> visibleSellers = visibility.visibleOf(sellers);
        return pool.stream().filter(item -> visibleSellers.contains(item.sellerId())).toList();
    }

    private float @Nullable [] query(float @Nullable [] signal, float @Nullable [] interest) {
        if (signal != null && interest != null) {
            return TasteVectors.mix(signal, interest, properties.interestWeight());
        }
        return signal != null ? signal : interest;
    }

    // 출처별 중심 벡터(이유 코드용)
    private static Map<LectureSignals.Source, float[]> centroids(List<LectureSignals.Signal> signals,
            Map<Long, float[]> vectors) {
        Map<LectureSignals.Source, float[]> centroids = new HashMap<>();
        for (LectureSignals.Source source : LectureSignals.Source.values()) {
            float[] centroid = TasteVectors.weightedAverage(vectors, weightsById(signals, source));
            if (centroid != null) {
                centroids.put(source, centroid);
            }
        }
        return centroids;
    }

    // 고른 강의(인기순 채움 제외)마다 가장 가까운 기준을 이유로
    private Map<Long, Reasoned> reasons(List<LectureRanker.Ranked> ranked, List<LectureSignals.Signal> signals,
            Map<Long, float[]> signalVectors, Map<LectureSignals.Source, float[]> centroids, float @Nullable [] interest) {
        List<Long> ids = ranked.stream().filter(item -> !item.popular()).map(LectureRanker.Ranked::lectureId).toList();
        Map<Long, float[]> itemVectors = vectors(ids);
        Map<Long, Reasoned> reasons = new HashMap<>();
        for (Long id : ids) {
            float[] vector = itemVectors.get(id);
            if (vector != null) {
                reasons.put(id, reason(vector, signals, signalVectors, centroids, interest));
            }
        }
        return reasons;
    }

    private static Reasoned reason(float[] vector, List<LectureSignals.Signal> signals, Map<Long, float[]> signalVectors,
            Map<LectureSignals.Source, float[]> centroids, float @Nullable [] interest) {
        LectureRecommendReason best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        LectureSignals.Source bestSource = null;
        for (Map.Entry<LectureSignals.Source, float[]> centroid : centroids.entrySet()) {
            double score = TasteVectors.cosine(vector, centroid.getValue());
            if (score > bestScore) {
                bestScore = score;
                bestSource = centroid.getKey();
                best = centroid.getKey() == LectureSignals.Source.PURCHASED ? LectureRecommendReason.SIMILAR_TO_PURCHASED
                        : LectureRecommendReason.SIMILAR_TO_CART;
            }
        }
        // 질의 벡터는 신호나 관심사로 만들었으므로 기준이 하나는 있다. 신호 중심이 없으면 관심사가 이유다
        if (best == null || interest != null && TasteVectors.cosine(vector, interest) > bestScore) {
            return new Reasoned(LectureRecommendReason.MATCHES_INTEREST, null);
        }
        return new Reasoned(best, closestSignal(vector, signals, signalVectors, bestSource));
    }

    // 그 출처의 신호 강의 중 가장 가까운 강의
    private static @Nullable Long closestSignal(float[] vector, List<LectureSignals.Signal> signals,
            Map<Long, float[]> signalVectors, LectureSignals.Source source) {
        Long closest = null;
        double closestScore = Double.NEGATIVE_INFINITY;
        for (LectureSignals.Signal signal : signals) {
            float[] signalVector = signalVectors.get(signal.lectureId());
            if (signal.source() != source || signalVector == null) {
                continue;
            }
            double score = TasteVectors.cosine(vector, signalVector);
            if (score > closestScore) {
                closestScore = score;
                closest = signal.lectureId();
            }
        }
        return closest;
    }

    private LectureRecommendation toRecommendation(List<LectureRanker.Ranked> ranked, Map<Long, Reasoned> reasons,
            boolean needsInterest) {
        Set<Long> titleIds = new HashSet<>();
        for (LectureRanker.Ranked item : ranked) {
            titleIds.add(item.lectureId());
        }
        for (Reasoned reasoned : reasons.values()) {
            if (reasoned.basisLectureId() != null) {
                titleIds.add(reasoned.basisLectureId());
            }
        }
        Map<Long, LectureSnapshot> lectures = byId(store.lectures(titleIds));
        List<LectureRecommendation.Item> items = new ArrayList<>();
        boolean personalized = false;
        for (LectureRanker.Ranked item : ranked) {
            Reasoned reasoned = item.popular() ? null : reasons.get(item.lectureId());
            LectureRecommendReason reason = LectureRecommendReason.POPULAR;
            String basisTitle = null;
            if (reasoned != null) {
                reason = reasoned.reason();
                LectureSnapshot basis = reasoned.basisLectureId() == null ? null : lectures.get(reasoned.basisLectureId());
                basisTitle = basis == null ? null : basis.title();
            }
            personalized |= !item.popular();
            items.add(new LectureRecommendation.Item(item.lectureId(), lectures.get(item.lectureId()).title(), item.score(),
                    reason, reason.sentence(basisTitle)));
        }
        return new LectureRecommendation(items, needsInterest, personalized);
    }

    private Map<Long, float[]> vectors(Collection<Long> lectureIds) {
        Map<Long, float[]> vectors = new HashMap<>();
        Map<String, float[]> stored = index.vectors(IndexType.LECTURE, lectureIds.stream().map(String::valueOf).toList());
        for (Map.Entry<String, float[]> entry : stored.entrySet()) {
            vectors.put(Long.parseLong(entry.getKey()), entry.getValue());
        }
        return vectors;
    }

    private LectureSignals.Weights weights() {
        ChatbotLectureRecommendProperties.Weights weights = properties.weights();
        return new LectureSignals.Weights(weights.owned(), weights.cart(), weights::rating);
    }

    // 강의 ID → 가중. source가 있으면 그 출처만
    private static Map<Long, Double> weightsById(List<LectureSignals.Signal> signals, LectureSignals.@Nullable Source source) {
        Map<Long, Double> weights = new LinkedHashMap<>();
        for (LectureSignals.Signal signal : signals) {
            if (source == null || signal.source() == source) {
                weights.put(signal.lectureId(), signal.weight());
            }
        }
        return weights;
    }

    private static Map<Long, LectureSnapshot> byId(List<LectureSnapshot> lectures) {
        Map<Long, LectureSnapshot> byId = new HashMap<>();
        for (LectureSnapshot lecture : lectures) {
            byId.put(lecture.id(), lecture);
        }
        return byId;
    }

    /** 고른 강의의 이유와 문장에 넣을 신호 강의 */
    private record Reasoned(LectureRecommendReason reason, @Nullable Long basisLectureId) {
    }
}

package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.FallbackReason;
import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.Feature;
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
 *     만들 수 없으면(신호도 관심사도 없음, 또는 신호 강의가 아직 색인 전) 인기순만 주고 {@code needsInterest}
 *     (관심사를 주면 맞춤으로 찾을 수 있다).</li>
 *     <li>kNN(판매 중·삭제 안 됨·원본에 있음 필터) 뒤 코드에서 뺀다: 구매 {@code OWNED}·{@code PENDING}, 장바구니, 본인 강의,
 *     숨김 판매자({@link MemberVisibility}), 사본으로 다시 본 추천 불가 상태(색인이 늦은 경우).</li>
 *     <li>{@link LectureRanker}: β 혼합, 동점 순서, 판매자당 상한, 인기순 채우기(인기순은 숨김 판매자를 SQL에서 거른다).</li>
 *     <li>이유 코드: 고른 강의마다 구매 중심·장바구니 중심·관심사 벡터 중 가장 가까운 쪽. 구매·장바구니면 가장 가까운 신호 강의 제목을
 *     문장에 넣는다(공개 정보인 강의 제목). 순위와 상관없이 강의마다 정하므로, 순위를 바꾸는 후처리가 들어와도 그대로 쓸 수 있다.</li>
 * </ol>
 * 색인·임베딩을 쓸 수 없으면(연결 없음·ES·임베딩 호출 실패) 관심사 없는 요청은 인기순({@code personalized=false})으로 답하고
 * fallback 지표로 세며, 관심사를 넣은 요청은 {@link ChatbotErrorCode#LECTURE_RECOMMEND_UNAVAILABLE}이다(관심사를 무시한 결과를
 * 맞춤처럼 보이지 않게). DB 오류는 잡지 않는다. 같은 장애를 요청마다 로그로 남기지 않고, 호출 실패가 시작되거나 회복될 때만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LectureRecommendUseCase {

    /** 관심사 최대 글자 수(앞뒤 공백 제외). API 검증과 Tool이 함께 쓴다 */
    public static final int INTEREST_MAX_LENGTH = 100;

    private final LectureRecommendStore store;
    private final RecommendVectorIndex index;
    private final ChatbotEmbedder embedder;
    private final MemberVisibility visibility;
    private final ChatbotLectureRecommendProperties properties;
    private final ChatbotMetrics metrics;

    // ES·임베딩 호출이 실패하고 있는지(로그를 상태가 바뀔 때만 남기려고)
    private final AtomicBoolean failing = new AtomicBoolean();

    /**
     * @param interest 사용자가 직접 입력한 관심사 문장(앞뒤 공백 없음). 없으면 null(저장하지 않는다)
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
            return unavailable(memberId, excluded, interest, FallbackReason.MODEL_OFF, null);
        }
        LectureRecommendation result;
        try {
            result = personalized(memberId, interest, signals, excluded);
        } catch (SearchFailure e) {
            return unavailable(memberId, excluded, interest, FallbackReason.ERROR, e.getCause());
        }
        if (failing.compareAndSet(true, false)) {
            log.info("강의 추천 색인·임베딩 호출이 회복됐다");
        }
        return result;
    }

    private LectureRecommendation personalized(long memberId, @Nullable String interest, List<LectureSignals.Signal> signals,
            Set<Long> excluded) {
        Map<Long, float[]> signalVectors = search(() -> vectors(signals.stream().map(LectureSignals.Signal::lectureId).toList()));
        float[] interestVector = interest == null ? null : search(() -> embedder.embedQuery(interest));
        Taste taste = Taste.of(signals, signalVectors, interestVector);
        float[] query = taste.query(properties.interestWeight());
        if (query == null) {
            // 신호 강의가 아직 색인되지 않았다. 관심사를 주면 맞춤으로 찾을 수 있다
            return popularOnly(memberId, excluded, true);
        }

        List<RecommendVectorIndex.ScoredId> hits = search(() -> index.knn(IndexType.LECTURE, query, properties.knnK(),
                properties.numCandidates(), LectureIndexDocument.RECOMMENDABLE));
        Map<Long, Double> similarity = new LinkedHashMap<>();
        for (RecommendVectorIndex.ScoredId hit : hits) {
            similarity.put(LectureIndexDocument.lectureId(hit.id()), hit.score());
        }
        List<LectureRanker.Ranked> ranked = LectureRanker.rank(candidates(memberId, similarity, excluded),
                store.popular(memberId, excluded, properties.popularPoolSize()), properties.popularityWeight(),
                properties.resultSize(), properties.maxPerSeller());
        return toRecommendation(ranked, reasons(ranked, taste), false);
    }

    // 코드에서 빼는 규칙. kNN 필터를 지나도 사본으로 다시 본다(색인이 늦게 따라온 경우)
    private List<LectureRanker.Candidate> candidates(long memberId, Map<Long, Double> similarity, Set<Long> excluded) {
        Map<Long, LectureSnapshot> lectures = byId(store.lectures(similarity.keySet()));
        Set<Long> sellers = new HashSet<>();
        for (LectureSnapshot lecture : lectures.values()) {
            sellers.add(lecture.sellerId());
        }
        Set<Long> visibleSellers = visibility.visibleOf(sellers);
        List<Long> kept = new ArrayList<>();
        for (Long lectureId : similarity.keySet()) {
            LectureSnapshot lecture = lectures.get(lectureId);
            if (lecture != null && lecture.recommendable() && !excluded.contains(lectureId) && lecture.sellerId() != memberId
                    && visibleSellers.contains(lecture.sellerId())) {
                kept.add(lectureId);
            }
        }
        Map<Long, Long> owned = store.ownedCounts(kept);
        List<LectureRanker.Candidate> candidates = new ArrayList<>();
        for (Long lectureId : kept) {
            candidates.add(new LectureRanker.Candidate(lectureId, lectures.get(lectureId).sellerId(), similarity.get(lectureId),
                    owned.get(lectureId)));
        }
        return candidates;
    }

    private LectureRecommendation popularOnly(long memberId, Set<Long> excluded, boolean needsInterest) {
        List<LectureRanker.Ranked> ranked = LectureRanker.rank(List.of(),
                store.popular(memberId, excluded, properties.popularPoolSize()), properties.popularityWeight(),
                properties.resultSize(), properties.maxPerSeller());
        return toRecommendation(ranked, Map.of(), needsInterest);
    }

    // 503은 공통 예외 처리기가 남긴다. 여기서는 호출 실패가 시작될 때만 남긴다
    private LectureRecommendation unavailable(long memberId, Set<Long> excluded, @Nullable String interest,
            FallbackReason reason, @Nullable Throwable cause) {
        if (cause != null && failing.compareAndSet(false, true)) {
            log.warn("강의 추천 색인·임베딩 호출이 실패하기 시작했다. 회복될 때까지 관심사 요청은 거절하고 나머지는 인기순으로 답한다",
                    cause);
        }
        if (interest != null) {
            throw cause == null ? new ChatbotException(ChatbotErrorCode.LECTURE_RECOMMEND_UNAVAILABLE)
                    : new ChatbotException(ChatbotErrorCode.LECTURE_RECOMMEND_UNAVAILABLE, cause);
        }
        metrics.fallback(Feature.LECTURE_RECOMMEND, reason);
        return popularOnly(memberId, excluded, false);
    }

    // 고른 강의(인기순 채움 제외)마다 이유
    private Map<Long, Reasoned> reasons(List<LectureRanker.Ranked> ranked, Taste taste) {
        List<Long> ids = ranked.stream().filter(item -> !item.popular()).map(LectureRanker.Ranked::lectureId).toList();
        Map<Long, float[]> itemVectors = search(() -> vectors(ids));
        Map<Long, Reasoned> reasons = new HashMap<>();
        for (Long id : ids) {
            reasons.put(id, taste.reason(itemVectors.get(id)));
        }
        return reasons;
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
            Reasoned reasoned = item.popular() ? Reasoned.POPULAR : reasons.get(item.lectureId());
            LectureSnapshot basis = reasoned.basisLectureId() == null ? null : lectures.get(reasoned.basisLectureId());
            personalized |= !item.popular();
            items.add(new LectureRecommendation.Item(item.lectureId(), lectures.get(item.lectureId()).title(), item.score(),
                    reasoned.reason(), reasoned.reason().sentence(basis == null ? null : basis.title())));
        }
        return new LectureRecommendation(items, needsInterest, personalized);
    }

    private Map<Long, float[]> vectors(Collection<Long> lectureIds) {
        Map<Long, float[]> vectors = new HashMap<>();
        Map<String, float[]> stored = index.vectors(IndexType.LECTURE,
                lectureIds.stream().map(LectureIndexDocument::docId).toList());
        for (Map.Entry<String, float[]> entry : stored.entrySet()) {
            vectors.put(LectureIndexDocument.lectureId(entry.getKey()), entry.getValue());
        }
        return vectors;
    }

    // ES·임베딩 호출만 감싼다. 실패를 SearchFailure로 바꿔 DB 오류와 구분한다
    private static <T> T search(Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            throw new SearchFailure(e);
        }
    }

    private LectureSignals.Weights weights() {
        ChatbotLectureRecommendProperties.Weights weights = properties.weights();
        return new LectureSignals.Weights(weights.owned(), weights.cart(), weights::rating);
    }

    private static Map<Long, LectureSnapshot> byId(List<LectureSnapshot> lectures) {
        Map<Long, LectureSnapshot> byId = new HashMap<>();
        for (LectureSnapshot lecture : lectures) {
            byId.put(lecture.id(), lecture);
        }
        return byId;
    }

    /**
     * 한 요청의 취향: 신호, 신호 강의 벡터(색인된 것만), 출처별 중심 벡터, 관심사 벡터.
     */
    private record Taste(List<LectureSignals.Signal> signals, Map<Long, float[]> signalVectors,
            Map<LectureSignals.Source, float[]> centroids, float @Nullable [] interest) {

        static Taste of(List<LectureSignals.Signal> signals, Map<Long, float[]> signalVectors, float @Nullable [] interest) {
            Map<LectureSignals.Source, float[]> centroids = new HashMap<>();
            for (LectureSignals.Source source : LectureSignals.Source.values()) {
                float[] centroid = TasteVectors.weightedAverage(signalVectors, weightsById(signals, source));
                if (centroid != null) {
                    centroids.put(source, centroid);
                }
            }
            return new Taste(signals, signalVectors, centroids, interest);
        }

        // 질의 벡터. 신호와 관심사가 모두 있으면 섞고, 하나만 있으면 그것, 둘 다 없으면 null
        float @Nullable [] query(double interestWeight) {
            float[] signal = TasteVectors.weightedAverage(signalVectors, weightsById(signals, null));
            if (signal != null && interest != null) {
                return TasteVectors.mix(signal, interest, interestWeight);
            }
            return signal != null ? signal : interest;
        }

        // 가장 가까운 기준. 질의 벡터를 신호나 관심사로 만들었으므로 기준이 하나는 있다
        Reasoned reason(float @Nullable [] vector) {
            if (vector == null) {
                // kNN 뒤에 문서가 사라졌다(index 재생성 등). 거리 대신 신호 가중이 큰 출처로 정하고 기준 강의 제목은 넣지 않는다
                return heaviest();
            }
            LectureSignals.Source bestSource = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (Map.Entry<LectureSignals.Source, float[]> centroid : centroids.entrySet()) {
                double score = TasteVectors.cosine(vector, centroid.getValue());
                if (score > bestScore) {
                    bestScore = score;
                    bestSource = centroid.getKey();
                }
            }
            if (bestSource == null || interest != null && TasteVectors.cosine(vector, interest) > bestScore) {
                return Reasoned.INTEREST;
            }
            return new Reasoned(reasonOf(bestSource), closestSignal(vector, bestSource));
        }

        // 중심 벡터가 있는 출처 중 (색인된) 신호 가중 합이 큰 쪽. 없으면 관심사
        private Reasoned heaviest() {
            LectureSignals.Source heaviest = null;
            double heaviestWeight = Double.NEGATIVE_INFINITY;
            for (LectureSignals.Source source : centroids.keySet()) {
                double weight = 0;
                for (LectureSignals.Signal signal : signals) {
                    if (signal.source() == source && signalVectors.containsKey(signal.lectureId())) {
                        weight += signal.weight();
                    }
                }
                if (weight > heaviestWeight) {
                    heaviestWeight = weight;
                    heaviest = source;
                }
            }
            return heaviest == null ? Reasoned.INTEREST : new Reasoned(reasonOf(heaviest), null);
        }

        // 그 출처의 신호 강의 중 가장 가까운 강의
        private @Nullable Long closestSignal(float[] vector, LectureSignals.Source source) {
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

        private static LectureRecommendReason reasonOf(LectureSignals.Source source) {
            return source == LectureSignals.Source.PURCHASED ? LectureRecommendReason.SIMILAR_TO_PURCHASED
                    : LectureRecommendReason.SIMILAR_TO_CART;
        }

        // 강의 ID → 가중. source가 있으면 그 출처만
        private static Map<Long, Double> weightsById(List<LectureSignals.Signal> signals,
                LectureSignals.@Nullable Source source) {
            Map<Long, Double> weights = new LinkedHashMap<>();
            for (LectureSignals.Signal signal : signals) {
                if (source == null || signal.source() == source) {
                    weights.put(signal.lectureId(), signal.weight());
                }
            }
            return weights;
        }
    }

    /** 고른 강의의 이유와 문장에 넣을 신호 강의 */
    private record Reasoned(LectureRecommendReason reason, @Nullable Long basisLectureId) {

        static final Reasoned POPULAR = new Reasoned(LectureRecommendReason.POPULAR, null);
        static final Reasoned INTEREST = new Reasoned(LectureRecommendReason.MATCHES_INTEREST, null);
    }

    /** ES·임베딩 호출 실패(DB 오류와 구분) */
    private static final class SearchFailure extends RuntimeException {

        SearchFailure(RuntimeException cause) {
            super(cause);
        }
    }
}

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

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.Feature;
import com.hapbang.auxiliary.chatbot.config.ChatbotLectureRecommendProperties;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.LectureRanker;
import com.hapbang.auxiliary.chatbot.domain.LectureSignals;
import com.hapbang.auxiliary.chatbot.domain.LectureSnapshot;
import com.hapbang.auxiliary.chatbot.domain.LectureTaste;
import com.hapbang.auxiliary.chatbot.domain.RecommendBasis;
import com.hapbang.auxiliary.chatbot.out.LectureRecommendStore;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 강의 추천 후보 생성 (#718, #720). <b>후보는 코드가 정한다</b>(같은 입력이면 같은 결과). LLM을 부르지 않는다.
 * 두 단계다: {@link #pool}이 맞춤 후보만 고르고, {@link #complete}가 고른 순서대로 모자란 자리를 인기순으로 채워 답을 만든다.
 * 위젯·Tool은 {@link #recommend}(풀 크기 = 추천 수), 버튼 경로는 큰 풀을 받아 LLM이 고른 뒤 {@link #complete}를 부른다.
 * <ol>
 *     <li>요청 회원이 숨김(사본 없음·탈퇴·마스킹)이면 빈 결과.</li>
 *     <li>신호: 구매 {@code OWNED}·장바구니에 리뷰 별점 보정, 최근 순 상한, 0 아래로 내리지 않음({@link LectureSignals}).</li>
 *     <li>질의 벡터: 신호 강의 벡터의 가중 평균. 관심사가 함께 있으면 {@code (1−α)·신호 + α·관심사}, 신호가 없으면 관심사만.
 *     만들 수 없으면(신호도 관심사도 없음, 또는 신호 강의가 아직 색인 전) 풀은 비고 {@code needsInterest}
 *     (관심사를 주면 맞춤으로 찾을 수 있다. 모델·ES를 끈 실행이면 끈다).</li>
 *     <li>kNN(판매 중·삭제 안 됨·원본에 있음 필터) 뒤 코드에서 뺀다: 구매 {@code OWNED}·{@code PENDING}, 장바구니, 본인 강의,
 *     숨김 판매자({@link MemberVisibility}), 사본으로 다시 본 추천 불가 상태(색인이 늦은 경우).</li>
 *     <li>{@link LectureRanker}: β 혼합, 동점 순서, 판매자당 상한으로 풀 크기까지.</li>
 *     <li>이유 코드: 고른 강의마다 구매 중심·장바구니 중심·관심사 벡터 중 가장 가까운 쪽({@link LectureTaste}). 구매·장바구니면
 *     가장 가까운 신호 강의 제목을 문장에 넣는다(회원 본인 화면용. 대화 Tool은 제목을 빼고 준다, {@link LectureRecommendTools}).</li>
 *     <li>인기순 채우기: 맞춤 후보가 모자랄 때만 읽는다. {@link PopularLectureCache}의 상위 목록에서 회원별 제외를 하고, 고른 강의를
 *     ID로 다시 읽어 판매 중·판매자 보임을 확인한다. 캐시 뒤 상태가 바뀌었거나 제외 뒤 모자라면 인기순 쿼리를 직접 돈다.</li>
 * </ol>
 * 검색(ES·임베딩 호출)이 실패하면 모든 경로에서 인기순으로 답하고 근거는 {@link RecommendBasis#POPULAR_TEMPORARY}다
 * (지표 {@code hapbang.chatbot.recommend.search.errors}). 모델·ES를 끈 실행은 설정이라 {@link RecommendBasis#POPULAR}다.
 * DB 오류는 잡지 않는다. 같은 장애를 요청마다 로그로 남기지 않고, 호출 실패가 시작되거나 회복될 때만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LectureRecommendUseCase {

    private final LectureRecommendStore store;
    private final RecommendVectorIndex index;
    private final ChatbotEmbedder embedder;
    private final MemberVisibility visibility;
    private final PopularLectureCache popularCache;
    private final ChatbotLectureRecommendProperties properties;
    private final ChatbotMetrics metrics;

    // ES·임베딩 호출이 실패하고 있는지(로그를 상태가 바뀔 때만 남기려고)
    private final AtomicBoolean failing = new AtomicBoolean();

    /**
     * 위젯·Tool 경로: 맞춤 후보를 추천 수만큼 고르고 모자라면 인기순으로 채운다.
     *
     * @param interest 사용자가 직접 입력한 관심사 문장({@link RecommendInterest#normalize}를 거친 값). 없으면 null(저장하지 않는다)
     */
    public LectureRecommendation recommend(long memberId, @Nullable String interest) {
        Pool pool = pool(memberId, interest == null ? null : List.of(interest), properties.resultSize());
        return complete(pool, pool.lectureIds());
    }

    /**
     * 맞춤 후보만 최대 {@code size}개(인기순 채움 없음). 제외 규칙과 판매자당 상한은 여기서 적용하므로 풀에서 어떤 강의를 골라도
     * 규칙을 지킨다.
     *
     * @param interest 관심사 원문(첫째)과 쿼리 확장 표현. 함께 임베딩해 평균을 낸다. 없으면 null
     */
    public Pool pool(long memberId, @Nullable List<String> interest, int size) {
        if (!visibility.isVisible(memberId)) {
            return Pool.forHiddenRequester(memberId);
        }
        Set<Long> excluded = store.excludedLectures(memberId);
        List<LectureSignals.Signal> signals = LectureSignals.weigh(store.activities(memberId), store.ratings(memberId),
                properties.signalLimit(), weights());
        boolean vectorReady = index.available() && embedder.available();
        if (signals.isEmpty() && interest == null) {
            // 맞춤으로 고를 재료가 없어 원래 인기순이다. 관심사를 쓸 수 없는 실행(모델·ES 연결 없음)이면 물어보지 않는다
            return Pool.empty(memberId, excluded, RecommendBasis.POPULAR, vectorReady);
        }
        if (!vectorReady) {
            return Pool.empty(memberId, excluded, RecommendBasis.POPULAR, false);
        }
        Pool pool;
        try {
            pool = personalized(memberId, interest, signals, excluded, size);
        } catch (VectorCall.Failure e) {
            if (failing.compareAndSet(false, true)) {
                log.warn("강의 추천 색인·임베딩 호출이 실패하기 시작했다. 회복될 때까지 인기순으로 답한다", e.getCause());
            }
            metrics.recommendSearchError(Feature.LECTURE_RECOMMEND);
            return Pool.empty(memberId, excluded, RecommendBasis.POPULAR_TEMPORARY, false);
        }
        if (failing.compareAndSet(true, false)) {
            log.info("강의 추천 색인·임베딩 호출이 회복됐다");
        }
        return pool;
    }

    /**
     * 풀에서 고른 강의를 그 순서대로 두고, 추천 수에 모자라면 인기순으로 채워 답을 만든다.
     *
     * @param chosen 풀의 강의 ID(고른 순서). 풀에 없는 ID와 중복은 버린다
     */
    public LectureRecommendation complete(Pool pool, List<Long> chosen) {
        if (pool.requesterHidden()) {
            return LectureRecommendation.forHiddenRequester();
        }
        Map<Long, LectureRanker.Ranked> byId = new LinkedHashMap<>();
        for (LectureRanker.Ranked ranked : pool.ranked()) {
            byId.put(ranked.lectureId(), ranked);
        }
        List<LectureRanker.Ranked> picked = new ArrayList<>();
        for (Long id : chosen) {
            LectureRanker.Ranked ranked = byId.remove(id);
            if (ranked != null && picked.size() < properties.resultSize()) {
                picked.add(ranked);
            }
        }
        return toRecommendation(fillPopular(pool, picked), pool.reasons(), pool.needsInterest(), pool.basis());
    }

    private Pool personalized(long memberId, @Nullable List<String> interest, List<LectureSignals.Signal> signals,
            Set<Long> excluded, int size) {
        Map<Long, float[]> signalVectors =
                VectorCall.run(() -> vectors(signals.stream().map(LectureSignals.Signal::lectureId).toList()));
        float[] interestVector = interest == null ? null : VectorCall.run(() -> embedder.embedInterest(interest));
        LectureTaste taste = LectureTaste.of(signals, signalVectors, interestVector);
        float[] query = taste.query(properties.interestWeight());
        if (query == null) {
            // 신호 강의가 아직 색인되지 않았다. 관심사를 주면 맞춤으로 찾을 수 있다
            return Pool.empty(memberId, excluded, RecommendBasis.POPULAR, true);
        }

        List<RecommendVectorIndex.ScoredId> hits = VectorCall.run(() -> index.knn(IndexType.LECTURE, query, properties.knnK(),
                properties.numCandidates(), LectureIndexDocument.RECOMMENDABLE));
        Map<Long, Double> similarity = new LinkedHashMap<>();
        for (RecommendVectorIndex.ScoredId hit : hits) {
            similarity.put(LectureIndexDocument.lectureId(hit.id()), hit.score());
        }
        List<LectureRanker.Ranked> ranked = LectureRanker.rank(candidates(memberId, similarity, excluded),
                properties.popularityWeight(), size, properties.maxPerSeller());
        if (ranked.isEmpty()) {
            // 맞춤 후보가 제외 규칙으로 모두 빠졌다. 관심사를 바꿔도 같을 수 있어 묻지 않는다
            return Pool.empty(memberId, excluded, RecommendBasis.POPULAR, false);
        }
        return new Pool(memberId, excluded, ranked, reasons(ranked, taste), RecommendBasis.PERSONALIZED, false, false);
    }

    // 코드에서 빼는 규칙. kNN 필터를 지나도 사본으로 다시 본다(색인이 늦게 따라온 경우)
    private List<LectureRanker.Candidate> candidates(long memberId, Map<Long, Double> similarity, Set<Long> excluded) {
        Map<Long, LectureSnapshot> lectures = store.lectures(similarity.keySet());
        Set<Long> visibleSellers = visibleSellers(lectures.values());
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

    // 모자랄 때만 인기순을 읽는다. 캐시로 채운 강의는 ID로 다시 읽어 판매 중·판매자 보임을 확인하고,
    // 하나라도 바뀌었거나 캐시로 다 채우지 못하면 인기순 쿼리를 직접 돈다(캐시는 상위 일부만 들고 있다)
    private List<LectureRanker.Ranked> fillPopular(Pool pool, List<LectureRanker.Ranked> picked) {
        int size = properties.resultSize();
        if (picked.size() >= size) {
            return picked;
        }
        List<LectureRanker.Popular> cached = new ArrayList<>();
        for (LectureRanker.Popular item : popularCache.top()) {
            if (item.sellerId() != pool.memberId() && !pool.excluded().contains(item.lectureId())) {
                cached.add(item);
            }
        }
        List<LectureRanker.Ranked> filled = LectureRanker.fill(picked, cached, size, properties.maxPerSeller());
        if (filled.size() >= size && stillRecommendable(filled)) {
            return filled;
        }
        return LectureRanker.fill(picked, store.popular(pool.memberId(), pool.excluded(), properties.popularPoolSize()), size,
                properties.maxPerSeller());
    }

    // 인기순으로 채운 강의가 지금도 판매 중이고 판매자가 보이는지
    private boolean stillRecommendable(List<LectureRanker.Ranked> filled) {
        List<Long> ids = filled.stream().filter(LectureRanker.Ranked::popular).map(LectureRanker.Ranked::lectureId).toList();
        Map<Long, LectureSnapshot> lectures = store.lectures(ids);
        Set<Long> visibleSellers = visibleSellers(lectures.values());
        for (Long id : ids) {
            LectureSnapshot lecture = lectures.get(id);
            if (lecture == null || !lecture.recommendable() || !visibleSellers.contains(lecture.sellerId())) {
                return false;
            }
        }
        return true;
    }

    private Set<Long> visibleSellers(Collection<LectureSnapshot> lectures) {
        Set<Long> sellers = new HashSet<>();
        for (LectureSnapshot lecture : lectures) {
            sellers.add(lecture.sellerId());
        }
        return visibility.visibleOf(sellers);
    }

    // 고른 강의(인기순 채움 제외)마다 이유
    private Map<Long, LectureTaste.Reasoned> reasons(List<LectureRanker.Ranked> ranked, LectureTaste taste) {
        List<Long> ids = ranked.stream().map(LectureRanker.Ranked::lectureId).toList();
        Map<Long, float[]> itemVectors = VectorCall.run(() -> vectors(ids));
        Map<Long, LectureTaste.Reasoned> reasons = new HashMap<>();
        for (Long id : ids) {
            reasons.put(id, taste.reason(itemVectors.get(id)));
        }
        return reasons;
    }

    private LectureRecommendation toRecommendation(List<LectureRanker.Ranked> ranked,
            Map<Long, LectureTaste.Reasoned> reasons, boolean needsInterest, RecommendBasis basis) {
        Set<Long> titleIds = new HashSet<>();
        for (LectureRanker.Ranked item : ranked) {
            titleIds.add(item.lectureId());
        }
        for (LectureTaste.Reasoned reasoned : reasons.values()) {
            if (reasoned.basisLectureId() != null) {
                titleIds.add(reasoned.basisLectureId());
            }
        }
        Map<Long, LectureSnapshot> lectures = store.lectures(titleIds);
        List<LectureRecommendation.Item> items = new ArrayList<>();
        for (LectureRanker.Ranked item : ranked) {
            LectureTaste.Reasoned reasoned = item.popular() ? LectureTaste.Reasoned.POPULAR : reasons.get(item.lectureId());
            LectureSnapshot basisLecture = reasoned.basisLectureId() == null ? null : lectures.get(reasoned.basisLectureId());
            items.add(new LectureRecommendation.Item(item.lectureId(), lectures.get(item.lectureId()).title(), item.score(),
                    reasoned.reason(), reasoned.reason().sentence(basisLecture == null ? null : basisLecture.title())));
        }
        return new LectureRecommendation(items, needsInterest, basis, false);
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

    private LectureSignals.Weights weights() {
        ChatbotLectureRecommendProperties.Weights weights = properties.weights();
        return new LectureSignals.Weights(weights.owned(), weights.cart(), weights::rating);
    }

    /**
     * 맞춤 후보 풀. 인기순 채움이 없다. 버튼 경로는 이 풀을 LLM에 보여 주고 고른 순서로 {@link #complete}를 부른다.
     *
     * @param excluded        회원별로 뺄 강의(보유·결제 중·장바구니). 인기순으로 채울 때 다시 쓴다
     * @param ranked          점수 순 맞춤 후보
     * @param reasons         후보마다 이유 코드와 기준 강의
     * @param needsInterest   맞춤으로 고를 수 없고 관심사를 주면 찾을 수 있다
     * @param requesterHidden 요청 회원이 숨김이라 추천하지 않는다
     */
    public record Pool(long memberId, Set<Long> excluded, List<LectureRanker.Ranked> ranked,
            Map<Long, LectureTaste.Reasoned> reasons, RecommendBasis basis, boolean needsInterest, boolean requesterHidden) {

        public Pool {
            excluded = Set.copyOf(excluded);
            ranked = List.copyOf(ranked);
            reasons = Map.copyOf(reasons);
        }

        static Pool empty(long memberId, Set<Long> excluded, RecommendBasis basis, boolean needsInterest) {
            return new Pool(memberId, excluded, List.of(), Map.of(), basis, needsInterest, false);
        }

        static Pool forHiddenRequester(long memberId) {
            return new Pool(memberId, Set.of(), List.of(), Map.of(), RecommendBasis.POPULAR, false, true);
        }

        /** 풀의 강의 ID(점수 순) */
        public List<Long> lectureIds() {
            return ranked.stream().map(LectureRanker.Ranked::lectureId).toList();
        }
    }
}

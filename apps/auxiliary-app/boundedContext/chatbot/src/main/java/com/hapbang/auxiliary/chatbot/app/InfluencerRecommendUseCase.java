package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.Feature;
import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.InfluencerPopularity;
import com.hapbang.auxiliary.chatbot.domain.InfluencerPopularity.Stats;
import com.hapbang.auxiliary.chatbot.domain.InfluencerProfile;
import com.hapbang.auxiliary.chatbot.domain.InfluencerReason;
import com.hapbang.auxiliary.chatbot.domain.RecommendBasis;
import com.hapbang.auxiliary.chatbot.domain.TasteVectors;
import com.hapbang.auxiliary.chatbot.out.InfluencerRecommendStore;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex.ScoredId;

import lombok.extern.slf4j.Slf4j;

/**
 * 인플루언서 추천 후보 (#719, #720). <b>후보 풀과 그 안의 순서는 코드가 정한다(같은 상태면 같은 결과).</b> 버튼 경로는 LLM이
 * 풀 안에서 다시 고르고 이유를 쓴다({@link InfluencerRecommendExplainUseCase}).
 * <p>취향 벡터:
 * <table>
 *     <tr><th>요청</th><th>취향 벡터</th><th>이유</th></tr>
 *     <tr><td>관심사 문장이 있음(자유 질문 Tool)</td><td>관심사 문장</td><td>{@code MATCHES_INTEREST}</td></tr>
 *     <tr><td>인플루언서이고 내 프로필 문서가 있음</td><td>내 벡터와 함께 마친 상대들 평균을 섞음(상대 쪽 {@code partner-weight})</td>
 *     <td>후보마다 더 가까운 쪽: {@code SIMILAR_CATEGORY} 또는 {@code SIMILAR_TO_PARTNERS}</td></tr>
 *     <tr><td>인플루언서인데 프로필이 비어 있음</td><td>함께 마친 상대들 평균</td><td>{@code SIMILAR_TO_PARTNERS}</td></tr>
 *     <tr><td>위가 모두 없음(일반 회원 포함)</td><td>없음: 인기순만, {@code needsInterest=true}</td><td>{@code POPULAR}</td></tr>
 * </table>
 * <ul>
 *     <li>함께 마친 상대 중 추천 불가(숨김·정지·마스킹) 회원의 벡터는 평균에서 뺀다.</li>
 *     <li>검색: kNN({@code hidden=false}) → 본인·추천 불가(DB로 다시 확인, 색인이 늦어도 안전) 제외 →
 *     {@code (1 − β)·유사도 + β·정규화 인기}(후보 안 min-max). 동점은 회원 ID 내림차순.</li>
 *     <li>이미 함께 출연한 상대는 빼지 않는다(관계가 일회성이 아님). {@code PAST_PARTNER}를 붙인다.</li>
 *     <li>모자라면 인기순으로 채워 {@code size}명을 맞춘다(추천 가능한 회원이 그보다 적으면 그만큼).</li>
 *     <li>요청 회원이 숨김({@link MemberVisibility}: 사본 없음·탈퇴·마스킹)이면 빈 결과이고 {@code requesterHidden=true}다.
 *     사본이 없는 것은 가입 직후 동기화 전이라 화면은 정해진 안내 문구를 띄운다(#717 규칙을 강의 추천과 같이 따른다).</li>
 *     <li>임베딩·ES 호출이 실패하면 인기순으로 답하고 근거는 {@link RecommendBasis#POPULAR_TEMPORARY}다(지표
 *     {@code hapbang.chatbot.recommend.search.errors}). 모델·ES를 끈 실행은 설정이라 {@link RecommendBasis#POPULAR}다.
 *     두 경우 모두 {@code needsInterest}는 false다(입력이 없어서가 아니다).</li>
 *     <li>두 단계다: {@link #pool}이 맞춤 후보만 고르고, {@link #complete}가 고른 순서대로 모자란 자리를 인기순으로 채운다.</li>
 * </ul>
 */
@Slf4j
@Service
public class InfluencerRecommendUseCase {

    private static final Map<String, Object> NOT_HIDDEN = Map.of(InfluencerIndexPlan.HIDDEN, false);

    private final InfluencerRecommendStore store;
    private final MemberVisibility memberVisibility;
    private final RecommendVectorIndex index;
    private final ChatbotEmbedder embedder;
    private final ChatbotInfluencerRecommendProperties properties;
    private final InfluencerPopularity popularity;
    private final ChatbotMetrics metrics;
    // 검색(임베딩·ES) 장애 중인지. 로그는 상태가 바뀔 때만 남긴다
    private final AtomicBoolean searchFailing = new AtomicBoolean(false);

    InfluencerRecommendUseCase(InfluencerRecommendStore store, MemberVisibility memberVisibility, RecommendVectorIndex index,
            ChatbotEmbedder embedder, ChatbotInfluencerRecommendProperties properties, ChatbotMetrics metrics) {
        this.store = store;
        this.metrics = metrics;
        this.memberVisibility = memberVisibility;
        this.index = index;
        this.embedder = embedder;
        this.properties = properties;
        ChatbotInfluencerRecommendProperties.Popularity weights = properties.popularity();
        this.popularity = new InfluencerPopularity(weights.completedWeight(), weights.ratingWeight(), weights.priorReviews());
    }

    /**
     * 위젯·Tool 경로: 맞춤 후보를 {@code size}명까지 고르고 모자라면 인기순으로 채운다.
     *
     * @param interest 관심사 문장(자유 질문 Tool·버튼 경로만 넘긴다). 없으면 null
     */
    public InfluencerRecommendation recommend(long userId, @Nullable String interest) {
        Pool pool = pool(userId, interest == null ? null : List.of(interest), properties.size());
        return complete(pool, pool.memberIds());
    }

    /**
     * 맞춤 후보만 최대 {@code size}명(인기순 채움 없음). 본인·추천 불가 제외는 여기서 적용한다.
     *
     * @param interest 관심사 원문(첫째)과 쿼리 확장 표현. 함께 임베딩해 평균을 낸다. 없으면 null
     */
    public Pool pool(long userId, @Nullable List<String> interest, int size) {
        if (!memberVisibility.isVisible(userId)) {
            return Pool.forHiddenRequester(userId);
        }
        Set<Long> partners = store.completedPartners(userId);
        InfluencerProfile me = store.profiles(List.of(userId)).get(userId);
        // 리뷰 사본 전체를 훑는 값이라 요청마다 한 번만 센다(유사도 후보·인기순 채우기가 같이 쓴다)
        double overallMeanRating = store.overallMeanRating();
        if (!index.available() || !embedder.available()) {
            // 모델·ES를 끈 실행(설정). 다시 시도해도 같다
            return Pool.empty(userId, partners, overallMeanRating, RecommendBasis.POPULAR, false);
        }
        try {
            Taste taste = taste(userId, me, partners, interest);
            Pool pool;
            if (taste == null) {
                // 프로필은 있는데 아직 색인 전이면 입력이 없는 것이 아니다
                pool = Pool.empty(userId, partners, overallMeanRating, RecommendBasis.POPULAR, !hasOwnProfile(me));
            } else {
                List<Candidate> similar = similar(userId, taste, overallMeanRating, size);
                pool = similar.isEmpty() ? Pool.empty(userId, partners, overallMeanRating, RecommendBasis.POPULAR, false)
                        : new Pool(userId, partners, overallMeanRating, similar, RecommendBasis.PERSONALIZED, false, false);
            }
            if (searchFailing.compareAndSet(true, false)) {
                log.info("인플루언서 추천 검색이 다시 된다");
            }
            return pool;
        } catch (VectorCall.Failure e) {
            // 임베딩·ES 장애. 인기순으로 답한다. DB 오류·코드 오류는 감싸지 않아 그대로 올라간다
            if (searchFailing.compareAndSet(false, true)) {
                log.warn("인플루언서 추천 검색이 실패해 인기순으로 답한다. 다시 될 때까지 같은 실패는 남기지 않는다", e);
            }
            metrics.recommendSearchError(Feature.INFLUENCER_RECOMMEND);
            return Pool.empty(userId, partners, overallMeanRating, RecommendBasis.POPULAR_TEMPORARY, false);
        }
    }

    /** 풀의 후보에 분야를 채운다(버튼 경로의 LLM 프롬프트용). 함께 출연한 이력({@code PAST_PARTNER})은 붙이지 않는다 */
    public List<Candidate> describe(Pool pool) {
        Map<Long, InfluencerProfile> profiles = store.profiles(pool.memberIds());
        List<Candidate> described = new ArrayList<>();
        for (Candidate candidate : pool.candidates()) {
            InfluencerProfile profile = profiles.get(candidate.memberId());
            described.add(candidate.complete(profile == null ? List.of() : profile.categories(), false));
        }
        return described;
    }

    /**
     * 풀에서 고른 후보를 그 순서대로 두고, 추천 수에 모자라면 인기순으로 채워 답을 만든다.
     *
     * @param chosen 풀의 회원 ID(고른 순서). 풀에 없는 ID와 중복은 버린다
     */
    public InfluencerRecommendation complete(Pool pool, List<Long> chosen) {
        if (pool.requesterHidden()) {
            return InfluencerRecommendation.forHiddenRequester();
        }
        Map<Long, Candidate> byId = new LinkedHashMap<>();
        for (Candidate candidate : pool.candidates()) {
            byId.put(candidate.memberId(), candidate);
        }
        Map<Long, Candidate> picked = new LinkedHashMap<>();
        for (Long id : chosen) {
            Candidate candidate = byId.remove(id);
            if (candidate != null && picked.size() < properties.size()) {
                picked.put(id, candidate);
            }
        }
        fillPopular(pool.userId(), picked, pool.overallMeanRating());
        Map<Long, InfluencerProfile> profiles = store.profiles(picked.keySet());
        List<Candidate> items = new ArrayList<>();
        for (Candidate candidate : picked.values()) {
            InfluencerProfile profile = profiles.get(candidate.memberId());
            items.add(candidate.complete(profile == null ? List.of() : profile.categories(),
                    pool.partners().contains(candidate.memberId())));
        }
        return new InfluencerRecommendation(items, pool.needsInterest(), pool.basis(), false);
    }

    // 요청 회원의 취향 벡터. 만들 입력이 없으면 null
    private @Nullable Taste taste(long userId, @Nullable InfluencerProfile me, Set<Long> partners,
            @Nullable List<String> interest) {
        if (interest != null) {
            return new Taste.Single(VectorCall.run(() -> embedder.embedInterest(interest)), InfluencerReason.MATCHES_INTEREST);
        }
        if (me == null || !me.isInfluencer()) {
            return null;
        }
        String myDocId = InfluencerIndexPlan.docIdOf(userId);
        float[] mine = VectorCall.run(() -> index.vectors(IndexType.INFLUENCER, List.of(myDocId))).get(myDocId);
        float[] partnerMean = partnerMean(partners);
        if (mine != null && partnerMean != null) {
            float[] own = TasteVectors.normalize(mine);
            float[] others = TasteVectors.normalize(partnerMean);
            float[] mixed = TasteVectors.weightedSum(own, 1 - properties.partnerWeight(), others, properties.partnerWeight());
            return new Taste.Mixed(mixed, own, others);
        }
        if (mine != null) {
            return new Taste.Single(mine, InfluencerReason.SIMILAR_CATEGORY);
        }
        if (partnerMean != null) {
            return new Taste.Single(partnerMean, InfluencerReason.SIMILAR_TO_PARTNERS);
        }
        return null;
    }

    // 인플루언서이고 색인할 글이 있다(문서가 아직 없어도 개인 신호는 있다). 추천 가능 여부는 보지 않는다:
    // 정지된 인플루언서의 문서도 숨김일 뿐 벡터는 있어 취향으로 쓴다
    private static boolean hasOwnProfile(@Nullable InfluencerProfile me) {
        return me != null && me.isInfluencer() && InfluencerIndexPlan.hasText(me);
    }

    // 추천 가능한 상대들의 벡터 평균. 없으면 null
    private float @Nullable [] partnerMean(Set<Long> partners) {
        List<String> docIds = InfluencerIndexPlan.docIdsOf(store.recommendableOf(partners));
        List<float[]> vectors = new ArrayList<>();
        for (float[] vector : VectorCall.run(() -> index.vectors(IndexType.INFLUENCER, docIds)).values()) {
            vectors.add(TasteVectors.normalize(vector));
        }
        return vectors.isEmpty() ? null : TasteVectors.mean(vectors);
    }

    // kNN → 본인·추천 불가 제외 → 유사도와 정규화 인기를 섞어 size명
    private List<Candidate> similar(long userId, Taste taste, double overallMeanRating, int size) {
        List<ScoredId> hits = VectorCall.run(() -> index.knn(IndexType.INFLUENCER, taste.vector(), properties.k(),
                properties.numCandidates(), NOT_HIDDEN));
        Map<Long, Double> similarity = new LinkedHashMap<>();
        for (ScoredId hit : hits) {
            long id = InfluencerIndexPlan.memberIdOf(hit.id());
            if (id != userId) {
                similarity.put(id, hit.score());
            }
        }
        similarity.keySet().retainAll(store.recommendableOf(similarity.keySet()));
        if (similarity.isEmpty()) {
            return List.of();
        }
        double beta = properties.popularityWeight();
        List<Candidate> top = rank(store.popularityStats(similarity.keySet()), overallMeanRating, size,
                (stats, normalizedPopularity) -> (1 - beta) * similarity.get(stats.memberId()) + beta * normalizedPopularity,
                EnumSet.noneOf(InfluencerReason.class));
        List<Long> topIds = new ArrayList<>();
        for (Candidate candidate : top) {
            topIds.add(candidate.memberId());
        }
        Map<String, float[]> vectors = taste instanceof Taste.Mixed
                ? VectorCall.run(() -> index.vectors(IndexType.INFLUENCER, InfluencerIndexPlan.docIdsOf(topIds))) : Map.of();
        List<Candidate> reasoned = new ArrayList<>();
        for (Candidate candidate : top) {
            float[] vector = vectors.get(InfluencerIndexPlan.docIdOf(candidate.memberId()));
            reasoned.add(candidate.withReason(taste.reasonFor(vector)));
        }
        return reasoned;
    }

    // 모자란 자리를 인기순으로 채운다. 점수는 남은 후보 안에서 정규화한 인기다
    private void fillPopular(long userId, Map<Long, Candidate> chosen, double overallMeanRating) {
        int missing = properties.size() - chosen.size();
        if (missing <= 0) {
            return;
        }
        List<Stats> pool = new ArrayList<>();
        for (Stats stats : store.popularityStatsOfRecommendable()) {
            if (stats.memberId() != userId && !chosen.containsKey(stats.memberId())) {
                pool.add(stats);
            }
        }
        for (Candidate candidate : rank(pool, overallMeanRating, missing,
                (stats, normalizedPopularity) -> normalizedPopularity,
                EnumSet.of(InfluencerReason.POPULAR))) {
            chosen.put(candidate.memberId(), candidate);
        }
    }

    // 후보 안에서 인기를 정규화하고 점수를 매겨 위에서 limit명. 동점은 회원 ID 내림차순
    private List<Candidate> rank(List<Stats> pool, double overallMeanRating, int limit, Scorer scorer,
            Set<InfluencerReason> reasons) {
        if (pool.isEmpty()) {
            return List.of();
        }
        Map<Long, Double> normalized = InfluencerPopularity.normalize(popularity.rawScores(pool, overallMeanRating));
        List<Candidate> ranked = new ArrayList<>();
        for (Stats stats : pool) {
            double score = scorer.score(stats, normalized.getOrDefault(stats.memberId(), 0.0));
            ranked.add(new Candidate(stats, List.of(), score, reasons));
        }
        ranked.sort(Candidate.ORDER);
        return ranked.subList(0, Math.min(limit, ranked.size()));
    }

    @FunctionalInterface
    private interface Scorer {
        double score(Stats stats, double normalizedPopularity);
    }

    /** 취향 벡터와, 후보마다 붙일 이유 */
    private sealed interface Taste {

        float[] vector();

        /** @param candidate 후보의 저장된 벡터(못 읽었으면 null) */
        InfluencerReason reasonFor(float @Nullable [] candidate);

        /** 한 가지 신호. 모든 후보에 같은 이유 */
        record Single(float[] vector, InfluencerReason reason) implements Taste {

            @Override
            public InfluencerReason reasonFor(float @Nullable [] candidate) {
                return reason;
            }
        }

        /**
         * 내 프로필과 함께 마친 상대 평균을 섞은 취향. 후보마다 더 가까운 쪽이 이유다(같거나 벡터를 못 읽으면 내 분야).
         *
         * @param own    내 프로필 쪽(정규화)
         * @param others 상대 평균 쪽(정규화)
         */
        record Mixed(float[] vector, float[] own, float[] others) implements Taste {

            @Override
            public InfluencerReason reasonFor(float @Nullable [] candidate) {
                if (candidate == null) {
                    return InfluencerReason.SIMILAR_CATEGORY;
                }
                float[] normalized = TasteVectors.normalize(candidate);
                return TasteVectors.dot(normalized, others) > TasteVectors.dot(normalized, own) ? InfluencerReason.SIMILAR_TO_PARTNERS
                        : InfluencerReason.SIMILAR_CATEGORY;
            }
        }
    }

    /**
     * 추천 결과.
     *
     * @param needsInterest   개인 신호(내 프로필·함께 마친 상대)가 없어 인기순으로만 골랐다. 대화 화면이 관심사를 물어볼 수 있다
     * @param basis           후보의 근거. 검색이 잠시 실패해 인기순으로 대신 답했으면 {@link RecommendBasis#POPULAR_TEMPORARY}
     * @param requesterHidden 요청 회원이 숨김이라 추천하지 않았다(items는 비어 있다)
     */
    public record InfluencerRecommendation(List<Candidate> items, boolean needsInterest, RecommendBasis basis,
            boolean requesterHidden) {

        static InfluencerRecommendation forHiddenRequester() {
            return new InfluencerRecommendation(List.of(), false, RecommendBasis.POPULAR, true);
        }
    }

    /**
     * 맞춤 후보 풀. 인기순 채움이 없다. 버튼 경로는 이 풀을 LLM에 보여 주고 고른 순서로 {@link #complete}를 부른다.
     *
     * @param partners          함께 출연을 마친 상대(이유 {@code PAST_PARTNER})
     * @param overallMeanRating 인기순으로 채울 때 쓰는 전체 평균 별점(요청마다 한 번 센다)
     * @param candidates        점수 순 맞춤 후보
     */
    public record Pool(long userId, Set<Long> partners, double overallMeanRating, List<Candidate> candidates,
            RecommendBasis basis, boolean needsInterest, boolean requesterHidden) {

        public Pool {
            partners = Set.copyOf(partners);
            candidates = List.copyOf(candidates);
        }

        static Pool empty(long userId, Set<Long> partners, double overallMeanRating, RecommendBasis basis,
                boolean needsInterest) {
            return new Pool(userId, partners, overallMeanRating, List.of(), basis, needsInterest, false);
        }

        static Pool forHiddenRequester(long userId) {
            return new Pool(userId, Set.of(), 0, List.of(), RecommendBasis.POPULAR, false, true);
        }

        /** 풀의 회원 ID(점수 순) */
        public List<Long> memberIds() {
            return candidates.stream().map(Candidate::memberId).toList();
        }
    }

    /**
     * 추천 후보 한 명. 회원 정보는 사본에서 센 값만 담는다(닉네임·자기소개 없음).
     *
     * @param stats      이 후보가 완료한 출연 수(누구와든)·받은 리뷰(원본 없음 제외)
     * @param categories 분야 코드(회원이 고른 순서)
     * @param score      정렬 점수(클수록 앞)
     */
    public record Candidate(
            Stats stats,
            List<String> categories,
            double score,
            Set<InfluencerReason> reasons
    ) {

        /** 점수 내림차순, 동점은 회원 ID 내림차순 */
        static final Comparator<Candidate> ORDER = Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparing(Comparator.comparingLong(Candidate::memberId).reversed());

        public Candidate {
            categories = List.copyOf(categories);
            reasons = Set.copyOf(reasons);
        }

        public long memberId() {
            return stats.memberId();
        }

        public long completedAppearances() {
            return stats.completed();
        }

        /** 받은 리뷰의 실제 평균 별점(소수 첫째 자리). 리뷰가 없으면 null */
        public @Nullable Double averageRating() {
            if (stats.reviewCount() == 0) {
                return null;
            }
            return Math.round(stats.ratingSum() * 10.0 / stats.reviewCount()) / 10.0;
        }

        /** 이유 코드를 정해진 순서(enum 순서)로 */
        public List<InfluencerReason> orderedReasons() {
            List<InfluencerReason> ordered = new ArrayList<>();
            for (InfluencerReason reason : InfluencerReason.values()) {
                if (reasons.contains(reason)) {
                    ordered.add(reason);
                }
            }
            return ordered;
        }

        Candidate withReason(InfluencerReason reason) {
            Set<InfluencerReason> more = new HashSet<>(reasons);
            more.add(reason);
            return new Candidate(stats, categories, score, more);
        }

        // 분야를 채우고, 이미 함께 출연한 상대면 이유에 더한다(빼지 않는다)
        Candidate complete(List<String> memberCategories, boolean pastPartner) {
            Candidate completed = new Candidate(stats, memberCategories, score, reasons);
            return pastPartner ? completed.withReason(InfluencerReason.PAST_PARTNER) : completed;
        }
    }
}

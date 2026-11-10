package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.InfluencerPopularity;
import com.hapbang.auxiliary.chatbot.domain.InfluencerReason;
import com.hapbang.auxiliary.chatbot.out.InfluencerRecommendStore;
import com.hapbang.auxiliary.chatbot.out.InfluencerRecommendStore.Profile;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex.ScoredId;
import com.hapbang.shared.member.domain.Role;

import lombok.extern.slf4j.Slf4j;

/**
 * 인플루언서 추천 후보 (#719). <b>후보와 순서는 코드가 정한다(같은 상태면 같은 결과).</b> LLM은 고른 후보의 설명만 쓴다
 * ({@link InfluencerRecommendExplainer}).
 * <p>취향 벡터:
 * <table>
 *     <tr><th>요청</th><th>취향 벡터</th><th>이유</th></tr>
 *     <tr><td>관심사 문장이 있음(자유 질문 Tool)</td><td>관심사 문장</td><td>{@code MATCHES_INTEREST}</td></tr>
 *     <tr><td>인플루언서이고 내 프로필 문서가 있음</td><td>내 벡터와 함께 마친 상대들 평균을 섞음(상대 쪽 {@code partner-weight})</td>
 *     <td>{@code SIMILAR_CATEGORY}(+ {@code SIMILAR_TO_PARTNERS})</td></tr>
 *     <tr><td>인플루언서인데 프로필이 비어 있음</td><td>함께 마친 상대들 평균</td><td>{@code SIMILAR_TO_PARTNERS}</td></tr>
 *     <tr><td>위가 모두 없음(일반 회원 포함)</td><td>없음: 인기순만, {@code needsInterest=true}</td><td>{@code POPULAR}</td></tr>
 * </table>
 * <ul>
 *     <li>함께 마친 상대 중 추천 불가(숨김·정지·마스킹) 회원의 벡터는 평균에서 뺀다.</li>
 *     <li>검색: kNN({@code hidden=false}) → 본인·추천 불가(DB로 다시 확인, 색인이 늦어도 안전) 제외 →
 *     {@code (1 − β)·유사도 + β·정규화 인기}(후보 안 min-max). 동점은 회원 ID 내림차순.</li>
 *     <li>이미 함께 출연한 상대는 빼지 않는다(관계가 일회성이 아님). {@code PAST_PARTNER}를 붙인다.</li>
 *     <li>모자라면 인기순으로 채워 늘 {@code size}명을 맞춘다. 요청 회원이 숨김(탈퇴·마스킹)이면 빈 결과다.</li>
 *     <li>임베딩·ES를 쓸 수 없거나 실패하면 인기순으로 답한다(그때 {@code needsInterest}는 false: 입력이 없어서가 아니다).</li>
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

    InfluencerRecommendUseCase(InfluencerRecommendStore store, MemberVisibility memberVisibility, RecommendVectorIndex index,
            ChatbotEmbedder embedder, ChatbotInfluencerRecommendProperties properties) {
        this.store = store;
        this.memberVisibility = memberVisibility;
        this.index = index;
        this.embedder = embedder;
        this.properties = properties;
        ChatbotInfluencerRecommendProperties.Popularity weights = properties.popularity();
        this.popularity = new InfluencerPopularity(weights.completedWeight(), weights.ratingWeight(), weights.priorReviews());
    }

    /**
     * @param interest 관심사 문장(자유 질문 Tool만 넘긴다). 없으면 null
     */
    public InfluencerRecommendation recommend(long userId, @Nullable String interest) {
        if (!memberVisibility.isVisible(userId)) {
            return new InfluencerRecommendation(List.of(), false);
        }
        Set<Long> partners = store.completedPartners(userId);
        Map<Long, Candidate> chosen = new LinkedHashMap<>();
        boolean needsInterest = false;
        if (searchAvailable()) {
            try {
                Taste taste = taste(userId, partners, interest);
                if (taste == null) {
                    needsInterest = true;
                } else {
                    similar(userId, taste, chosen);
                }
            } catch (RuntimeException e) {
                // 임베딩·ES 장애. 인기순으로 답한다
                log.warn("인플루언서 추천 검색이 실패해 인기순으로 답한다", e);
                chosen.clear();
            }
        }
        fillPopular(userId, chosen);
        Map<Long, Profile> profiles = store.profiles(chosen.keySet());
        List<Candidate> items = new ArrayList<>();
        for (Candidate candidate : chosen.values()) {
            Profile profile = profiles.get(candidate.memberId());
            items.add(candidate.complete(profile == null ? List.of() : profile.categories(),
                    partners.contains(candidate.memberId())));
        }
        return new InfluencerRecommendation(items, needsInterest);
    }

    private boolean searchAvailable() {
        return index.available() && embedder.available();
    }

    // 요청 회원의 취향 벡터. 만들 입력이 없으면 null
    private @Nullable Taste taste(long userId, Set<Long> partners, @Nullable String interest) {
        if (interest != null) {
            return new Taste(embedder.embedQuery(interest), EnumSet.of(InfluencerReason.MATCHES_INTEREST));
        }
        Profile me = store.profiles(List.of(userId)).get(userId);
        if (me == null || (me.role() != Role.INFLUENCER && me.role() != Role.VERIFIED_INFLUENCER)) {
            return null;
        }
        float[] mine = index.vectors(IndexType.INFLUENCER, List.of(String.valueOf(userId))).get(String.valueOf(userId));
        float[] partnerMean = partnerMean(partners);
        if (mine != null && partnerMean != null) {
            float[] mixed = Vectors.weightedSum(Vectors.normalize(mine), 1 - properties.partnerWeight(),
                    Vectors.normalize(partnerMean), properties.partnerWeight());
            return new Taste(mixed, EnumSet.of(InfluencerReason.SIMILAR_CATEGORY, InfluencerReason.SIMILAR_TO_PARTNERS));
        }
        if (mine != null) {
            return new Taste(mine, EnumSet.of(InfluencerReason.SIMILAR_CATEGORY));
        }
        if (partnerMean != null) {
            return new Taste(partnerMean, EnumSet.of(InfluencerReason.SIMILAR_TO_PARTNERS));
        }
        return null;
    }

    // 추천 가능한 상대들의 벡터 평균. 없으면 null
    private float @Nullable [] partnerMean(Set<Long> partners) {
        Set<Long> recommendable = store.recommendableOf(partners);
        List<String> ids = new ArrayList<>();
        for (Long id : recommendable) {
            ids.add(String.valueOf(id));
        }
        List<float[]> vectors = new ArrayList<>();
        for (float[] vector : index.vectors(IndexType.INFLUENCER, ids).values()) {
            vectors.add(Vectors.normalize(vector));
        }
        return vectors.isEmpty() ? null : Vectors.mean(vectors);
    }

    // kNN → 본인·추천 불가 제외 → 유사도와 정규화 인기를 섞어 size명
    private void similar(long userId, Taste taste, Map<Long, Candidate> chosen) {
        List<ScoredId> hits = index.knn(IndexType.INFLUENCER, taste.vector(), properties.k(), properties.numCandidates(),
                NOT_HIDDEN);
        Map<Long, Double> similarity = new LinkedHashMap<>();
        for (ScoredId hit : hits) {
            long id = Long.parseLong(hit.id());
            if (id != userId) {
                similarity.put(id, hit.score());
            }
        }
        Set<Long> recommendable = store.recommendableOf(similarity.keySet());
        similarity.keySet().retainAll(recommendable);
        if (similarity.isEmpty()) {
            return;
        }
        List<InfluencerPopularity.Stats> stats = store.popularityStats(similarity.keySet());
        Map<Long, Double> normalized = InfluencerPopularity.normalize(
                popularity.rawScores(stats, store.overallMeanRating()));
        double beta = properties.popularityWeight();
        Map<Long, InfluencerPopularity.Stats> statsById = byId(stats);
        List<Candidate> ranked = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : similarity.entrySet()) {
            long id = entry.getKey();
            double score = (1 - beta) * entry.getValue() + beta * normalized.getOrDefault(id, 0.0);
            ranked.add(Candidate.of(statsById.get(id), score, taste.reasons()));
        }
        ranked.sort(Candidate.ORDER);
        for (Candidate candidate : ranked.subList(0, Math.min(properties.size(), ranked.size()))) {
            chosen.put(candidate.memberId(), candidate);
        }
    }

    // 모자란 자리를 인기순으로 채운다. 점수는 남은 후보 안에서 정규화한 인기다
    private void fillPopular(long userId, Map<Long, Candidate> chosen) {
        int missing = properties.size() - chosen.size();
        if (missing <= 0) {
            return;
        }
        List<Long> pool = new ArrayList<>();
        for (Long id : store.allRecommendable()) {
            if (id != userId && !chosen.containsKey(id)) {
                pool.add(id);
            }
        }
        if (pool.isEmpty()) {
            return;
        }
        List<InfluencerPopularity.Stats> stats = store.popularityStats(pool);
        Map<Long, Double> normalized = InfluencerPopularity.normalize(
                popularity.rawScores(stats, store.overallMeanRating()));
        List<Candidate> ranked = new ArrayList<>();
        for (InfluencerPopularity.Stats one : stats) {
            ranked.add(Candidate.of(one, normalized.getOrDefault(one.memberId(), 0.0), EnumSet.of(InfluencerReason.POPULAR)));
        }
        ranked.sort(Candidate.ORDER);
        for (Candidate candidate : ranked.subList(0, Math.min(missing, ranked.size()))) {
            chosen.put(candidate.memberId(), candidate);
        }
    }

    private static Map<Long, InfluencerPopularity.Stats> byId(List<InfluencerPopularity.Stats> stats) {
        Map<Long, InfluencerPopularity.Stats> byId = new HashMap<>();
        for (InfluencerPopularity.Stats one : stats) {
            byId.put(one.memberId(), one);
        }
        return byId;
    }

    private record Taste(float[] vector, Set<InfluencerReason> reasons) {
    }

    /**
     * 추천 결과.
     *
     * @param needsInterest 개인 신호(내 프로필·함께 마친 상대)가 없어 인기순으로만 골랐다. 대화 화면이 관심사를 물어볼 수 있다
     */
    public record InfluencerRecommendation(List<Candidate> items, boolean needsInterest) {
    }

    /**
     * 추천 후보 한 명. 회원 정보는 사본에서 센 값만 담는다(닉네임·자기소개 없음).
     *
     * @param categories           분야 코드(회원이 고른 순서)
     * @param completedAppearances 함께 마친 출연 수(원본 없음 제외)
     * @param reviewCount          받은 출연 리뷰 수
     * @param ratingSum            받은 출연 리뷰 별점 합
     * @param score                정렬 점수(0~1 근처, 클수록 앞)
     */
    public record Candidate(
            long memberId,
            List<String> categories,
            long completedAppearances,
            long reviewCount,
            long ratingSum,
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

        static Candidate of(InfluencerPopularity.Stats stats, double score, Set<InfluencerReason> reasons) {
            return new Candidate(stats.memberId(), List.of(), stats.completed(), stats.reviewCount(), stats.ratingSum(), score,
                    reasons);
        }

        /** 받은 리뷰의 실제 평균 별점(소수 첫째 자리). 리뷰가 없으면 null */
        public @Nullable Double averageRating() {
            if (reviewCount == 0) {
                return null;
            }
            return Math.round(ratingSum * 10.0 / reviewCount) / 10.0;
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

        // 분야를 채우고, 이미 함께 출연한 상대면 이유에 더한다(빼지 않는다)
        Candidate complete(List<String> memberCategories, boolean pastPartner) {
            Set<InfluencerReason> more = new HashSet<>(reasons);
            if (pastPartner) {
                more.add(InfluencerReason.PAST_PARTNER);
            }
            return new Candidate(memberId, memberCategories, completedAppearances, reviewCount, ratingSum, score, more);
        }
    }
}

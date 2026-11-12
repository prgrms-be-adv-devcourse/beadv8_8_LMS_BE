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

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.FallbackReason;
import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.Feature;
import com.hapbang.auxiliary.chatbot.config.ChatbotLectureRecommendProperties;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.LectureRanker;
import com.hapbang.auxiliary.chatbot.domain.LectureSignals;
import com.hapbang.auxiliary.chatbot.domain.LectureSnapshot;
import com.hapbang.auxiliary.chatbot.domain.LectureTaste;
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
 *     (관심사를 주면 맞춤으로 찾을 수 있다. 모델·ES 연결이 없는 실행이면 끈다).</li>
 *     <li>kNN(판매 중·삭제 안 됨·원본에 있음 필터) 뒤 코드에서 뺀다: 구매 {@code OWNED}·{@code PENDING}, 장바구니, 본인 강의,
 *     숨김 판매자({@link MemberVisibility}), 사본으로 다시 본 추천 불가 상태(색인이 늦은 경우).</li>
 *     <li>{@link LectureRanker}: β 혼합, 동점 순서, 판매자당 상한, 인기순 채우기(인기순은 숨김 판매자를 SQL에서 거른다).</li>
 *     <li>이유 코드: 고른 강의마다 구매 중심·장바구니 중심·관심사 벡터 중 가장 가까운 쪽({@link LectureTaste}). 구매·장바구니면
 *     가장 가까운 신호 강의 제목을 문장에 넣는다(회원 본인 화면용. 대화 Tool은 제목을 빼고 준다, {@link LectureRecommendTools}).</li>
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
     * 입력한 관심사를 추천에 넘길 값으로 바꾼다. API와 Tool이 함께 쓴다.
     *
     * @return 없거나 공백뿐이면 null, 아니면 앞뒤 공백을 뺀 값
     */
    public static @Nullable String normalizeInterest(@Nullable String interest) {
        return interest == null || interest.isBlank() ? null : interest.strip();
    }

    /**
     * @param interest 사용자가 직접 입력한 관심사 문장({@link #normalizeInterest}를 거친 값). 없으면 null(저장하지 않는다)
     */
    public LectureRecommendation recommend(long memberId, @Nullable String interest) {
        if (!visibility.isVisible(memberId)) {
            return LectureRecommendation.empty();
        }
        Set<Long> excluded = store.excludedLectures(memberId);
        List<LectureSignals.Signal> signals = LectureSignals.weigh(store.activities(memberId), store.ratings(memberId),
                properties.signalLimit(), weights());
        boolean vectorReady = index.available() && embedder.available();
        if (signals.isEmpty() && interest == null) {
            // 맞춤으로 고를 재료가 없어 원래 인기순이다(fallback이 아니다). 관심사를 쓸 수 없는 실행(모델·ES 연결 없음)이면
            // 관심사를 물어보지 않게 needsInterest를 끈다. 실행 중 호출 실패는 여기서 알 수 없다(관심사 요청이 503으로 알린다)
            return popularOnly(memberId, excluded, vectorReady);
        }
        if (!vectorReady) {
            return unavailable(memberId, excluded, interest, FallbackReason.MODEL_OFF, null);
        }
        LectureRecommendation result;
        try {
            result = personalized(memberId, interest, signals, excluded);
        } catch (VectorCall.Failure e) {
            return unavailable(memberId, excluded, interest, FallbackReason.ERROR, e.getCause());
        }
        if (failing.compareAndSet(true, false)) {
            log.info("강의 추천 색인·임베딩 호출이 회복됐다");
        }
        return result;
    }

    private LectureRecommendation personalized(long memberId, @Nullable String interest, List<LectureSignals.Signal> signals,
            Set<Long> excluded) {
        Map<Long, float[]> signalVectors =
                VectorCall.run(() -> vectors(signals.stream().map(LectureSignals.Signal::lectureId).toList()));
        float[] interestVector = interest == null ? null : VectorCall.run(() -> embedder.embedQuery(interest));
        LectureTaste taste = LectureTaste.of(signals, signalVectors, interestVector);
        float[] query = taste.query(properties.interestWeight());
        if (query == null) {
            // 신호 강의가 아직 색인되지 않았다. 관심사를 주면 맞춤으로 찾을 수 있다
            return popularOnly(memberId, excluded, true);
        }

        List<RecommendVectorIndex.ScoredId> hits = VectorCall.run(() -> index.knn(IndexType.LECTURE, query, properties.knnK(),
                properties.numCandidates(), LectureIndexDocument.RECOMMENDABLE));
        Map<Long, Double> similarity = new LinkedHashMap<>();
        for (RecommendVectorIndex.ScoredId hit : hits) {
            similarity.put(LectureIndexDocument.lectureId(hit.id()), hit.score());
        }
        List<LectureRanker.Ranked> ranked = rank(memberId, excluded, candidates(memberId, similarity, excluded));
        return toRecommendation(ranked, reasons(ranked, taste), false);
    }

    // 코드에서 빼는 규칙. kNN 필터를 지나도 사본으로 다시 본다(색인이 늦게 따라온 경우)
    private List<LectureRanker.Candidate> candidates(long memberId, Map<Long, Double> similarity, Set<Long> excluded) {
        Map<Long, LectureSnapshot> lectures = store.lectures(similarity.keySet());
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
        return toRecommendation(rank(memberId, excluded, List.of()), Map.of(), needsInterest);
    }

    // 정책값(β·추천 수·판매자당 상한)으로 고르고, 모자라면 인기순으로 채운다
    private List<LectureRanker.Ranked> rank(long memberId, Set<Long> excluded, List<LectureRanker.Candidate> candidates) {
        return LectureRanker.rank(candidates, store.popular(memberId, excluded, properties.popularPoolSize()),
                properties.popularityWeight(), properties.resultSize(), properties.maxPerSeller());
    }

    // 호출 실패가 시작될 때만 로그를 남긴다. 503은 잠시 뒤 다시 보내면 되는 거절이라 공통 예외 처리기도 요청마다 남기지 않는다
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
        LectureRecommendation fallback = popularOnly(memberId, excluded, false);
        // 인기순으로 대신 답한 것이 확정된 뒤에 센다(인기순 조회가 DB 오류로 실패하면 세지 않는다)
        metrics.fallback(Feature.LECTURE_RECOMMEND, reason);
        return fallback;
    }

    // 고른 강의(인기순 채움 제외)마다 이유
    private Map<Long, LectureTaste.Reasoned> reasons(List<LectureRanker.Ranked> ranked, LectureTaste taste) {
        List<Long> ids = ranked.stream().filter(item -> !item.popular()).map(LectureRanker.Ranked::lectureId).toList();
        Map<Long, float[]> itemVectors = VectorCall.run(() -> vectors(ids));
        Map<Long, LectureTaste.Reasoned> reasons = new HashMap<>();
        for (Long id : ids) {
            reasons.put(id, taste.reason(itemVectors.get(id)));
        }
        return reasons;
    }

    private LectureRecommendation toRecommendation(List<LectureRanker.Ranked> ranked,
            Map<Long, LectureTaste.Reasoned> reasons, boolean needsInterest) {
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
        boolean personalized = false;
        for (LectureRanker.Ranked item : ranked) {
            LectureTaste.Reasoned reasoned = item.popular() ? LectureTaste.Reasoned.POPULAR : reasons.get(item.lectureId());
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

    private LectureSignals.Weights weights() {
        ChatbotLectureRecommendProperties.Weights weights = properties.weights();
        return new LectureSignals.Weights(weights.owned(), weights.cart(), weights::rating);
    }
}

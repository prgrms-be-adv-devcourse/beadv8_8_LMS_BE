package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.Feature;
import com.hapbang.auxiliary.chatbot.app.InfluencerRecommendUseCase.Candidate;
import com.hapbang.auxiliary.chatbot.app.InfluencerRecommendUseCase.InfluencerRecommendation;
import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotRecommendLlmProperties;
import com.hapbang.auxiliary.chatbot.domain.InfluencerReason;

import lombok.RequiredArgsConstructor;

/**
 * 챗봇 "인플루언서 추천받기" 버튼 경로 (#719, #720). 강의 버튼과 같은 흐름이다: 쿼리 확장(관심사가 있을 때) → 맞춤 후보 풀 →
 * LLM 리랭킹 + 이유({@link RecommendReranker}) → 남은 풀(코드 순서) → 인기순 채우기.
 * <ul>
 *     <li>프롬프트에는 후보의 공개 수치만 넣는다: 분야 이름, 후보가 완료한 출연 수(요청 회원과 함께한 수가 아님), 평균 별점, 이유 코드.
 *     <b>닉네임·자기소개 원문, 요청 회원 정보, 회원 ID, 함께 출연한 이력은 넣지 않는다.</b></li>
 *     <li>LLM이 고른 후보만 LLM 문장이고 나머지(남은 풀·인기순 채움)는 이유 코드 문장이다. 풀이 비면 LLM을 부르지 않는다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class InfluencerRecommendExplainUseCase {

    private final InfluencerRecommendUseCase useCase;
    private final QueryExpander queryExpander;
    private final RecommendReranker reranker;
    private final ChatbotCategoryNames categoryNames;
    private final ChatbotInfluencerRecommendProperties properties;
    private final ChatbotRecommendLlmProperties llmProperties;

    /**
     * @param interest 관심사 원문(앞뒤 공백을 뺀 값). 없으면 null
     */
    public Explained recommend(long userId, @Nullable String interest) {
        List<String> queries = interest == null ? null : queryExpander.expand(interest);
        InfluencerRecommendUseCase.Pool pool = useCase.pool(userId, queries, llmProperties.rerank().poolSize());
        if (pool.candidates().isEmpty()) {
            InfluencerRecommendation recommendation = useCase.complete(pool, List.of());
            return new Explained(recommendation, sentences(recommendation, Map.of()), false);
        }
        List<Candidate> described = useCase.describe(pool);
        List<String> lines = new ArrayList<>();
        for (Candidate candidate : described) {
            lines.add(line(candidate));
        }
        RecommendReranker.Reranked reranked = reranker.rerank(Feature.INFLUENCER_BUTTON, "함께 방송할 출연 상대", lines,
                properties.size());
        List<Long> ids = pool.memberIds();
        List<Long> chosen = new ArrayList<>();
        for (int index : reranked.orderWithRest(ids.size())) {
            chosen.add(ids.get(index));
        }
        Map<Long, String> llmReasons = new HashMap<>();
        reranked.reasons().forEach((index, reason) -> llmReasons.put(ids.get(index), reason));
        InfluencerRecommendation recommendation = useCase.complete(pool, chosen);
        return new Explained(recommendation, sentences(recommendation, llmReasons), reranked.fallback() != null);
    }

    /** 이유 코드를 정해진 문장으로(LLM이 고르지 않은 후보·LLM 실패) */
    static String reasonSentence(Candidate candidate) {
        return descriptions(candidate, " ");
    }

    // 후보마다 이유 문장: LLM이 고른 후보는 LLM 문장, 나머지는 이유 코드 문장
    private static Map<Long, String> sentences(InfluencerRecommendation recommendation, Map<Long, String> llmReasons) {
        Map<Long, String> sentences = new HashMap<>();
        for (Candidate candidate : recommendation.items()) {
            sentences.put(candidate.memberId(), llmReasons.getOrDefault(candidate.memberId(), reasonSentence(candidate)));
        }
        return sentences;
    }

    // 후보의 공개 수치만. 닉네임·자기소개·요청 회원 정보·회원 ID는 넣지 않는다
    private String line(Candidate candidate) {
        List<String> names = categoryNames.namesOf(candidate.categories());
        Double rating = candidate.averageRating();
        return "분야: %s | 완료한 출연: %d회 | 평균 별점: %s | 추천 근거: %s".formatted(
                names.isEmpty() ? "없음" : String.join(", ", names), candidate.completedAppearances(),
                rating == null ? "리뷰 없음" : rating, descriptions(candidate, ", "));
    }

    private static String descriptions(Candidate candidate, String delimiter) {
        StringJoiner joined = new StringJoiner(delimiter);
        for (InfluencerReason reason : candidate.orderedReasons()) {
            joined.add(reason.getDescription());
        }
        return joined.toString();
    }

    /**
     * @param explanations 후보 회원 ID → 이유 문장(모든 후보)
     * @param fallback     LLM 답을 쓰지 못하고 이유 코드 문장·코드 순서로 답했다
     */
    public record Explained(InfluencerRecommendation recommendation, Map<Long, String> explanations, boolean fallback) {
    }
}

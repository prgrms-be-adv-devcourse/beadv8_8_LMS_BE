package com.hapbang.auxiliary.chatbot.app;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.Feature;
import com.hapbang.auxiliary.chatbot.config.ChatbotRecommendLlmProperties;

import lombok.RequiredArgsConstructor;

/**
 * 추천 버튼 경로(강의·인플루언서)가 함께 쓰는 흐름 (#720): 쿼리 확장(관심사가 있을 때) → 맞춤 후보 풀 → LLM 리랭킹 + 이유
 * ({@link RecommendReranker}) → 고른 후보 뒤에 남은 풀(코드 순서) → 인기순 채우기.
 * <ul>
 *     <li>풀이 비면 LLM을 부르지 않고 채우기만 한다. fallback이 아니다.</li>
 *     <li>프롬프트의 후보 줄, 풀, 채우기는 종류마다 {@link Steps}가 정한다.</li>
 * </ul>
 * 빈이 아니다. 각 버튼 UseCase가 자기 의존으로 만든다.
 */
@RequiredArgsConstructor
final class RecommendExplainFlow {

    private final QueryExpander queryExpander;
    private final RecommendReranker reranker;
    private final RecommendTasteSupport tasteSupport;
    private final ChatbotRecommendLlmProperties llmProperties;

    /**
     * @param feature  fallback 지표의 기능 태그
     * @param subject  프롬프트 첫 줄(무엇을 고르는지)
     * @param memberId 요청 회원
     * @param interest 관심사 원문. 없으면 null
     * @param pick     추천 수
     */
    <P, R> Explained<R> recommend(Feature feature, String subject, long memberId, @Nullable String interest, int pick,
            Steps<P, R> steps) {
        List<String> queries = interest == null ? null : queryExpander.expand(interest);
        P pool = steps.pool().apply(queries, llmProperties.rerank().poolSize());
        List<Long> ids = steps.ids().apply(pool);
        if (ids.isEmpty()) {
            return new Explained<>(steps.complete().apply(pool, List.of()), Map.of(), false);
        }
        List<String> lines = steps.candidateLines().apply(pool);
        RecommendReranker.Reranked reranked = reranker.rerank(feature, subject, lines, pick,
                tasteSupport.read(memberId, interest));
        Map<Long, String> llmReasons = reranked.reasonsById(ids);
        R recommendation = steps.complete().apply(pool, reranked.chosenThenRest(ids));
        return new Explained<>(recommendation, llmReasons, reranked.fallback() != null);
    }

    /**
     * 종류마다 다른 단계.
     *
     * @param pool           쿼리 확장 표현(없으면 null)·풀 크기 → 맞춤 후보 풀(인기순 채움 없음)
     * @param ids            풀의 후보 ID(코드 순서). 비어 있으면 LLM을 부르지 않는다
     * @param candidateLines 후보마다 프롬프트에 넣을 공개 정보 한 줄(ID 순서와 같게)
     * @param complete       풀과 순서 → 앞에서부터 추천 수만큼 쓰고 모자라면 인기순으로 채운 추천
     */
    record Steps<P, R>(
            BiFunction<@Nullable List<String>, Integer, P> pool,
            Function<P, List<Long>> ids,
            Function<P, List<String>> candidateLines,
            BiFunction<P, List<Long>, R> complete
    ) {
    }

    /**
     * @param recommendation 채운 추천
     * @param llmReasons     후보 ID → LLM 이유 문장(LLM이 고른 후보만)
     * @param fallback       LLM 답을 쓰지 못하고 이유 코드 문장·코드 순서로 답했다
     */
    record Explained<R>(R recommendation, Map<Long, String> llmReasons, boolean fallback) {
    }
}

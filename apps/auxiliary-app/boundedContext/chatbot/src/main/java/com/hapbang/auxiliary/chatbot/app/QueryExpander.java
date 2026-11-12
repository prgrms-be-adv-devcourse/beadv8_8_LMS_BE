package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.FallbackReason;
import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.Feature;
import com.hapbang.auxiliary.chatbot.config.ChatbotRecommendLlmProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 추천 버튼 경로의 관심사 쿼리 확장 (#720). 관심사 문장을 비슷한 표현 몇 개로 늘린다. 검색 전에 끝나야 해서 리랭킹과 따로 부른다.
 * <ul>
 *     <li>돌려주는 목록의 첫째는 늘 원문이다. 부르는 쪽은 원문과 확장 표현을 함께 임베딩해 평균을 관심사 벡터로 쓴다
 *     ({@link ChatbotEmbedder#embedInterest}). 방식 비교는 #867에서 한다.</li>
 *     <li>실패·시간 초과·형식 오류·회로 열림이면 원문만 돌려준다(검색은 계속한다). fallback 지표 {@code feature=query_expansion}.</li>
 *     <li>확장 표현은 앞뒤 공백을 빼고, 빈 것·원문과 같은 것·중복을 버리고, 원문 최대 길이로 자른다.</li>
 * </ul>
 */
@Slf4j
@Component
public class QueryExpander {

    static final String SYSTEM_PROMPT = """
            너는 합방 서비스 추천 검색어를 늘리는 도우미다. 사용자가 입력한 관심사와 뜻이 같거나 가까운 짧은 한국어 표현을 최대 %d개 쓴다.
            - 관심사에 없는 분야를 더하지 않는다.
            - 표현마다 한 구절로 쓴다.
            """;

    private final @Nullable ChatClient chatClient;
    private final LlmCallGuard llm;
    private final ChatbotRecommendLlmProperties properties;
    private final ChatbotMetrics metrics;

    QueryExpander(ObjectProvider<ChatModel> chatModel, ObjectProvider<ChatClient.Builder> chatClientBuilder, LlmCallGuard llm,
            ChatbotRecommendLlmProperties properties, ChatbotMetrics metrics) {
        this.chatClient = chatModel.getIfAvailable() == null ? null : chatClientBuilder.getObject().build();
        this.llm = llm;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * @param interest 관심사 원문(검증·앞뒤 공백 제거를 거친 값)
     * @return 원문이 첫째인 표현 목록
     */
    public List<String> expand(String interest) {
        ChatbotRecommendLlmProperties.QueryExpansion expansion = properties.queryExpansion();
        if (expansion.maxExpansions() == 0) {
            return List.of(interest);
        }
        String system = SYSTEM_PROMPT.formatted(expansion.maxExpansions());
        LlmCallGuard.Result<Expansions> result = llm.call(chatClient != null, expansion.timeout(), () -> {
            ChatClient.ChatClientRequestSpec request = chatClient.prompt().system(system).user("관심사: " + interest)
                    .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT);
            if (!expansion.model().isBlank()) {
                request = request.options(ChatOptions.builder().model(expansion.model()));
            }
            return request.call().entity(Expansions.class);
        });
        if (!result.succeeded()) {
            if (result.reason() == FallbackReason.ERROR || result.reason() == FallbackReason.TIMEOUT
                    || result.reason() == FallbackReason.FORMAT) {
                log.warn("관심사 쿼리 확장을 받지 못해 원문으로 검색한다 reason={}", result.reason().tag(), result.error());
            }
            metrics.fallback(Feature.QUERY_EXPANSION, result.reason());
            return List.of(interest);
        }
        Set<String> queries = new LinkedHashSet<>();
        queries.add(interest);
        List<String> phrases = result.value() == null || result.value().phrases() == null ? List.of() : result.value().phrases();
        for (String phrase : phrases) {
            if (queries.size() > expansion.maxExpansions()) {
                break;
            }
            if (phrase != null && !phrase.isBlank()) {
                queries.add(TextCut.head(phrase.strip(), LectureRecommendUseCase.INTEREST_MAX_LENGTH));
            }
        }
        return new ArrayList<>(queries);
    }

    /** LLM 응답(구조화 출력의 최상위 객체) */
    record Expansions(@Nullable List<String> phrases) {
    }
}

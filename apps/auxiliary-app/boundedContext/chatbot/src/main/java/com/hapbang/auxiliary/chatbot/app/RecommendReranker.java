package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
 * 추천 버튼 경로의 리랭킹 + 이유 (#720). <b>LLM 한 번 호출로 후보 풀에서 고르고 이유를 쓴다.</b> 강의·인플루언서가 같이 쓴다.
 * <ul>
 *     <li>후보는 코드가 만든 풀이다(맞춤 후보만). 프롬프트에는 후보의 공개 정보와 이유 코드 설명만 넣고 회원 정보는 넣지 않는다.
 *     후보는 순번(1부터)으로 부르고 코드가 되돌린다. 모델은 ID를 모른다.</li>
 *     <li>형식은 모델의 구조화 출력({@link AdvisorParams#ENABLE_NATIVE_STRUCTURED_OUTPUT})으로 고정한다. 공급자에 따라 스키마 최상위가
 *     객체여야 해서 목록을 {@link Picks}로 감싼다.</li>
 *     <li>범위 밖 순번, 중복 순번(처음 것만), 빈 문장, 순번을 말한 문장은 버린다. 이유 문장은 {@code reason-max-length}에서 자른다.</li>
 *     <li>LLM을 못 쓰거나(모델 없음·회로 열림·동시 상한·시간 초과·오류·형식 오류) 쓸 만한 답이 하나도 없으면
 *     {@link Reranked#fallback()}에 이유가 있고 고른 것이 없다. 부르는 쪽은 코드 순서와 이유 코드 문장으로 답한다.</li>
 *     <li>리랭킹을 끄면({@code rerank.enabled=false}) 풀의 앞쪽 {@code pick}개만 보여 주고 이유만 받는다.</li>
 * </ul>
 */
@Slf4j
@Component
public class RecommendReranker {

    static final String SYSTEM_PROMPT = """
            너는 합방 서비스의 추천 카드에 들어갈 후보를 고르고 추천 이유를 쓴다. 항상 한국어로 쓴다.
            규칙:
            - 주어진 후보 중에서 사용자에게 가장 알맞은 후보를 최대 %d개 골라 좋은 순서대로 쓴다.
            - 후보마다 candidate(후보 번호)와 reason(추천 이유 한두 문장)을 쓴다.
            - 후보 목록에 있는 사실만 근거로 쓴다. 가격·할인·수강생 수처럼 주어지지 않은 사실은 지어내지 않는다.
            - 추천 근거는 왜 후보가 되었는지다. 문장의 방향을 잡는 데만 쓰고 근거 이름을 그대로 쓰지 않는다.
            - 후보 번호를 문장에 쓰지 않는다. 이름을 지어내지 않는다. 목록에 없는 번호는 쓰지 않는다.
            - 문장마다 %d자를 넘기지 않는다.
            """;

    // 순번을 말한 문장("후보 2", "후보2번")
    private static final Pattern CANDIDATE_MENTION = Pattern.compile("후보\\s*\\d");

    private final @Nullable ChatClient chatClient;
    private final LlmCallGuard llm;
    private final ChatbotRecommendLlmProperties properties;
    private final ChatbotMetrics metrics;

    RecommendReranker(ObjectProvider<ChatModel> chatModel, ObjectProvider<ChatClient.Builder> chatClientBuilder, LlmCallGuard llm,
            ChatbotRecommendLlmProperties properties, ChatbotMetrics metrics) {
        // ChatClient.Builder 빈 정의는 모델이 없어도 있고, 그때 꺼내면 예외다. 모델이 있을 때만 꺼낸다(FaqChatService와 같다)
        this.chatClient = chatModel.getIfAvailable() == null ? null : chatClientBuilder.getObject().build();
        this.llm = llm;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * @param feature    fallback 지표의 기능 태그(버튼)
     * @param subject    프롬프트 첫 줄(무엇을 고르는지, 예: "수강할 강의")
     * @param candidates 후보마다 공개 정보 한 줄(코드 순서). 비어 있으면 LLM을 부르지 않는다
     * @param pick       고를 수(추천 수)
     */
    public Reranked rerank(Feature feature, String subject, List<String> candidates, int pick) {
        if (candidates.isEmpty()) {
            return new Reranked(List.of(), Map.of(), null);
        }
        ChatbotRecommendLlmProperties.Rerank rerank = properties.rerank();
        List<String> shown = rerank.enabled() ? candidates : candidates.subList(0, Math.min(pick, candidates.size()));
        String system = SYSTEM_PROMPT.formatted(pick, rerank.reasonMaxLength());
        String user = prompt(subject, shown);
        LlmCallGuard.Result<Picks> result = llm.call(chatClient != null, rerank.timeout(), () -> {
            ChatClient.ChatClientRequestSpec request = chatClient.prompt().system(system).user(user)
                    .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT);
            if (!rerank.model().isBlank()) {
                request = request.options(ChatOptions.builder().model(rerank.model()));
            }
            return request.call().entity(Picks.class);
        });
        if (!result.succeeded()) {
            return fallback(feature, result.reason(), result.error());
        }
        List<Integer> order = new ArrayList<>();
        Map<Integer, String> reasons = new HashMap<>();
        Set<Integer> seen = new LinkedHashSet<>();
        List<Pick> picks = result.value() == null || result.value().picks() == null ? List.of() : result.value().picks();
        for (Pick answer : picks) {
            if (order.size() >= pick) {
                break;
            }
            if (usable(answer, shown.size()) && seen.add(answer.candidate())) {
                int index = answer.candidate() - 1;
                order.add(index);
                reasons.put(index, TextCut.head(answer.reason().strip(), rerank.reasonMaxLength()));
            }
        }
        if (order.isEmpty()) {
            log.warn("추천 리랭킹에 쓸 만한 답이 없어 코드 순서로 답한다 answered={}", picks.size());
            return fallback(feature, FallbackReason.ERROR, null);
        }
        return new Reranked(order, reasons, null);
    }

    private Reranked fallback(Feature feature, FallbackReason reason, @Nullable Throwable error) {
        if (reason == FallbackReason.ERROR && error != null || reason == FallbackReason.TIMEOUT
                || reason == FallbackReason.FORMAT) {
            log.warn("추천 리랭킹을 받지 못해 코드 순서로 답한다 reason={}", reason.tag(), error);
        }
        metrics.fallback(feature, reason);
        return new Reranked(List.of(), Map.of(), reason);
    }

    // 범위 안의 순번이고, 문장이 있고, 순번을 말하지 않는 답만 쓴다
    private static boolean usable(@Nullable Pick answer, int candidateCount) {
        if (answer == null || answer.candidate() < 1 || answer.candidate() > candidateCount || answer.reason() == null
                || answer.reason().isBlank()) {
            return false;
        }
        return !CANDIDATE_MENTION.matcher(answer.reason()).find();
    }

    private static String prompt(String subject, List<String> candidates) {
        StringBuilder prompt = new StringBuilder(subject).append(" 후보:\n");
        for (int i = 0; i < candidates.size(); i++) {
            prompt.append("- 후보 ").append(i + 1).append(" | ").append(candidates.get(i)).append('\n');
        }
        return prompt.toString();
    }

    /** LLM 응답(구조화 출력의 최상위 객체) */
    record Picks(@Nullable List<Pick> picks) {
    }

    /** 고른 후보 하나. candidate는 프롬프트의 후보 순번(1부터) */
    record Pick(int candidate, @Nullable String reason) {
    }

    /**
     * 리랭킹 결과.
     *
     * @param order    고른 후보의 위치(0부터, 고른 순서). fallback이면 비어 있다
     * @param reasons  위치 → LLM 이유 문장
     * @param fallback LLM 답을 쓰지 못한 이유. 썼으면 null(일부만 쓸 만해도 null)
     */
    public record Reranked(List<Integer> order, Map<Integer, String> reasons, @Nullable FallbackReason fallback) {

        public Reranked {
            order = List.copyOf(order);
            reasons = Map.copyOf(reasons);
        }

        /**
         * 고른 후보 뒤에 남은 후보를 코드 순서로 붙인 위치 목록. 부르는 쪽은 앞에서부터 추천 수만큼 쓰고, 모자라면 인기순으로 채운다.
         */
        public List<Integer> orderWithRest(int candidateCount) {
            List<Integer> all = new ArrayList<>(order);
            for (int i = 0; i < candidateCount; i++) {
                if (!all.contains(i)) {
                    all.add(i);
                }
            }
            return all;
        }
    }
}

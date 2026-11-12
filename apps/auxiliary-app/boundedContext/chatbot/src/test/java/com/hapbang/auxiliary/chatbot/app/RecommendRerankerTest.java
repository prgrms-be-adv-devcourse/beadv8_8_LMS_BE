package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.Feature;
import com.hapbang.auxiliary.chatbot.config.ChatbotLlmProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotRecommendLlmProperties;
import com.hapbang.auxiliary.chatbot.support.FakeChatModel;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 리랭킹 처리 단위 확인 (#720). Spring 문맥 없이 가짜 채팅 모델과 실제 {@link LlmCallGuard}로 만든다. 켠 경우의 동작은
 * {@code LectureRecommendButtonTest}·{@code InfluencerRecommendApiTest}가 API로 본다.
 */
class RecommendRerankerTest {

    private static final List<String> IDS = List.of("a", "b", "c", "d", "e", "f");

    private final FakeChatModel chatModel = new FakeChatModel();
    private LlmCallGuard guard;

    @AfterEach
    void close() {
        chatModel.release();
        if (guard != null) {
            guard.close();
        }
    }

    @Test
    @DisplayName("리랭킹을 끄면 앞 5개만 보여 주고, LLM이 다른 순서로 골라도 코드 순서 그대로 둔다. LLM 이유는 고른 후보에만 붙는다")
    void disabledKeepsCodeOrder() {
        chatModel.reply("""
                {"picks": [{"candidate": 4, "reason": "넷째"}, {"candidate": 2, "reason": "둘째"}]}""");

        RecommendReranker.Reranked result = reranker(false).rerank(Feature.LECTURE_BUTTON, "강의", lines(), 5);

        assertThat(result.fallback()).isNull();
        assertThat(result.chosenThenRest(IDS).subList(0, 5)).containsExactly("a", "b", "c", "d", "e");
        assertThat(result.reasonsById(IDS)).containsOnlyKeys("b", "d").containsEntry("d", "넷째");
        assertThat(chatModel.lastPrompt().getUserMessage().getText()).contains("후보 5 |").doesNotContain("후보 6 |");
    }

    @Test
    @DisplayName("켜면 LLM이 고른 순서 → 남은 후보(코드 순서)")
    void enabledFollowsLlmOrder() {
        chatModel.reply("""
                {"picks": [{"candidate": 4, "reason": "넷째"}, {"candidate": 2, "reason": "둘째"}]}""");

        RecommendReranker.Reranked result = reranker(true).rerank(Feature.LECTURE_BUTTON, "강의", lines(), 5);

        assertThat(result.chosenThenRest(IDS)).startsWith("d", "b", "a", "c", "e");
    }

    private static List<String> lines() {
        return IDS.stream().map(id -> "제목: " + id).toList();
    }

    private RecommendReranker reranker(boolean enabled) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("chatModel", chatModel);
        beans.registerSingleton("chatClientBuilder", ChatClient.builder(chatModel));
        guard = new LlmCallGuard(CircuitBreaker.ofDefaults("test"), new ChatbotLlmProperties(4),
                new SimpleAsyncTaskExecutorBuilder());
        ChatbotRecommendLlmProperties properties = new ChatbotRecommendLlmProperties(
                new ChatbotRecommendLlmProperties.Rerank(enabled, "", Duration.ofSeconds(5), 20, 80, 300),
                new ChatbotRecommendLlmProperties.QueryExpansion("", Duration.ofSeconds(5), 3));
        return new RecommendReranker(beans.getBeanProvider(ChatModel.class), beans.getBeanProvider(ChatClient.Builder.class),
                guard, properties, new ChatbotMetrics(new SimpleMeterRegistry()));
    }
}

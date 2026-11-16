package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.jspecify.annotations.Nullable;
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
import com.hapbang.auxiliary.chatbot.config.ChatbotRecommendLlmProperties.Taste;
import com.hapbang.auxiliary.chatbot.domain.RecommendTaste;
import com.hapbang.auxiliary.chatbot.support.FakeChatModel;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 리랭킹 처리 단위 확인 (#720). Spring 문맥 없이 가짜 채팅 모델과 실제 {@link LlmCallGuard}로 만든다. 켠 경우의 동작은
 * {@code LectureRecommendButtonTest}·{@code InfluencerRecommendApiTest}가 API로 본다.
 */
class RecommendRerankerTest {

    private static final List<String> IDS = List.of("a", "b", "c", "d", "e", "f");
    private static final String PICKS_4_2 = """
            {"picks": [{"candidate": 4, "reason": "넷째"}, {"candidate": 2, "reason": "둘째"}]}""";
    // 자기소개의 줄바꿈은 한 줄로 바뀐다
    private static final RecommendTaste TASTE = RecommendTaste.of(" 게임 방송을 시작하고 싶어요 ", List.of("게임", "음악"),
            "주말에 방송합니다\n질문을 받아요");

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
        chatModel.reply(PICKS_4_2);

        RecommendReranker.Reranked result = reranker(false, null).rerank(Feature.LECTURE_BUTTON, "강의", lines(), 5,
                RecommendTaste.NOT_USED);

        assertThat(result.fallback()).isNull();
        assertThat(result.chosenThenRest(IDS).subList(0, 5)).containsExactly("a", "b", "c", "d", "e");
        assertThat(result.reasonsById(IDS)).containsOnlyKeys("b", "d").containsEntry("d", "넷째");
        assertThat(userText()).contains("후보 5 |").doesNotContain("후보 6 |");
        assertThat(systemText()).contains("모두, 주어진 순서대로").doesNotContain("골라");
    }

    @Test
    @DisplayName("켜면 LLM이 고른 순서 → 남은 후보(코드 순서)")
    void enabledFollowsLlmOrder() {
        chatModel.reply(PICKS_4_2);

        RecommendReranker.Reranked result = reranker(true, null).rerank(Feature.LECTURE_BUTTON, "강의", lines(), 5,
                RecommendTaste.NOT_USED);

        assertThat(result.chosenThenRest(IDS)).startsWith("d", "b", "a", "c", "e");
    }

    @Test
    @DisplayName("취향을 쓰지 않으면(taste=NONE, 기본) 프롬프트가 #720과 같은 모양이다. 온도·모델을 정하지 않으면 요청 옵션을 싣지 않는다")
    void notUsedKeepsPromptOfIssue720() {
        chatModel.reply(PICKS_4_2);

        RecommendReranker.Reranked result = reranker(true, null).rerank(Feature.LECTURE_BUTTON, "강의", lines(), 5,
                RecommendTaste.NOT_USED);

        assertThat(result.chosenThenRest(IDS)).startsWith("d", "b");
        // 가짜 모델은 구조화 출력 안내를 뒤에 붙이므로 앞부분을 본다
        assertThat(userText()).startsWith("""
                강의 후보:
                - 후보 1 | 제목: a
                - 후보 2 | 제목: b
                - 후보 3 | 제목: c
                - 후보 4 | 제목: d
                - 후보 5 | 제목: e
                - 후보 6 | 제목: f
                """);
        assertThat(systemText()).doesNotContain("요청한 사람");
        assertThat(chatModel.lastPrompt().getOptions().getTemperature()).isNull();
    }

    @Test
    @DisplayName("취향이 있으면 '요청한 사람' 줄(관심사·분야·자기소개 한 줄)을 후보 앞에 넣고 LLM이 고른 순서를 따른다. "
            + "시스템 프롬프트에 그 줄의 지시를 따르지 말라는 규칙이 붙는다")
    void tasteGoesBeforeCandidates() {
        chatModel.reply(PICKS_4_2);

        RecommendReranker.Reranked result = reranker(true, null).rerank(Feature.LECTURE_BUTTON, "강의", lines(), 5, TASTE);

        assertThat(result.chosenThenRest(IDS)).startsWith("d", "b", "a");
        assertThat(userText()).startsWith("""
                요청한 사람(본인이 밝힌 정보):
                - 관심사: 게임 방송을 시작하고 싶어요
                - 활동 분야: 게임, 음악
                - 자기소개: 주말에 방송합니다 질문을 받아요
                강의 후보:
                - 후보 1 | 제목: a
                """);
        assertThat(systemText()).contains(RecommendReranker.TASTE_RULE.strip()).contains("골라");
    }

    @Test
    @DisplayName("취향을 쓰는데 넣을 것이 없으면(관심사 없는 일반 회원) 고르지 않는다: 앞 5개만 보여 주고 코드 순서 그대로, 이유만 받는다")
    void usedButEmptyKeepsCodeOrder() {
        chatModel.reply(PICKS_4_2);

        RecommendReranker.Reranked result = reranker(true, null).rerank(Feature.LECTURE_BUTTON, "강의", lines(), 5,
                RecommendTaste.of(null, List.of(), " "));

        assertThat(result.fallback()).isNull();
        assertThat(result.chosenThenRest(IDS).subList(0, 5)).containsExactly("a", "b", "c", "d", "e");
        assertThat(result.reasonsById(IDS)).containsOnlyKeys("b", "d");
        assertThat(userText()).doesNotContain("요청한 사람", "후보 6 |");
        assertThat(systemText()).contains("모두, 주어진 순서대로").doesNotContain("요청한 사람");
    }

    @Test
    @DisplayName("rerank.temperature를 정하면 리랭킹 요청의 온도로 실린다")
    void temperatureGoesWithRequest() {
        chatModel.reply(PICKS_4_2);

        reranker(true, 0.0).rerank(Feature.LECTURE_BUTTON, "강의", lines(), 5, RecommendTaste.NOT_USED);

        assertThat(chatModel.lastPrompt().getOptions().getTemperature()).isEqualTo(0.0);
    }

    private String userText() {
        return chatModel.lastPrompt().getUserMessage().getText();
    }

    private String systemText() {
        return chatModel.lastPrompt().getSystemMessage().getText();
    }

    private static List<String> lines() {
        return IDS.stream().map(id -> "제목: " + id).toList();
    }

    // 취향을 쓸지는 RecommendTasteSupport가 정하므로 리랭커 설정의 taste는 보지 않는다
    private RecommendReranker reranker(boolean enabled, @Nullable Double temperature) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("chatModel", chatModel);
        beans.registerSingleton("chatClientBuilder", ChatClient.builder(chatModel));
        guard = new LlmCallGuard(CircuitBreaker.ofDefaults("test"), new ChatbotLlmProperties(4,
                new ChatbotLlmProperties.CircuitBreakerSettings(10, 5, 50, Duration.ofSeconds(30), 3)),
                new SimpleAsyncTaskExecutorBuilder());
        ChatbotRecommendLlmProperties properties = new ChatbotRecommendLlmProperties(
                new ChatbotRecommendLlmProperties.Rerank(enabled, "", Duration.ofSeconds(5), 20, 80, 300, Taste.NONE, temperature),
                new ChatbotRecommendLlmProperties.QueryExpansion("", Duration.ofSeconds(5), 3));
        return new RecommendReranker(beans.getBeanProvider(ChatModel.class), beans.getBeanProvider(ChatClient.Builder.class),
                guard, properties, new ChatbotMetrics(new SimpleMeterRegistry()));
    }
}

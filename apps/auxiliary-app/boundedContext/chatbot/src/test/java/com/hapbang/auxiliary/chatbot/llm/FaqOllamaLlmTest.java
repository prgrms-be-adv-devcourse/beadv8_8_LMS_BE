package com.hapbang.auxiliary.chatbot.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.hapbang.auxiliary.chatbot.app.FaqAnswer;
import com.hapbang.auxiliary.chatbot.app.FaqChatService;
import com.hapbang.auxiliary.chatbot.app.FaqStreamEvent;
import com.hapbang.auxiliary.chatbot.app.FaqSyncService;
import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 실제 모델(로컬 Ollama qwen2.5:3b + nomic-embed-text)이 searchFaq Tool을 부르고 스트림이 끝까지 오는지 본다(#715).
 * 답변 문장은 매번 달라 단정하지 않는다. Tool을 불렀는지(sources)와 이벤트 순서만 본다.
 * 실행: {@code ./gradlew :chatbot:llmTest --tests '*FaqOllamaLlmTest'} (CPU에서는 질문마다 수십 초 걸릴 수 있다)
 */
@Tag("llm")
@IntegrationTest
@Import(ElasticsearchTestcontainersConfig.class)
@TestPropertySource(properties = {
        "spring.ai.model.chat=ollama",
        "spring.ai.model.embedding=ollama",
        "spring.ai.ollama.base-url=http://localhost:11434",
        "spring.ai.ollama.init.pull-model-strategy=never",
        "spring.ai.ollama.chat.model=qwen2.5:3b",
        "spring.ai.ollama.chat.temperature=0",
        "spring.ai.ollama.embedding.model=nomic-embed-text",
        "hapbang.chatbot.faq.index-name=chatbot-faq-ollama-768-llm",
        "hapbang.chatbot.faq.dimensions=768",
        "hapbang.chatbot.faq.document-prefix=search_document: ",
        "hapbang.chatbot.faq.query-prefix=search_query: ",
        "hapbang.chatbot.faq.similarity-threshold=0.6",
        // CPU Ollama는 느리다. 이 테스트는 시간 상한이 아니라 연결을 본다
        "hapbang.chatbot.faq.chat-timeout=180s",
        "hapbang.chatbot.faq.stream-timeout=180s"
})
class FaqOllamaLlmTest {

    @Autowired
    private FaqSyncService syncService;

    @Autowired
    private FaqChatService chatService;

    @BeforeEach
    void setUp() throws Exception {
        deleteIndex();
        syncService.syncAll();
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteIndex();
    }

    @Test
    @DisplayName("모델이 searchFaq를 불러 근거 FAQ가 붙은 답을 준다(fallback 아님)")
    void chatCallsTool() {
        FaqAnswer answer = chatService.chat(1L, "HP 충전은 어떻게 해요?");

        System.out.println("[FaqOllamaLlmTest] chat answer=" + answer);
        assertThat(answer.fallback()).isFalse();
        assertThat(answer.sources()).isNotEmpty();
        assertThat(answer.answer()).isNotBlank();
    }

    @Test
    @DisplayName("스트림이 여러 조각으로 오고 done으로 끝난다")
    void streamCompletes() {
        List<FaqStreamEvent> events = chatService.stream(1L, "출연을 취소하면 HP는 돌려받나요?").collectList()
                .block(Duration.ofMinutes(4));

        System.out.println("[FaqOllamaLlmTest] stream events=" + events.size() + ", last=" + events.getLast());
        assertThat(events.stream().filter(FaqStreamEvent.Token.class::isInstance).count()).isGreaterThan(1);
        assertThat(events.getLast()).isInstanceOfSatisfying(FaqStreamEvent.Done.class, done -> {
            assertThat(done.fallback()).isFalse();
            assertThat(done.sources()).isNotEmpty();
        });
    }

    private void deleteIndex() throws Exception {
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create(ElasticsearchTestcontainersConfig.uri() + "/chatbot-faq-ollama-768-llm")).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
    }
}

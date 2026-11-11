package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;

import com.hapbang.auxiliary.chatbot.app.FaqAnswer.FaqSource;
import com.hapbang.auxiliary.chatbot.config.ChatbotFaqProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotLlmProperties;
import com.hapbang.auxiliary.chatbot.support.FakeChatModel;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.Disposable;
import reactor.test.StepVerifier;

/**
 * FAQ 대화의 프롬프트 조립·Tool 연결·fallback·시간 상한·회로·동시 상한·SSE 이벤트 순서(#715). 가짜 채팅 모델과 검색 결과로 확인한다.
 * 실제 모델 연동은 {@code FaqOllamaLlmTest}(@Tag llm)가 본다.
 */
class FaqChatServiceTest {

    private static final FaqHit HIT = new FaqHit("FAQ-501", "출연을 취소하고 싶어요.", "확정 전에는 언제든 취소할 수 있습니다.", 0.9);
    private static final FaqHit SECOND = new FaqHit("FAQ-502", "호스트가 일방적으로 취소하면?", "보류한 HP를 돌려받습니다.", 0.85);

    private final FakeChatModel chatModel = new FakeChatModel();
    private final FaqSearchService searchService = mock(FaqSearchService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CircuitBreaker circuitBreaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofMinutes(1))
            .build());

    private FaqChatService service;
    private LlmCallGuard guard;

    @AfterEach
    void close() {
        chatModel.release();
        if (guard != null) {
            guard.close();
        }
    }

    @Test
    @DisplayName("시스템 프롬프트·사용자 질문·searchFaq Tool을 실어 부르고, Tool이 찾은 FAQ를 sources로 준다")
    void chatUsesToolAndReturnsSources() {
        when(searchService.search(anyString())).thenReturn(List.of(HIT, SECOND));
        service = service(Duration.ofSeconds(2), 4, true);

        FaqAnswer answer = service.chat("출연 취소하면 어떻게 돼요?");

        assertThat(answer.fallback()).isFalse();
        assertThat(answer.answer()).isEqualTo(FakeChatModel.ANSWER_PREFIX + " " + HIT.answer());
        assertThat(answer.sources()).containsExactly(new FaqSource("FAQ-501", HIT.question()),
                new FaqSource("FAQ-502", SECOND.question()));
        var prompt = chatModel.lastPrompt();
        assertThat(prompt.getInstructions()).extracting(message -> message.getMessageType())
                .containsExactly(MessageType.SYSTEM, MessageType.USER);
        assertThat(prompt.getSystemMessage().getText()).isEqualTo(FaqChatService.SYSTEM_PROMPT)
                .contains("Tool 결과에 있는 내용만 근거로", FaqChatService.NO_ANSWER_MESSAGE, "개인정보");
        assertThat(prompt.getUserMessage().getText()).isEqualTo("출연 취소하면 어떻게 돼요?");
        assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name()).containsExactly("searchFaq");
    }

    @Test
    @DisplayName("모델이 정상으로 답했어도 Tool이 FAQ를 못 찾았으면 FAQ 매칭 실패로 센다")
    void chatCountsUnmatchedWhenToolFindsNothing() {
        when(searchService.search(anyString())).thenReturn(List.of());
        service = service(Duration.ofSeconds(2), 4, true);

        FaqAnswer answer = service.chat("쿠폰은 어디서 받아요?");

        assertThat(answer.fallback()).isFalse();
        assertThat(answer.sources()).isEmpty();
        assertThat(registry.get("hapbang.chatbot.faq.unmatched").tag("feature", "faq_chat").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("모델이 실패하면 LLM 없이 검색 상위 1건의 답변 그대로, fallback=true")
    void chatFallsBackToTopHit() {
        when(searchService.search(anyString())).thenReturn(List.of(HIT, SECOND));
        chatModel.mode(FakeChatModel.Mode.FAIL);
        service = service(Duration.ofSeconds(2), 4, true);

        FaqAnswer answer = service.chat("출연 취소");

        assertThat(answer).isEqualTo(new FaqAnswer(HIT.answer(), List.of(new FaqSource("FAQ-501", HIT.question())), true));
        assertThat(fallbacks("faq_chat", "error")).isEqualTo(1);
    }

    @Test
    @DisplayName("fallback인데 기준값을 넘는 FAQ도 없으면 안내 문구, 근거 없음")
    void chatFallsBackToNoAnswer() {
        when(searchService.search(anyString())).thenReturn(List.of());
        chatModel.mode(FakeChatModel.Mode.FAIL);
        service = service(Duration.ofSeconds(2), 4, true);

        assertThat(service.chat("쿠폰은 어디서 받아요?"))
                .isEqualTo(new FaqAnswer(FaqChatService.NO_ANSWER_MESSAGE, List.of(), true));
        assertThat(registry.get("hapbang.chatbot.faq.unmatched").tag("feature", "faq_chat").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("모델이 시간 상한을 넘기면 기다리지 않고 fallback으로 답한다")
    void chatTimesOut() {
        when(searchService.search(anyString())).thenReturn(List.of(HIT));
        // 모델은 풀어 줄 때까지 답하지 않는다. 답이 돌아왔다는 것 자체가 시간 상한에서 끊었다는 뜻이다
        chatModel.hold();
        chatModel.mode(FakeChatModel.Mode.HANG);
        service = service(Duration.ofMillis(300), 4, true);

        FaqAnswer answer = service.chat("출연 취소");

        assertThat(answer.fallback()).isTrue();
        assertThat(fallbacks("faq_chat", "timeout")).isEqualTo(1);
    }

    @Test
    @DisplayName("실패가 이어지면 회로가 열리고, 열린 동안은 모델을 부르지 않고 fallback으로 답한다")
    void circuitOpensAfterFailures() {
        when(searchService.search(anyString())).thenReturn(List.of(HIT));
        chatModel.mode(FakeChatModel.Mode.FAIL);
        service = service(Duration.ofSeconds(2), 4, true);

        for (int i = 0; i < 5; i++) {
            service.chat("출연 취소");
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int calls = chatModel.calls();

        FaqAnswer answer = service.chat("출연 취소");

        assertThat(answer.fallback()).isTrue();
        assertThat(chatModel.calls()).isEqualTo(calls);
        assertThat(fallbacks("faq_chat", "circuit_open")).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 대화 상한에 걸리면 모델을 부르지 않고 fallback(busy)이다")
    void busyWhenConcurrentLimitReached() throws Exception {
        when(searchService.search(anyString())).thenReturn(List.of(HIT));
        // 첫 대화는 풀어 줄 때까지 모델 안에 머문다(시간 상한은 길게 두어 끊기지 않게 한다)
        chatModel.hold();
        chatModel.mode(FakeChatModel.Mode.HANG);
        service = service(Duration.ofSeconds(30), 1, true);

        CompletableFuture<FaqAnswer> first = CompletableFuture.supplyAsync(() -> service.chat("첫 질문"));
        await().atMost(Duration.ofSeconds(10)).until(() -> chatModel.calls() == 1);
        FaqAnswer second = service.chat("두 번째 질문");

        assertThat(second.fallback()).isTrue();
        assertThat(fallbacks("faq_chat", "busy")).isEqualTo(1);
        chatModel.release();
        assertThat(first.get(10, TimeUnit.SECONDS).fallback()).isFalse();
    }

    @Test
    @DisplayName("채팅 모델이 없는 실행이면 항상 fallback(model_off)이다")
    void modelOff() {
        when(searchService.search(anyString())).thenReturn(List.of(HIT));
        service = service(Duration.ofSeconds(2), 4, false);

        assertThat(service.chat("출연 취소").fallback()).isTrue();
        assertThat(fallbacks("faq_chat", "model_off")).isEqualTo(1);
    }

    @Test
    @DisplayName("스트림: token 여러 개(앞 공백 보존) 뒤 done(sources, fallback=false)")
    void streamTokensThenDone() {
        when(searchService.search(anyString())).thenReturn(List.of(HIT));
        service = service(Duration.ofSeconds(2), 4, true);

        List<FaqStreamEvent> events = service.stream("출연 취소").collectList().block(Duration.ofSeconds(5));

        assertThat(events).hasSizeGreaterThan(2);
        assertThat(events.getLast()).isEqualTo(new FaqStreamEvent.Done(List.of(new FaqSource("FAQ-501", HIT.question())), false));
        String text = events.subList(0, events.size() - 1).stream()
                .map(event -> ((FaqStreamEvent.Token) event).text())
                .reduce("", String::concat);
        assertThat(text).isEqualTo(FakeChatModel.ANSWER_PREFIX + " " + HIT.answer());
        assertThat(events).anySatisfy(event -> assertThat(((FaqStreamEvent) event)).isInstanceOfSatisfying(
                FaqStreamEvent.Token.class, token -> assertThat(token.text()).startsWith(" ")));
    }

    @Test
    @DisplayName("스트림이 첫 조각 전에 실패하면 fallback 답을 token 하나 + done(fallback=true)으로 보낸다")
    void streamFailsBeforeFirstToken() {
        when(searchService.search(anyString())).thenReturn(List.of(HIT));
        chatModel.mode(FakeChatModel.Mode.FAIL);
        service = service(Duration.ofSeconds(2), 4, true);

        StepVerifier.create(service.stream("출연 취소"))
                .expectNext(new FaqStreamEvent.Token(HIT.answer()))
                .expectNext(new FaqStreamEvent.Done(List.of(new FaqSource("FAQ-501", HIT.question())), true))
                .verifyComplete();
        assertThat(fallbacks("faq_stream", "error")).isEqualTo(1);
    }

    @Test
    @DisplayName("스트림이 조각을 보낸 뒤 실패하면 보낸 조각은 두고 error 이벤트로 끝낸다")
    void streamFailsAfterFirstToken() {
        when(searchService.search(anyString())).thenReturn(List.of(HIT));
        chatModel.mode(FakeChatModel.Mode.FAIL_AFTER_FIRST_TOKEN);
        service = service(Duration.ofSeconds(2), 4, true);

        StepVerifier.create(service.stream("출연 취소"))
                .expectNext(new FaqStreamEvent.Token(FakeChatModel.ANSWER_PREFIX))
                .expectNext(new FaqStreamEvent.Error(FaqChatService.STREAM_ERROR_MESSAGE))
                .verifyComplete();
    }

    @Test
    @DisplayName("스트림이 전체 시간 상한을 넘기면 fallback으로 답한다")
    void streamTimesOut() {
        when(searchService.search(anyString())).thenReturn(List.of(HIT));
        chatModel.hold();
        chatModel.mode(FakeChatModel.Mode.HANG);
        service = service(Duration.ofMillis(300), 4, true);

        // verify 상한은 테스트가 멈추지 않게 하는 안전장치다. 모델이 답하지 않으므로 끝났다면 시간 상한에서 끊은 것이다
        StepVerifier.create(service.stream("출연 취소"))
                .expectNext(new FaqStreamEvent.Token(HIT.answer()))
                .expectNextMatches(event -> event instanceof FaqStreamEvent.Done done && done.fallback())
                .expectComplete()
                .verify(Duration.ofSeconds(30));
        assertThat(fallbacks("faq_stream", "timeout")).isEqualTo(1);
    }

    @Test
    @DisplayName("모델이 빈 답으로 끝내면 안내 문구 token과 done을 보낸다")
    void streamEmptyAnswer() {
        when(searchService.search(anyString())).thenReturn(List.of());
        chatModel.mode(FakeChatModel.Mode.EMPTY);
        service = service(Duration.ofSeconds(2), 4, true);

        StepVerifier.create(service.stream("아무 말"))
                .expectNext(new FaqStreamEvent.Token(FaqChatService.NO_ANSWER_MESSAGE))
                .expectNext(new FaqStreamEvent.Done(List.of(), false))
                .verifyComplete();
        assertThat(registry.get("hapbang.chatbot.faq.unmatched").tag("feature", "faq_stream").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("사용자가 스트림을 끊으면 동시 대화 자리를 돌려준다")
    void cancelledStreamReleasesPermit() throws Exception {
        when(searchService.search(anyString())).thenReturn(List.of(HIT));
        chatModel.hold();
        chatModel.mode(FakeChatModel.Mode.HANG);
        service = service(Duration.ofSeconds(30), 1, true);

        CountDownLatch subscribed = new CountDownLatch(1);
        Disposable stream = service.stream("첫 질문").doOnSubscribe(s -> subscribed.countDown()).subscribe();
        assertThat(subscribed.await(10, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(10)).until(() -> chatModel.calls() == 1);
        stream.dispose();
        chatModel.mode(FakeChatModel.Mode.TOOL_ANSWER);

        assertThat(service.chat("두 번째 질문").fallback()).isFalse();
    }

    @Test
    @DisplayName("동시 상한은 챗봇 기능 전체가 나눠 쓴다: 다른 기능이 자리를 쥐고 있으면 FAQ 대화는 busy, 돌려주면 다시 부른다")
    void concurrentLimitSharedAcrossFeatures() {
        when(searchService.search(anyString())).thenReturn(List.of(HIT));
        service = service(Duration.ofSeconds(2), 1, true);
        // 다른 기능(예: 강의 추천 설명)이 같은 장치로 자리를 잡는다
        LlmCallGuard.Permit other = guard.acquire(true);
        assertThat(other.blocked()).isNull();

        assertThat(service.chat("출연 취소").fallback()).isTrue();
        assertThat(fallbacks("faq_chat", "busy")).isEqualTo(1);

        other.succeeded();
        assertThat(service.chat("출연 취소").fallback()).isFalse();
    }

    private FaqChatService service(Duration timeout, int maxConcurrent, boolean withModel) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (withModel) {
            beans.registerSingleton("chatModel", chatModel);
            beans.registerSingleton("chatClientBuilder", ChatClient.builder(chatModel));
        }
        ChatbotFaqProperties properties = new ChatbotFaqProperties("chatbot-faq-test-8", 8, 0.8, 3, "", "", timeout, timeout,
                false, Duration.ofMinutes(10), new ChatbotFaqProperties.RateLimit(10, Duration.ofMinutes(1), 100));
        guard = new LlmCallGuard(circuitBreaker, new ChatbotLlmProperties(maxConcurrent), new SimpleAsyncTaskExecutorBuilder());
        return new FaqChatService(beans.getBeanProvider(org.springframework.ai.chat.model.ChatModel.class),
                beans.getBeanProvider(ChatClient.Builder.class), new FaqTools(searchService), searchService,
                properties, new ChatbotMetrics(registry), guard);
    }

    private double fallbacks(String feature, String reason) {
        return registry.get("hapbang.chatbot.fallbacks").tag("feature", feature).tag("reason", reason).counter().count();
    }

}

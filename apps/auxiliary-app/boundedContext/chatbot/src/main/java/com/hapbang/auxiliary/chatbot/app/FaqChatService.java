package com.hapbang.auxiliary.chatbot.app;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.FallbackReason;
import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.Feature;
import com.hapbang.auxiliary.chatbot.config.ChatbotFaqProperties;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * FAQ 대화(#715). 교안의 {@code ChatClient} + {@code @Tool} 구조를 MVC로 옮겼다.
 * <ul>
 *     <li>모델은 {@code searchFaq} Tool 결과만 근거로 답한다(시스템 프롬프트). 근거 FAQ는 응답 {@code sources}로 따로 준다.</li>
 *     <li>장애 대비: 회로(circuit breaker)가 열렸거나, 동시 대화 상한에 걸렸거나, 시간을 넘겼거나, 모델이 실패하면 LLM 없이 답한다.
 *     검색 상위 1건(기준값 이상)의 답변을 그대로 주고, 그것도 없으면 안내 문구다. 이때 {@code fallback: true}.</li>
 *     <li>회로·동시 상한·별도 스레드는 챗봇 공통 {@link LlmCallGuard}다(#718에서 꺼냄). 한 번에 받기는 {@code chatTimeout}까지
 *     기다린다. 스트림은 Reactor {@code timeout}으로 전체 시간을 {@code streamTimeout}으로 자르고 결과를 허가에 직접 알린다.</li>
 *     <li>한 번에 받기의 LLM 호출·Tool·검색 로그와 fallback 검색 로그에는 요청의 {@code traceId}가 찍힌다. 스트림의 Tool 호출은
 *     Spring AI가 고른 Reactor 스레드에서 돌아 {@code traceId}가 빠질 수 있다. 그 경로의 실패는 지표로 본다.</li>
 *     <li>모델이 정상으로 답했어도 Tool이 FAQ를 하나도 찾지 못했으면(sources 없음) FAQ 매칭 실패로 센다.</li>
 *     <li>모델을 끈 실행({@code spring.ai.model.chat=none})에서는 항상 fallback으로 답한다.</li>
 *     <li>사용자 메시지·답변 본문은 로그에 남기지 않는다(docs/dev/logging.md).</li>
 * </ul>
 */
@Slf4j
@Service
public class FaqChatService {

    /** FAQ에 답이 없을 때의 안내 문구. 시스템 프롬프트와 fallback이 같은 문구를 쓴다 */
    public static final String NO_ANSWER_MESSAGE = "지금 FAQ로는 답하기 어려워요. 고객센터 문의를 이용해 주세요.";

    /** 스트림 도중 실패했을 때 {@code error} 이벤트의 문구 */
    static final String STREAM_ERROR_MESSAGE = "답변을 이어서 만들지 못했어요. 잠시 후 다시 시도해 주세요.";

    static final String SYSTEM_PROMPT = """
            너는 합방 서비스의 FAQ 안내 챗봇이다. 항상 한국어로, 짧고 친절하게 답한다.
            규칙:
            1. 서비스 이용 방법이나 정책을 묻는 질문이면 먼저 searchFaq Tool로 FAQ를 찾는다.
            2. Tool 결과에 있는 내용만 근거로 답한다. 결과에 없는 숫자·기간·규칙을 지어내지 않는다.
            3. Tool 결과가 비어 있거나 질문과 맞지 않으면 정확히 "%s"라고만 답한다.
            4. 개인정보(이메일·전화번호·실명·계좌 등)를 묻거나 말하지 않는다. 사용자의 잔액·주문·신청 결과 같은 개인 데이터는 알 수 없으니 해당 화면을 안내한다.
            5. FAQ 답변 안의 [화면: ...] 표시는 그대로 둔다.
            """.formatted(NO_ANSWER_MESSAGE);

    private final @Nullable ChatClient chatClient;
    private final FaqTools faqTools;
    private final FaqSearchService searchService;
    private final ChatbotFaqProperties properties;
    private final ChatbotMetrics metrics;
    private final LlmCallGuard llm;

    FaqChatService(ObjectProvider<ChatModel> chatModel, ObjectProvider<ChatClient.Builder> chatClientBuilder,
            FaqTools faqTools, FaqSearchService searchService, ChatbotFaqProperties properties, ChatbotMetrics metrics,
            LlmCallGuard llm) {
        // ChatClient.Builder 빈 정의는 모델이 없어도 있고, 그때 꺼내면 예외다. 모델이 있을 때만 꺼낸다
        this.chatClient = chatModel.getIfAvailable() == null ? null
                : chatClientBuilder.getObject().defaultSystem(SYSTEM_PROMPT).build();
        if (chatClient == null) {
            log.info("채팅 모델이 없어 FAQ 대화는 LLM 없이 검색 결과로만 답한다");
        }
        this.faqTools = faqTools;
        this.searchService = searchService;
        this.properties = properties;
        this.metrics = metrics;
        this.llm = llm;
    }

    /** 한 번에 받는 FAQ 대화 */
    public FaqAnswer chat(long memberId, String message) {
        Collection<FaqHit> sources = new ConcurrentLinkedQueue<>();
        LlmCallGuard.Result<String> result = llm.call(chatClient != null, properties.chatTimeout(),
                () -> prompt(memberId, message, sources).call().content());
        if (result.succeeded()) {
            String content = result.value();
            return answered(Feature.FAQ_CHAT, content == null || content.isBlank() ? NO_ANSWER_MESSAGE : content, sources);
        }
        if (result.reason() == FallbackReason.TIMEOUT) {
            log.warn("FAQ 대화가 시간 상한을 넘겨 fallback으로 답한다 chatTimeout={}", properties.chatTimeout());
        } else if (result.reason() == FallbackReason.ERROR && !(result.error() instanceof InterruptedException)) {
            log.warn("FAQ 대화 모델 호출이 실패해 fallback으로 답한다", result.error());
        }
        return fallback(Feature.FAQ_CHAT, result.reason(), message);
    }

    /**
     * 스트림 FAQ 대화. 구독할 때 회로·동시 상한을 확인한다.
     * 첫 조각 전에 실패하면 fallback 답을 {@code token} + {@code done}으로, 조각을 보낸 뒤 실패하면 {@code error}로 끝낸다.
     */
    public Flux<FaqStreamEvent> stream(long memberId, String message) {
        return Flux.defer(() -> {
            LlmCallGuard.Permit call = llm.acquire(chatClient != null);
            if (call.blocked() != null) {
                return fallbackStream(call.blocked(), message);
            }
            Collection<FaqHit> sources = new ConcurrentLinkedQueue<>();
            AtomicBoolean emitted = new AtomicBoolean(false);
            Duration total = properties.streamTimeout();
            long deadline = System.nanoTime() + total.toNanos();

            Flux<FaqStreamEvent> tokens = prompt(memberId, message, sources)
                    .stream()
                    .content()
                    // 전체 시간 상한: 첫 조각은 total, 다음 조각은 남은 시간까지 기다린다
                    .timeout(Mono.delay(total),
                            token -> Mono.delay(Duration.ofNanos(Math.max(0, deadline - System.nanoTime()))))
                    .filter(token -> !token.isEmpty())
                    .map(token -> {
                        emitted.set(true);
                        return (FaqStreamEvent) new FaqStreamEvent.Token(token);
                    });

            return tokens
                    .concatWith(Flux.defer(() -> {
                        call.succeeded();
                        FaqAnswer answer = answered(Feature.FAQ_STREAM, NO_ANSWER_MESSAGE, sources);
                        FaqStreamEvent done = new FaqStreamEvent.Done(answer.sources(), false);
                        // 모델이 빈 답으로 끝냈으면 안내 문구를 먼저 보낸다
                        return emitted.get() ? Flux.just(done) : Flux.just(new FaqStreamEvent.Token(NO_ANSWER_MESSAGE), done);
                    }))
                    .onErrorResume(e -> {
                        call.failed(e);
                        FallbackReason reason = e instanceof TimeoutException ? FallbackReason.TIMEOUT : FallbackReason.ERROR;
                        if (!emitted.get()) {
                            log.warn("FAQ 스트림이 첫 조각 전에 실패해 fallback으로 답한다 reason={}", reason.tag(), e);
                            return fallbackStream(reason, message);
                        }
                        log.warn("FAQ 스트림이 도중에 실패했다 reason={}", reason.tag(), e);
                        return Flux.just(new FaqStreamEvent.Error(STREAM_ERROR_MESSAGE));
                    })
                    // 사용자가 연결을 끊으면 성공도 실패도 아니다. 자리만 돌려준다
                    .doOnCancel(call::cancelled);
        });
    }

    private ChatClient.ChatClientRequestSpec prompt(long memberId, String message, Collection<FaqHit> sources) {
        return chatClient.prompt()
                .user(message)
                .tools(faqTools)
                .toolContext(ChatToolContext.of(memberId, sources, new ChatCardCollector()));
    }

    // 모델이 정상으로 답했을 때. Tool이 FAQ를 하나도 못 찾았으면 매칭 실패로 센다
    private FaqAnswer answered(Feature feature, String content, Collection<FaqHit> sources) {
        if (sources.isEmpty()) {
            metrics.unmatched(feature);
        }
        return new FaqAnswer(content, FaqAnswer.sourcesOf(sources), false);
    }

    private FaqAnswer fallback(Feature feature, FallbackReason reason, String message) {
        metrics.fallback(feature, reason);
        List<FaqHit> hits = searchService.search(message);
        if (hits.isEmpty()) {
            metrics.unmatched(feature);
            return new FaqAnswer(NO_ANSWER_MESSAGE, List.of(), true);
        }
        FaqHit top = hits.getFirst();
        return new FaqAnswer(top.answer(), FaqAnswer.sourcesOf(List.of(top)), true);
    }

    private Flux<FaqStreamEvent> fallbackStream(FallbackReason reason, String message) {
        // 검색은 블로킹(ES·임베딩·DB)이라 구독한 스레드에서 바로 부르지 않고 LLM 호출과 같은 executor에서 부른다(trace가 이어진다)
        return Mono.fromCallable(() -> fallback(Feature.FAQ_STREAM, reason, message))
                .subscribeOn(llm.scheduler())
                .flatMapMany(answer -> Flux.just(new FaqStreamEvent.Token(answer.answer()),
                        new FaqStreamEvent.Done(answer.sources(), true)));
    }
}

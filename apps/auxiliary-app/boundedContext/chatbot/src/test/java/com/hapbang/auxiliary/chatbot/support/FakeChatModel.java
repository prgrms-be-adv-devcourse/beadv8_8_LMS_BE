package com.hapbang.auxiliary.chatbot.support;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트 전용 가짜 채팅 모델(#715). 운영 소스에 두지 않는다(docs/dev/testing.md).
 * <p>
 * 기본 동작({@link Mode#TOOL_ANSWER})은 실제 모델이 하는 일을 흉내 낸다. 요청에 실린 {@code searchFaq} Tool을 사용자 질문으로 부르고
 * (요청의 ToolContext를 그대로 넘긴다), 결과의 첫 FAQ 답변으로 답한다. 결과가 없으면 안내 문구로 답한다. 받은 요청은 {@link #lastPrompt()}로 본다.
 */
public class FakeChatModel implements ChatModel {

    /** 결과가 없을 때의 답(운영 시스템 프롬프트가 시키는 문구와 같다) */
    public static final String NO_ANSWER = "지금 FAQ로는 답하기 어려워요. 고객센터 문의를 이용해 주세요.";

    /** 답 앞에 붙이는 글. 스트림에서 앞 공백이 든 조각을 만든다 */
    public static final String ANSWER_PREFIX = "FAQ에 따르면";

    public enum Mode {
        /** Tool을 부르고 그 결과로 답한다 */
        TOOL_ANSWER,
        /** 예외를 던진다(스트림은 첫 조각 전 오류) */
        FAIL,
        /** {@link #delay} 동안 응답하지 않는다 */
        SLOW,
        /** 스트림: 첫 조각을 보낸 뒤 오류 */
        FAIL_AFTER_FIRST_TOKEN,
        /** 빈 답 */
        EMPTY
    }

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.TOOL_ANSWER);
    private final AtomicReference<Prompt> lastPrompt = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Duration delay = Duration.ofSeconds(10);

    public void mode(Mode mode) {
        this.mode.set(mode);
    }

    public void delay(Duration delay) {
        this.delay = delay;
    }

    public void reset() {
        mode.set(Mode.TOOL_ANSWER);
        lastPrompt.set(null);
        calls.set(0);
        delay = Duration.ofSeconds(10);
    }

    public Prompt lastPrompt() {
        return lastPrompt.get();
    }

    public int calls() {
        return calls.get();
    }

    @Override
    public ChatOptions getOptions() {
        return DefaultToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        record(prompt);
        return switch (mode.get()) {
            case FAIL, FAIL_AFTER_FIRST_TOKEN -> throw new IllegalStateException("가짜 모델 실패");
            case SLOW -> {
                sleep(delay);
                yield response(answer(prompt));
            }
            case EMPTY -> response("");
            case TOOL_ANSWER -> response(answer(prompt));
        };
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            record(prompt);
            return switch (mode.get()) {
                case FAIL -> Flux.error(new IllegalStateException("가짜 모델 실패"));
                case SLOW -> Flux.just(response("늦은 답")).delaySubscription(delay);
                case EMPTY -> Flux.just(response(""));
                case FAIL_AFTER_FIRST_TOKEN -> Flux.concat(Flux.just(response(ANSWER_PREFIX)),
                        Flux.error(new IllegalStateException("가짜 모델 도중 실패")));
                case TOOL_ANSWER -> {
                    String answer = answer(prompt);
                    // 조각 셋: 앞 공백이 든 조각을 일부러 만든다
                    int cut = answer.indexOf(' ', ANSWER_PREFIX.length());
                    List<String> tokens = cut < 0 ? List.of(answer)
                            : List.of(answer.substring(0, ANSWER_PREFIX.length()), answer.substring(ANSWER_PREFIX.length(), cut),
                                    answer.substring(cut));
                    yield Flux.fromIterable(tokens).map(FakeChatModel::response);
                }
            };
        });
    }

    private void record(Prompt prompt) {
        lastPrompt.set(prompt);
        calls.incrementAndGet();
    }

    // 실제 모델처럼 Tool을 부르고 결과로 답한다
    private String answer(Prompt prompt) {
        ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
        ToolCallback searchFaq = options.getToolCallbacks().stream()
                .filter(callback -> callback.getToolDefinition().name().equals("searchFaq"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("searchFaq Tool이 요청에 없습니다"));
        Map<String, Object> context = options.getToolContext() == null ? Map.of() : options.getToolContext();
        String question = prompt.getUserMessage().getText();
        String result = searchFaq.call(jsonMapper.writeValueAsString(Map.of("question", question)), new ToolContext(context));
        JsonNode hits = jsonMapper.readTree(result);
        if (hits.isEmpty()) {
            return NO_ANSWER;
        }
        return ANSWER_PREFIX + " " + hits.get(0).get("answer").asString();
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("가짜 모델 대기 중단", e);
        }
    }
}

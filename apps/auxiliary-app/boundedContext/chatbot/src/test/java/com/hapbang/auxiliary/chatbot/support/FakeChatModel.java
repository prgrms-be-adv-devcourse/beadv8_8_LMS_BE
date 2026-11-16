package com.hapbang.auxiliary.chatbot.support;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트 전용 가짜 채팅 모델(#715). 운영 소스에 두지 않는다(docs/dev/testing.md).
 * <p>
 * 기본 동작({@link Mode#TOOL_ANSWER})은 실제 모델이 하는 일을 흉내 낸다. 요청에 실린 {@code searchFaq} Tool을 사용자 질문으로 부르고
 * (요청의 ToolContext를 그대로 넘긴다), 결과의 첫 FAQ 답변으로 답한다. 결과가 없으면 안내 문구로 답한다. 받은 요청은 {@link #lastPrompt()}로 본다.
 * <p>
 * {@link Mode#TOOL_CALLS}는 실제 공급자처럼 Tool 호출을 <b>요청</b>한다(#720). Tool은 Spring AI의 {@code ToolCallingAdvisor}가
 * 실행하고 결과를 다시 보낸다. 그래서 Tool 호출 상한({@code spring.ai.tools.limits.*})과 대화의 Tool 등록을 실제 경로로 확인한다.
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
        /** {@link #release()} 전까지 응답하지 않는다(시간에 기대지 않고 "느린 모델"을 만든다) */
        HANG,
        /** 스트림: 첫 조각을 보낸 뒤 오류 */
        FAIL_AFTER_FIRST_TOKEN,
        /** 빈 답 */
        EMPTY,
        /** {@link #reply(String)}로 정한 글을 그대로 답한다(구조화 응답 {@code .entity()} 확인, #719) */
        REPLY,
        /**
         * {@link #toolCalls}로 정한 Tool을 한 번에 하나씩 호출 요청한다. 다 부르면 정한 글로 답하거나(스트림도 같다), 실패하게
         * 정했으면 실패한다. {@link #toolLoop}는 같은 Tool을 끝없이 요청한다
         */
        TOOL_CALLS
    }

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.TOOL_ANSWER);
    private final AtomicReference<Prompt> lastPrompt = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> reply = new AtomicReference<>("");
    private final AtomicReference<List<String>> scriptedTools = new AtomicReference<>(List.of());
    private volatile boolean loopTools;
    private volatile boolean failAfterTools;
    private volatile boolean failAfterFirstToken;
    private volatile CompletableFuture<Void> gate = CompletableFuture.completedFuture(null);

    public void mode(Mode mode) {
        this.mode.set(mode);
    }

    /** {@link Mode#HANG} 호출을 막아 둔다. 같은 테스트에서 {@link #release()}로 푼다 */
    public void hold() {
        gate = new CompletableFuture<>();
    }

    /** 막아 둔 호출을 모두 풀어 준다 */
    public void release() {
        gate.complete(null);
    }

    /** 정한 글을 그대로 답하게 한다 */
    public void reply(String text) {
        reply.set(text);
        mode.set(Mode.REPLY);
    }

    /**
     * 정한 Tool들을 차례로 호출 요청한 뒤 {@code answer}로 답한다.
     *
     * @param failAfterTools 다 부른 뒤 답하지 않고 실패한다(카드를 만든 뒤 LLM 실패)
     */
    public void toolCalls(List<String> tools, String answer, boolean failAfterTools) {
        scriptedTools.set(List.copyOf(tools));
        reply.set(answer);
        loopTools = false;
        this.failAfterTools = failAfterTools;
        failAfterFirstToken = false;
        mode.set(Mode.TOOL_CALLS);
    }

    /** 스트림: 정한 Tool을 다 부른 뒤 첫 조각을 보내고 실패한다 */
    public void toolCallsThenFailAfterFirstToken(List<String> tools, String answer) {
        toolCalls(tools, answer, false);
        failAfterFirstToken = true;
    }

    /** 같은 Tool을 끝없이 호출 요청한다(Tool 호출 상한 확인) */
    public void toolLoop(String tool) {
        toolCalls(List.of(tool), "", false);
        loopTools = true;
    }

    public void reset() {
        mode.set(Mode.TOOL_ANSWER);
        reply.set("");
        scriptedTools.set(List.of());
        loopTools = false;
        failAfterTools = false;
        failAfterFirstToken = false;
        lastPrompt.set(null);
        calls.set(0);
        release();
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
            case HANG -> {
                await(gate);
                yield response(answer(prompt));
            }
            case EMPTY -> response("");
            case REPLY -> response(reply.get());
            case TOOL_ANSWER -> response(answer(prompt));
            case TOOL_CALLS -> {
                ChatResponse toolCall = nextToolCall(prompt);
                if (toolCall != null) {
                    yield toolCall;
                }
                if (failAfterTools) {
                    throw new IllegalStateException("가짜 모델: Tool 뒤 실패");
                }
                yield response(reply.get());
            }
        };
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            record(prompt);
            return switch (mode.get()) {
                case FAIL -> Flux.error(new IllegalStateException("가짜 모델 실패"));
                case HANG -> Flux.just(response("늦은 답")).delaySubscription(Mono.fromFuture(gate, true));
                case EMPTY -> Flux.just(response(""));
                case REPLY -> Flux.just(response(reply.get()));
                case TOOL_CALLS -> {
                    ChatResponse toolCall = nextToolCall(prompt);
                    if (toolCall != null) {
                        yield Flux.just(toolCall);
                    }
                    if (failAfterTools) {
                        yield Flux.error(new IllegalStateException("가짜 모델: Tool 뒤 실패"));
                    }
                    if (failAfterFirstToken) {
                        yield Flux.concat(Flux.just(response(reply.get())),
                                Flux.error(new IllegalStateException("가짜 모델: Tool 뒤 도중 실패")));
                    }
                    yield Flux.just(response(reply.get()));
                }
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

    // 이번 요청에서 이미 받은 Tool 결과 수만큼 정한 Tool을 지나, 다음 Tool 호출을 요청한다. 다 불렀으면 null
    private @Nullable ChatResponse nextToolCall(Prompt prompt) {
        long answered = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).count();
        List<String> tools = scriptedTools.get();
        if (!loopTools && answered >= tools.size()) {
            return null;
        }
        String tool = loopTools ? tools.getFirst() : tools.get((int) answered);
        AssistantMessage call = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + answered, "function", tool, "{}")))
                .build();
        return new ChatResponse(List.of(new Generation(call)));
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

    private static void await(CompletableFuture<Void> gate) {
        try {
            gate.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("가짜 모델 대기 중단", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }
}

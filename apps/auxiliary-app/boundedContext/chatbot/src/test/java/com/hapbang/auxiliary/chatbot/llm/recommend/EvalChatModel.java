package com.hapbang.auxiliary.chatbot.llm.recommend;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import com.hapbang.auxiliary.chatbot.app.RecommendEvalAccess;

/**
 * 리랭킹 평가용 채팅 모델 감싸기(#867 2단계). 실제 모델 앞에 두고 두 가지를 한다.
 * <ul>
 *     <li><b>회원 한 명 안에서 같은 요청은 다시 보내지 않는다</b>({@link #startMember()}부터). 쿼리 확장은 온도 때문에 답이 매번 다를 수 있어,
 *     버튼 경로가 쓴 확장 결과를 다시 받아 같은 후보 풀(코드 순서)을 만들 때 쓴다. 운영 코드를 평가용으로 바꾸지 않으려는 것이다.</li>
 *     <li>실제로 보낸 호출의 걸린 시간·토큰과 리랭킹 답 원문을 남긴다(후보 밖 순번·이유 길이를 센다).</li>
 * </ul>
 * 운영 호출 장치({@code LlmCallGuard})는 호출마다 새 가상 스레드를 만든다. 회원 범위를 {@link InheritableThreadLocal}에 두어 그 스레드가
 * 물려받게 한다(스레드를 다시 쓰는 풀이면 이 방식이 맞지 않는다). 글·키·요청 헤더는 남기지 않는다.
 */
final class EvalChatModel implements ChatModel {

    /** 실제로 보낸 호출 하나 */
    record Call(boolean rerank, long millis, long promptTokens, long completionTokens) {
    }

    /**
     * 리랭킹 답 원문과 그 요청이 보여 준 후보 수.
     *
     * @param shown LLM에 보여 준 후보 수(고르지 않을 때는 추천 수만 보여 준다). 이보다 큰 순번은 후보 밖이다
     */
    record RerankAnswer(String output, int shown) {
    }

    // 회원 한 명의 기억. 호출 스레드가 같은 객체를 물려받아 쓴다
    private static final class Scope {
        private final Map<String, Object> memo = new ConcurrentHashMap<>();
        private volatile @Nullable RerankAnswer lastRerank;
    }

    private final ChatModel delegate;
    private final InheritableThreadLocal<@Nullable Scope> scope = new InheritableThreadLocal<>();
    private final Queue<Call> calls = new ConcurrentLinkedQueue<>();

    EvalChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    /** 이 스레드에서 새 회원을 시작한다(앞 회원의 기억을 버린다) */
    void startMember() {
        scope.set(new Scope());
    }

    /** 이 스레드의 회원에게서 마지막으로 받은 리랭킹 답. 없으면 null */
    @Nullable RerankAnswer lastRerank() {
        Scope current = scope.get();
        return current == null ? null : current.lastRerank;
    }

    /** 실제로 보낸 호출을 모두 꺼낸다(꺼낸 것은 지운다) */
    List<Call> drainCalls() {
        List<Call> drained = new ArrayList<>();
        Call call;
        while ((call = calls.poll()) != null) {
            drained.add(call);
        }
        return drained;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Scope current = scope.get();
        String key = key(prompt);
        Object remembered = current == null ? null : current.memo.get(key);
        if (remembered instanceof ChatResponse response) {
            return response;
        }
        if (remembered instanceof RuntimeException failure) {
            throw failure;
        }
        boolean rerank = RecommendEvalAccess.isRerankPrompt(prompt.getSystemMessage().getText());
        long started = System.nanoTime();
        try {
            ChatResponse response = delegate.call(prompt);
            long millis = (System.nanoTime() - started) / 1_000_000;
            Usage usage = response.getMetadata().getUsage();
            calls.add(new Call(rerank, millis, tokens(usage == null ? null : usage.getPromptTokens()),
                    tokens(usage == null ? null : usage.getCompletionTokens())));
            if (current != null) {
                if (rerank && response.getResult() != null) {
                    current.lastRerank = new RerankAnswer(response.getResult().getOutput().getText(),
                            RecommendEvalAccess.shownCandidates(prompt.getUserMessage().getText()));
                }
                current.memo.put(key, response);
            }
            return response;
        } catch (RuntimeException e) {
            calls.add(new Call(rerank, (System.nanoTime() - started) / 1_000_000, 0, 0));
            if (current != null) {
                current.memo.put(key, e);
            }
            throw e;
        }
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    // 메시지 글과 모델·온도로 같은 요청인지 본다
    private static String key(Prompt prompt) {
        StringBuilder key = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            key.append(message.getMessageType()).append('\n').append(message.getText()).append('\n');
        }
        ChatOptions options = prompt.getOptions();
        if (options != null) {
            key.append(options.getModel()).append('|').append(options.getTemperature());
        }
        return key.toString();
    }

    private static long tokens(@Nullable Integer value) {
        return value == null ? 0 : value;
    }
}

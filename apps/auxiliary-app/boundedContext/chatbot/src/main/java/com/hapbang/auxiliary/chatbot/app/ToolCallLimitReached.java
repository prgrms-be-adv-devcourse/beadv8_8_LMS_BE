package com.hapbang.auxiliary.chatbot.app;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;

/**
 * 한 요청의 Tool 호출이 상한({@code spring.ai.tools.limits.*})을 넘었다 (#720).
 * <p>
 * Spring AI 2.0.1의 {@code ToolCallingAdvisor}는 상한 초과 예외({@link ToolCallLimitExceededException})를 호출자에게 던지지 않고,
 * 종료 사유가 {@link ToolCallLimitExceededException#FINISH_REASON}인 정상 응답(글은 상한 안내문)으로 바꿔 돌려준다(테스트로 확인).
 * 대화는 그 응답을 이 예외로 바꿔 실패로 처리한다. {@link LlmCallGuard}는 회로 실패로 세지 않는다.
 */
public final class ToolCallLimitReached extends RuntimeException {

    ToolCallLimitReached() {
        super("Tool 호출 상한을 넘었다");
    }

    /** 응답이 Tool 호출 상한으로 끝났는지 */
    static boolean isLimitResponse(@Nullable ChatResponse response) {
        return response != null && response.getResult() != null && response.getResult().getMetadata() != null
                && ToolCallLimitExceededException.FINISH_REASON.equals(response.getResult().getMetadata().getFinishReason());
    }
}

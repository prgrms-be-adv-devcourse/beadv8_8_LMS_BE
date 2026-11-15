package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.FallbackReason;

import tools.jackson.databind.json.JsonMapper;

/** 모델은 답했지만 쓸 수 없는 실패의 분류 (#835): 예외 자신과 원인 사슬 전체를 본다 */
class LlmCallGuardFailureKindTest {

    @Test
    @DisplayName("Tool 상한·Jackson 형식 오류는 감싸여 있어도 찾고, Tool 상한이 형식 오류보다 먼저다. 그 밖은 null")
    void classifiesThroughCauseChain() {
        Throwable formatError = catchThrowable(() -> JsonMapper.builder().build().readTree("{"));
        RuntimeException wrappedFormat = new IllegalStateException("변환 실패", formatError);
        RuntimeException wrappedLimit = new RuntimeException(new ToolCallLimitReached());
        ToolCallLimitReached limitOverFormat = new ToolCallLimitReached();
        limitOverFormat.initCause(wrappedFormat);

        assertThat(LlmCallGuard.modelAnsweredFailure(new RuntimeException(limitOverFormat)))
                .isEqualTo(FallbackReason.TOOL_LIMIT);
        assertThat(LlmCallGuard.modelAnsweredFailure(formatError)).isEqualTo(FallbackReason.FORMAT);
        assertThat(LlmCallGuard.modelAnsweredFailure(wrappedFormat)).isEqualTo(FallbackReason.FORMAT);
        assertThat(LlmCallGuard.modelAnsweredFailure(wrappedLimit)).isEqualTo(FallbackReason.TOOL_LIMIT);
        assertThat(LlmCallGuard.modelAnsweredFailure(new RuntimeException(new RuntimeException(wrappedLimit))))
                .isEqualTo(FallbackReason.TOOL_LIMIT);
        assertThat(LlmCallGuard.modelAnsweredFailure(new IllegalStateException("모델 장애"))).isNull();
        assertThat(LlmCallGuard.modelAnsweredFailure(null)).isNull();
        assertThat(LlmCallGuard.isFormatError(wrappedFormat)).isTrue();
        assertThat(LlmCallGuard.isFormatError(wrappedLimit)).isFalse();
        assertThat(LlmCallGuard.isFormatError(null)).isFalse();
    }
}

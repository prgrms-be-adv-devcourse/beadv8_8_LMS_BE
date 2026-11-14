package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.chatbot.support.FakeAiModelsConfig;
import com.hapbang.auxiliary.chatbot.support.FakeChatModel;
import com.hapbang.auxiliary.chatbot.support.RecommendRows;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 강의 추천 Tool (#718, #720). 후보는 활동이 없는 회원의 인기순(ES 없이 사본만)으로 만든다.
 * 후보 생성 규칙은 {@link LectureRecommendUseCaseTest}, 버튼 경로(리랭킹 + 이유)는 {@link LectureRecommendButtonTest}가 본다.
 */
@Import(FakeAiModelsConfig.class)
class LectureRecommendToolsTest extends ChatbotIntegrationTestBase {

    @Autowired
    private LectureRecommendTools tools;

    @Autowired
    private FakeChatModel chatModel;

    @Autowired
    private MeterRegistry meterRegistry;

    private RecommendRows data;

    @BeforeEach
    void setUp() {
        chatModel.reset();
        data = new RecommendRows(jdbcTemplate);
        data.member(1);
        data.member(100);
        data.member(200);
        data.lecture(10, 100, "스프링 입문", "스프링 부트로 웹 서비스를 만든다", List.of(), true, false, false);
        data.lecture(11, 200, "파이썬 기초", "파이썬 문법", List.of(), true, false, false);
        data.purchase(1, 50, 10, "OWNED", RecommendRows.AT);
    }

    @Test
    @DisplayName("Tool 인자 스키마에는 관심사만 있고 회원 ID·ToolContext가 없다(모델이 회원 ID를 넘길 수 없다)")
    void schemaHasNoMemberId() {
        ToolCallback[] callbacks = ToolCallbacks.from(tools);

        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("recommendLectures");
        assertThat(callbacks[0].getToolDefinition().inputSchema()).contains("\"interest\"")
                .doesNotContain("memberId").doesNotContain("userId").doesNotContain("toolContext");
    }

    @Test
    @DisplayName("회원 ID는 ToolContext에서 꺼낸다. 결과는 후보·근거 코드·정해진 이유 문장(LLM을 부르지 않는다). Tool 호출 수를 센다")
    void usesToolContextMemberId() {
        ToolCallback callback = ToolCallbacks.from(tools)[0];

        double toolCalls = toolCalls();

        String result = callback.call("{}", new ToolContext(ChatToolContext.of(1L, List.of(), new ChatCardCollector())));

        assertThat(result).contains("\"lectureId\":10", "\"lectureId\":11", "\"needsInterest\":true", "\"basis\":\"POPULAR\"",
                "요즘 많이 듣는 강의예요.");
        assertThat(chatModel.calls()).isZero();
        assertThat(toolCalls()).isEqualTo(toolCalls + 1);
    }

    @Test
    @DisplayName("ToolContext가 없거나 회원 ID가 없으면 거절한다")
    void rejectsWithoutMemberId() {
        ToolCallback callback = ToolCallbacks.from(tools)[0];

        assertThatThrownBy(() -> callback.call("{}")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> callback.call("{}", new ToolContext(Map.of("other", 1L))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> tools.recommendLectures(null, new ToolContext(Map.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(ChatToolContext.MEMBER_ID);
    }

    @Test
    @DisplayName("검색이 잠시 실패하면(ES에 닿지 않음) Tool 결과에 POPULAR_TEMPORARY와 안내 한 줄(guide)을 넣는다. 정상 인기순이면 guide가 없다")
    void temporaryPopularGuide() {
        data.member(2);
        data.purchase(2, 2, 11, "OWNED", RecommendRows.AT);
        ToolCallback callback = ToolCallbacks.from(tools)[0];

        String temporary = callback.call("{}", new ToolContext(ChatToolContext.of(2L, List.of(), new ChatCardCollector())));
        String popular = callback.call("{}", new ToolContext(ChatToolContext.of(1L, List.of(), new ChatCardCollector())));

        assertThat(temporary).contains("\"basis\":\"POPULAR_TEMPORARY\"", RecommendGuide.TEMPORARY);
        assertThat(popular).contains("\"basis\":\"POPULAR\"").doesNotContain(RecommendGuide.TEMPORARY);
    }

    private double toolCalls() {
        var counter = meterRegistry.find("hapbang.chatbot.tool.calls").tag("tool", "recommendLectures").counter();
        return counter == null ? 0 : counter.count();
    }
}

package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.hapbang.auxiliary.chatbot.domain.LectureRecommendReason;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.chatbot.support.FakeAiModelsConfig;
import com.hapbang.auxiliary.chatbot.support.FakeChatModel;
import com.hapbang.auxiliary.chatbot.support.RecommendRows;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 강의 추천 Tool과 버튼 경로의 LLM 이유 문장 (#718). 후보는 활동이 없는 회원의 인기순(ES 없이 사본만)으로 만든다.
 * 후보 생성 규칙 자체는 {@link LectureRecommendUseCaseTest}가 본다.
 */
@Import(FakeAiModelsConfig.class)
class LectureRecommendToolsTest extends ChatbotIntegrationTestBase {

    @Autowired
    private LectureRecommendTools tools;

    @Autowired
    private LectureRecommendExplainUseCase explainUseCase;

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

        String result = callback.call("{}", new ToolContext(ChatToolContext.of(1L, List.of())));

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
    @DisplayName("버튼 경로: LLM 문장을 후보 강의에만 붙인다. 후보 밖 ID·빈 문장은 버리고, 빠진 강의는 정해진 문장, 긴 문장은 80자로 자른다")
    void explainFiltersOutsideCandidates() {
        String longReason = "가".repeat(120);
        chatModel.reply("""
                [{"lectureId": 10, "reason": "%s"},
                 {"lectureId": 999, "reason": "후보 밖 강의"},
                 {"lectureId": 11, "reason": "  "}]""".formatted(longReason));

        LectureRecommendation result = explainUseCase.recommend(1, null);

        assertThat(result.items()).extracting(LectureRecommendation.Item::lectureId).containsExactly(10L, 11L);
        assertThat(result.items().get(0).reasonText()).isEqualTo("가".repeat(80));
        assertThat(result.items().get(1).reasonText()).isEqualTo(LectureRecommendReason.POPULAR.sentence(null));
        assertThat(chatModel.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("버튼 경로 프롬프트에는 후보 강의의 제목·소개와 근거 설명만 있고 회원 ID·회원이 산 강의는 없다")
    void explainPromptHasNoMemberInfo() {
        // 회원 ID는 프롬프트의 다른 숫자와 겹치지 않는 값으로 둔다
        long member = 90017;
        data.member(member);
        data.lecture(12, 100, "회원이 산 강의", "비밀", List.of(), true, false, false);
        data.purchase(2, member, 12, "OWNED", RecommendRows.AT);
        chatModel.reply("[]");

        explainUseCase.recommend(member, null);

        String prompt = chatModel.lastPrompt().getInstructions().stream().map(Message::getText)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(prompt).contains("강의 ID: 10", "스프링 입문", "스프링 부트로 웹 서비스를 만든다",
                LectureRecommendReason.POPULAR.description());
        assertThat(prompt).doesNotContain("회원이 산 강의", "회원 ID", String.valueOf(member));
    }

    @Test
    @DisplayName("버튼 경로: LLM이 실패하면 후보와 정해진 문장 그대로, fallback 지표(lecture_recommend, error)")
    void explainFallsBackOnFailure() {
        chatModel.mode(FakeChatModel.Mode.FAIL);
        double before = fallbacks();

        LectureRecommendation result = explainUseCase.recommend(1, null);

        assertThat(result.items()).extracting(LectureRecommendation.Item::lectureId).containsExactly(10L, 11L);
        assertThat(result.items()).allSatisfy(item ->
                assertThat(item.reasonText()).isEqualTo(LectureRecommendReason.POPULAR.sentence(null)));
        assertThat(fallbacks()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("버튼 경로: 긴 이유 문장과 프롬프트의 긴 소개는 코드포인트로 잘라 이모지를 반으로 자르지 않는다")
    void explainCutsByCodePoints() {
        String emoji = "😀";
        data.lecture(13, 200, "이모지 강의", "가".repeat(299) + emoji + "꼬리", List.of(), true, false, false);
        chatModel.reply("""
                [{"lectureId": 10, "reason": "%s"}]""".formatted("나".repeat(79) + emoji + "넘침"));

        LectureRecommendation result = explainUseCase.recommend(1, null);

        assertThat(result.items().getFirst().lectureId()).isEqualTo(10L);
        assertThat(result.items().getFirst().reasonText()).isEqualTo("나".repeat(79) + emoji);
        String prompt = chatModel.lastPrompt().getUserMessage().getText();
        assertThat(prompt).contains("가".repeat(299) + emoji).doesNotContain("꼬리");
        // 짝 잃은 서로게이트는 코드포인트로 세어도 서로게이트 구간에 남는다
        assertThat(prompt.codePoints()).noneMatch(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE);
    }

    @Test
    @DisplayName("버튼 경로: 추천할 강의가 없으면(숨김 회원) LLM을 부르지 않는다")
    void explainSkipsLlmWhenEmpty() {
        data.member(2, "WITHDRAWN");

        LectureRecommendation result = explainUseCase.recommend(2, null);

        assertThat(result.items()).isEmpty();
        assertThat(chatModel.calls()).isZero();
    }

    private double fallbacks() {
        var counter = meterRegistry.find("hapbang.chatbot.fallbacks").tag("feature", "lecture_recommend").tag("reason", "error")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private double toolCalls() {
        var counter = meterRegistry.find("hapbang.chatbot.tool.calls").tag("tool", "recommendLectures").counter();
        return counter == null ? 0 : counter.count();
    }
}

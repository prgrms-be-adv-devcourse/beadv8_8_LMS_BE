package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.auxiliary.chatbot.domain.InfluencerReason;
import com.hapbang.auxiliary.chatbot.support.FakeChatModel;
import com.hapbang.auxiliary.chatbot.support.FakeEmbeddingModel;
import com.hapbang.auxiliary.global.auth.GatewayUserHeaderFilter;
import com.hapbang.shared.member.domain.Role;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 인플루언서 추천 API·LLM 설명·Tool (#719). 로그인은 운영처럼 Gateway 사용자 헤더로 한다.
 * <p>
 * 회원: 1 요청 인플루언서(게임), 2 게임, 3 음악. 자기소개에는 프롬프트에 새면 안 되는 문장을 넣어 둔다.
 */
class InfluencerRecommendApiTest extends InfluencerIntegrationTestBase {

    private static final String BASE = "/api/v1/chatbot/recommendations/influencers";
    private static final String SECRET_BIO = "비밀소개문장";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InfluencerRecommendTools tools;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUpInfluencers() {
        influencer(1L, "GAME", SECRET_BIO + "1");
        influencer(2L, "GAME", SECRET_BIO + "2");
        influencer(3L, "MUSIC", SECRET_BIO + "3");
        index(1L, 2L, 3L);
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void unauthorized() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/explained")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("위젯 API: LLM 없이 후보만. 회원 ID·분야 이름·완료 수·평균 별점·이유 코드, explanation 없음")
    void widget() throws Exception {
        mockMvc.perform(get(BASE).with(login(1L, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.needsInterest").value(false))
                .andExpect(jsonPath("$.data.fallback").value(false))
                .andExpect(jsonPath("$.data.items[*].memberId", contains(2, 3)))
                .andExpect(jsonPath("$.data.items[0].categoryNames", contains("게임")))
                .andExpect(jsonPath("$.data.items[0].completedAppearances").value(0))
                .andExpect(jsonPath("$.data.items[0].averageRating").isEmpty())
                .andExpect(jsonPath("$.data.items[0].reasons", contains("SIMILAR_CATEGORY")))
                .andExpect(jsonPath("$.data.items[0]", not(hasKey("explanation"))))
                .andExpect(jsonPath("$.data", not(hasKey("notice"))));
        assertThat(chatModel.calls()).isZero();
    }

    @Test
    @DisplayName("요청 회원의 사본이 아직 없으면(가입 직후) 빈 목록과 정해진 안내 문구")
    void requesterNotSyncedYet() throws Exception {
        mockMvc.perform(get(BASE).with(login(404L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.notice").value(InfluencerRecommendMapper.REQUESTER_HIDDEN_NOTICE));
    }

    @Test
    @DisplayName("설명 API: LLM 이유를 후보 순번으로 받아 붙인다. 범위 밖 순번·빈 문장은 버리고 이유 코드 문장으로 채운다. 프롬프트에 닉네임·자기소개 원문이 없다")
    void explained() throws Exception {
        chatModel.reply("""
                [{"candidate": 1, "reason": "게임 방송 분야가 같아 합이 잘 맞을 거예요."},
                 {"candidate": 9, "reason": "범위 밖 후보"},
                 {"candidate": 2, "reason": "  "}]""");

        mockMvc.perform(post(BASE + "/explained").with(login(1L, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fallback").value(false))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].memberId").value(2))
                .andExpect(jsonPath("$.data.items[0].explanation").value("게임 방송 분야가 같아 합이 잘 맞을 거예요."))
                .andExpect(jsonPath("$.data.items[1].explanation")
                        .value(InfluencerReason.SIMILAR_CATEGORY.getDescription()));

        String prompt = promptText();
        assertThat(prompt).contains("후보 1 | 분야: 게임", "후보 2 | 분야: 음악");
        assertThat(prompt).doesNotContain(SECRET_BIO, "회원1", "회원2", "회원3", "memberId");
    }

    @Test
    @DisplayName("설명 API: 프롬프트에 회원 ID를 넣지 않는다(모델이 회원 ID를 알 수 없다)")
    void promptHasNoMemberIds() throws Exception {
        influencer(90017L, "GAME", "롤");
        index(90017L);
        chatModel.reply("[]");

        mockMvc.perform(post(BASE + "/explained").with(login(1L, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].memberId", contains(90017, 2, 3)));

        assertThat(promptText()).contains("후보 1 | 분야: 게임").doesNotContain("90017");
    }

    @Test
    @DisplayName("설명 API: 후보 순번을 말한 문장은 버린다. 쓸 만한 답이 하나도 없으면 fallback=true")
    void explainedDropsCandidateMentions() throws Exception {
        chatModel.reply("""
                [{"candidate": 1, "reason": "후보 1은 게임 방송을 해요."},
                 {"candidate": 2, "reason": "후보2번과 잘 맞아요."}]""");

        mockMvc.perform(post(BASE + "/explained").with(login(1L, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fallback").value(true))
                .andExpect(jsonPath("$.data.items[0].explanation")
                        .value(InfluencerReason.SIMILAR_CATEGORY.getDescription()))
                .andExpect(jsonPath("$.data.items[1].explanation")
                        .value(InfluencerReason.SIMILAR_CATEGORY.getDescription()));
    }

    @Test
    @DisplayName("설명 API: 문장에 든 수치(출연 3회, 2번, 3명, 별점 4.5점)는 그대로 쓴다")
    void explainedKeepsNumbers() throws Exception {
        chatModel.reply("""
                [{"candidate": 1, "reason": "완료한 출연이 3회이고 평균 별점이 4.5점이에요."},
                 {"candidate": 2, "reason": "음악 방송을 2번 했고 3명과 함께했어요."}]""");

        mockMvc.perform(post(BASE + "/explained").with(login(1L, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fallback").value(false))
                .andExpect(jsonPath("$.data.items[0].explanation").value("완료한 출연이 3회이고 평균 별점이 4.5점이에요."))
                .andExpect(jsonPath("$.data.items[1].explanation").value("음악 방송을 2번 했고 3명과 함께했어요."));
    }

    @Test
    @DisplayName("Tool: 관심사 문장은 설정 길이(100자)까지만 임베딩한다")
    void toolTruncatesInterest() {
        ToolCallback callback = ToolCallbacks.from(tools)[0];
        String longInterest = "요".repeat(150);

        callback.call("{\"interest\": \"" + longInterest + "\"}",
                new ToolContext(ChatToolContext.of(1L, List.of())));

        assertThat(embeddingModel.texts().getLast()).isEqualTo(FakeEmbeddingModel.query("요".repeat(100)));
    }

    @Test
    @DisplayName("Tool: 관심사 문장은 글자 단위로 자른다. 100번째 글자가 이모지(char 2개)여도 반으로 갈리지 않는다")
    void toolTruncatesInterestByCharacter() {
        ToolCallback callback = ToolCallbacks.from(tools)[0];
        String emoji = "🍳";
        String interest = "요".repeat(99) + emoji + "요".repeat(10);

        callback.call("{\"interest\": \"" + interest + "\"}",
                new ToolContext(ChatToolContext.of(1L, List.of())));

        assertThat(embeddingModel.texts().getLast()).isEqualTo(FakeEmbeddingModel.query("요".repeat(99) + emoji));
    }

    @Test
    @DisplayName("설명 API: LLM이 실패하면 fallback=true이고 후보는 그대로, 이유는 정해진 문장")
    void explainedFallback() throws Exception {
        chatModel.mode(FakeChatModel.Mode.FAIL);

        mockMvc.perform(post(BASE + "/explained").with(login(1L, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fallback").value(true))
                .andExpect(jsonPath("$.data.items[*].memberId", contains(2, 3)))
                .andExpect(jsonPath("$.data.items[0].explanation")
                        .value(InfluencerReason.SIMILAR_CATEGORY.getDescription()));
    }

    @Test
    @DisplayName("설명 API는 회원마다 분당 10회, 위젯 API는 60회로 제한한다(넘으면 429). 두 한도는 따로 센다")
    void rateLimit() throws Exception {
        chatModel.mode(FakeChatModel.Mode.FAIL);
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post(BASE + "/explained").with(login(21L, Role.USER))).andExpect(status().isOk());
        }
        mockMvc.perform(post(BASE + "/explained").with(login(21L, Role.USER))).andExpect(status().isTooManyRequests());
        // 설명 한도를 다 쓴 회원도 위젯은 부를 수 있다
        mockMvc.perform(get(BASE).with(login(21L, Role.USER))).andExpect(status().isOk());

        for (int i = 0; i < 60; i++) {
            mockMvc.perform(get(BASE).with(login(22L, Role.USER))).andExpect(status().isOk());
        }
        mockMvc.perform(get(BASE).with(login(22L, Role.USER))).andExpect(status().isTooManyRequests());
        // 위젯 한도를 다 쓴 회원도 설명 버튼은 부를 수 있다
        mockMvc.perform(post(BASE + "/explained").with(login(22L, Role.USER))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Tool: 인자 스키마에 회원 ID가 없고, 회원 ID는 ToolContext에서만 꺼낸다(없으면 거절). 관심사는 선택 인자다")
    void tool() {
        ToolCallback callback = ToolCallbacks.from(tools)[0];
        String schema = callback.getToolDefinition().inputSchema();

        assertThat(callback.getToolDefinition().name()).isEqualTo("recommendInfluencers");
        assertThat(schema).contains("interest").doesNotContainIgnoringCase("userId").doesNotContainIgnoringCase("memberId");
        // 다른 값만 있고 회원 ID가 없는 ToolContext(빈 ToolContext는 Spring AI가 먼저 거절한다)
        assertThatThrownBy(() -> callback.call("{}", new ToolContext(Map.of(ChatToolContext.FAQ_SOURCES, List.of()))))
                .hasRootCauseInstanceOf(IllegalStateException.class);

        // 설명 글에 줄 합치기로 생긴 연속 공백이 없다(모델 프롬프트로 그대로 나간다)
        assertThat(callback.getToolDefinition().description()).doesNotContain("  ");

        double before = requests();
        String result = callback.call("{}", new ToolContext(ChatToolContext.of(1L, List.of())));
        assertThat(result).contains("\"memberId\":2", "SIMILAR_CATEGORY", "\"needsInterest\":false")
                .doesNotContain(SECRET_BIO);
        // Tool 경로도 API와 같은 사용 지표로 센다
        assertThat(requests()).isEqualTo(before + 1);
    }

    private double requests() {
        Counter counter = meterRegistry.find("hapbang.chatbot.requests").tag("feature", "influencer_recommend").counter();
        return counter == null ? 0 : counter.count();
    }

    private String promptText() {
        StringBuilder text = new StringBuilder();
        for (Message message : chatModel.lastPrompt().getInstructions()) {
            text.append(message.getText()).append('\n');
        }
        return text.toString();
    }

    private static RequestPostProcessor login(long userId, Role role) {
        return request -> {
            request.addHeader(GatewayUserHeaderFilter.USER_ID, String.valueOf(userId));
            request.addHeader(GatewayUserHeaderFilter.USER_ROLE, role.name());
            return request;
        };
    }
}

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
import com.hapbang.auxiliary.global.auth.GatewayUserHeaderFilter;
import com.hapbang.shared.member.domain.Role;

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
                .andExpect(jsonPath("$.data.items[0]", not(hasKey("explanation"))));
        assertThat(chatModel.calls()).isZero();
    }

    @Test
    @DisplayName("설명 API: LLM 이유를 붙인다. 후보 밖 회원·빈 문장은 버리고 이유 코드 문장으로 채운다. 프롬프트에 닉네임·자기소개 원문이 없다")
    void explained() throws Exception {
        chatModel.reply("""
                [{"memberId": 2, "reason": "게임 방송 분야가 같아 합이 잘 맞을 거예요."},
                 {"memberId": 999, "reason": "후보 밖 회원"},
                 {"memberId": 3, "reason": "  "}]""");

        mockMvc.perform(post(BASE + "/explained").with(login(1L, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fallback").value(false))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].memberId").value(2))
                .andExpect(jsonPath("$.data.items[0].explanation").value("게임 방송 분야가 같아 합이 잘 맞을 거예요."))
                .andExpect(jsonPath("$.data.items[1].explanation")
                        .value(InfluencerReason.SIMILAR_CATEGORY.getDescription()));

        String prompt = promptText();
        assertThat(prompt).contains("memberId=2", "분야: 게임", "memberId=3", "분야: 음악");
        assertThat(prompt).doesNotContain(SECRET_BIO, "회원1", "회원2", "회원3", "memberId=1");
    }

    @Test
    @DisplayName("설명 API: 후보 회원 ID 숫자를 말한 문장은 버린다. 쓸 만한 답이 하나도 없으면 fallback=true")
    void explainedDropsIdLeaks() throws Exception {
        chatModel.reply("""
                [{"memberId": 2, "reason": "회원 2번은 게임 방송을 해요."},
                 {"memberId": 3, "reason": "3 님과 잘 맞아요."}]""");

        mockMvc.perform(post(BASE + "/explained").with(login(1L, Role.INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fallback").value(true))
                .andExpect(jsonPath("$.data.items[0].explanation")
                        .value(InfluencerReason.SIMILAR_CATEGORY.getDescription()))
                .andExpect(jsonPath("$.data.items[1].explanation")
                        .value(InfluencerReason.SIMILAR_CATEGORY.getDescription()));
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
    @DisplayName("설명 API는 회원마다 호출 수를 제한한다(넘으면 429). 위젯 API는 제한하지 않는다")
    void rateLimit() throws Exception {
        chatModel.mode(FakeChatModel.Mode.FAIL);
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post(BASE + "/explained").with(login(21L, Role.USER))).andExpect(status().isOk());
        }
        mockMvc.perform(post(BASE + "/explained").with(login(21L, Role.USER))).andExpect(status().isTooManyRequests());
        for (int i = 0; i < 11; i++) {
            mockMvc.perform(get(BASE).with(login(22L, Role.USER))).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("Tool: 인자 스키마에 회원 ID가 없고, 회원 ID는 ToolContext에서만 꺼낸다(없으면 거절). 관심사는 선택 인자다")
    void tool() {
        ToolCallback callback = ToolCallbacks.from(tools)[0];
        String schema = callback.getToolDefinition().inputSchema();

        assertThat(callback.getToolDefinition().name()).isEqualTo("recommendInfluencers");
        assertThat(schema).contains("interest").doesNotContainIgnoringCase("userId").doesNotContainIgnoringCase("memberId");
        // 다른 값만 있고 회원 ID가 없는 ToolContext(빈 ToolContext는 Spring AI가 먼저 거절한다)
        assertThatThrownBy(() -> callback.call("{}", new ToolContext(Map.of("faqSources", List.of()))))
                .hasRootCauseInstanceOf(IllegalStateException.class);

        String result = callback.call("{}", new ToolContext(Map.of(InfluencerRecommendTools.USER_ID, 1L)));
        assertThat(result).contains("\"memberId\":2", "SIMILAR_CATEGORY", "\"needsInterest\":false")
                .doesNotContain(SECRET_BIO);
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

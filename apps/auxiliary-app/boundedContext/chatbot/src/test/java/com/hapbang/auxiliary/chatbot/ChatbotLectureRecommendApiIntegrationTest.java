package com.hapbang.auxiliary.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.auxiliary.chatbot.app.dto.LectureRecommendResDto;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.chatbot.support.FakeAiModelsConfig;
import com.hapbang.auxiliary.chatbot.support.RecommendRows;
import com.hapbang.auxiliary.global.auth.GatewayUserHeaderFilter;
import com.hapbang.shared.member.domain.Role;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 강의 추천 API (#718, #720). ES에 닿지 않는 실행이라 활동 없는 회원은 인기순·{@code POPULAR}, 관심사 요청은 검색이 실패해
 * 인기순·{@code POPULAR_TEMPORARY}다(맞춤 후보는 {@code LectureRecommendUseCaseTest}).
 */
@Import(FakeAiModelsConfig.class)
@TestPropertySource(properties = {"hapbang.chatbot.recommend.lecture.rate-limit.limit=2",
        "hapbang.chatbot.recommend.lecture.widget-rate-limit.limit=5"})
class ChatbotLectureRecommendApiIntegrationTest extends ChatbotIntegrationTestBase {

    private static final String PATH = "/api/v1/chatbot/recommendations/lectures";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        RecommendRows data = new RecommendRows(jdbcTemplate);
        // 요청 회원은 사본에 보이는 회원이어야 추천을 받는다(숨김이면 빈 결과)
        data.member(1);
        data.member(2);
        data.member(7);
        data.member(8);
        data.member(100);
        data.lecture(10, 100, "스프링 입문", "스프링 부트", List.of(), true, false, false);
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("응답 형식: items[lectureId, title, score, reason, reasonText], needsInterest, basis, fallback. 추천했으면 notice가 없다")
    void responseShape() throws Exception {
        mockMvc.perform(get(PATH).with(login(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].lectureId").value(10))
                .andExpect(jsonPath("$.data.items[0].title").value("스프링 입문"))
                .andExpect(jsonPath("$.data.items[0].score").value(0.0))
                .andExpect(jsonPath("$.data.items[0].reason").value("POPULAR"))
                .andExpect(jsonPath("$.data.items[0].reasonText").value("요즘 많이 듣는 강의예요."))
                .andExpect(jsonPath("$.data.needsInterest").value(true))
                .andExpect(jsonPath("$.data.basis").value("POPULAR"))
                .andExpect(jsonPath("$.data.fallback").value(false))
                .andExpect(jsonPath("$.data.notice").doesNotExist())
                .andExpect(jsonPath("$.data.personalized").doesNotExist());
    }

    @Test
    @DisplayName("요청 회원의 사본이 아직 없으면(가입 직후) 빈 목록과 notice(정해진 문구)")
    void requesterWithoutCopy() throws Exception {
        mockMvc.perform(get(PATH).with(login(404L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.notice").value(LectureRecommendResDto.REQUESTER_HIDDEN_NOTICE));
    }

    @Test
    @DisplayName("관심사는 앞뒤 공백을 뺀 1~100자: 빈 값·공백만·101자는 400 INVALID_REQUEST, 100자는 앞뒤 공백이 있어도 통과"
            + "(ES에 닿지 않아 오류 없이 인기순·POPULAR_TEMPORARY)")
    void interestValidation() throws Exception {
        mockMvc.perform(get(PATH).param("interest", "").with(login(1L))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get(PATH).param("interest", "   ").with(login(1L))).andExpect(status().isBadRequest());
        mockMvc.perform(get(PATH).param("interest", "가".repeat(101)).with(login(1L))).andExpect(status().isBadRequest());
        mockMvc.perform(get(PATH).param("interest", " " + "가".repeat(99) + " 가 ").with(login(1L)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(PATH).param("interest", "  " + "가".repeat(100) + "\n ").with(login(2L)))
                .andExpect(status().isOk());
        mockMvc.perform(get(PATH).param("interest", "가".repeat(100)).with(login(2L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].lectureId").value(10))
                .andExpect(jsonPath("$.data.basis").value("POPULAR_TEMPORARY"));
    }

    @Test
    @DisplayName("관심사를 넣은 요청은 좁은 한도(테스트 2회)를 센다. 넘으면 429·Retry-After. 관심사 없는 요청은 그 한도를 쓰지 않는다")
    void interestRateLimit() throws Exception {
        long userId = 7L;
        mockMvc.perform(get(PATH).with(login(userId))).andExpect(status().isOk());
        mockMvc.perform(get(PATH).param("interest", "데이터").with(login(userId))).andExpect(status().isOk());
        mockMvc.perform(get(PATH).param("interest", "데이터").with(login(userId))).andExpect(status().isOk());

        mockMvc.perform(get(PATH).param("interest", "데이터").with(login(userId)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        mockMvc.perform(get(PATH).with(login(userId))).andExpect(status().isOk());
        assertThat(meterRegistry.get("hapbang.chatbot.rate.limit.rejected").tag("feature", "lecture_recommend").counter().count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("관심사가 없어도 위젯 전체 한도(테스트 5회)를 센다. 넘으면 429(인기순 집계를 연타로 돌리지 않게)")
    void widgetRateLimit() throws Exception {
        long userId = 8L;
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get(PATH).with(login(userId))).andExpect(status().isOk());
        }

        mockMvc.perform(get(PATH).with(login(userId)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        mockMvc.perform(get(PATH).with(login(1L))).andExpect(status().isOk());
    }

    private static RequestPostProcessor login(long userId) {
        return request -> {
            request.addHeader(GatewayUserHeaderFilter.USER_ID, String.valueOf(userId));
            request.addHeader(GatewayUserHeaderFilter.USER_ROLE, Role.USER.name());
            return request;
        };
    }
}

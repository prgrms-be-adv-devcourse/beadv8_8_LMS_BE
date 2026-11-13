package com.hapbang.auxiliary.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.global.auth.GatewayUserHeaderFilter;
import com.hapbang.shared.member.domain.Role;

/**
 * 로컬 확인 화면 (#720). local 프로필에서만 로그인 없이 열린다. 다른 프로필에서는 보안 규칙이 열지 않아 401이고, 로그인해도
 * 리소스 핸들러가 없어 404다.
 */
class ChatbotLocalPageTest {

    private static final String PAGE = "/local/chatbot/index.html";

    @Nested
    @ActiveProfiles("local")
    @DisplayName("local 프로필")
    class Local extends ChatbotIntegrationTestBase {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("로그인 없이 화면이 열린다")
        void servesPage() throws Exception {
            String page = mockMvc.perform(get(PAGE))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/html"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            // 정적 리소스 응답에는 charset이 없어 브라우저는 페이지의 meta charset(utf-8)을 따른다
            assertThat(page).contains("<meta charset=\"utf-8\">", "챗봇 로컬 확인");
        }
    }

    @Nested
    @DisplayName("다른 프로필")
    class NotLocal extends ChatbotIntegrationTestBase {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("보안 규칙이 열지 않는다(로그인 없으면 401). 로그인해도 화면을 서빙하지 않는다(404)")
        void notOpened() throws Exception {
            mockMvc.perform(get(PAGE)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(PAGE).header(GatewayUserHeaderFilter.USER_ID, "1")
                    .header(GatewayUserHeaderFilter.USER_ROLE, Role.USER.name())).andExpect(status().isNotFound());
        }
    }
}

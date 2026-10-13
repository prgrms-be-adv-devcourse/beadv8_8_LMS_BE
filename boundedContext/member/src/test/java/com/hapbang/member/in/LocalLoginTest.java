package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.hapbang.member.app.MemberFacade;
import com.hapbang.testsupport.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "hapbang.local-login.enabled=true")
class LocalLoginTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MemberFacade memberFacade;

    /** 테스트마다 DB를 비우므로 앱 시작 때 만든 계정이 없다 → 다시 만든다 */
    @BeforeEach
    void setUp() {
        memberFacade.createTestAccounts();
    }

    @Test
    @DisplayName("역할로 로컬 로그인하면 그 세션으로 내 정보를 조회할 수 있다")
    void login() throws Exception {
        MvcResult result = login("role=INFLUENCER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("INFLUENCER"))
                .andExpect(jsonPath("$.email").value("test-influencer@hapbang.local"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);

        mockMvc.perform(get("/api/v1/members/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("INFLUENCER"));
    }

    @Test
    @DisplayName("같은 역할이라도 번호가 다르면 다른 테스트 계정으로 로그인한다")
    void login_numbered() throws Exception {
        String first = loggedInId("role=INFLUENCER");
        String second = loggedInId("role=INFLUENCER&number=2");

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("만들어 두지 않은 번호면 404와 TEST_ACCOUNT_NOT_FOUND를 반환한다")
    void login_notCreatedNumber() throws Exception {
        login("role=INFLUENCER&number=3")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEST_ACCOUNT_NOT_FOUND"));
    }

    @Test
    @DisplayName("역할이 없으면 400을 반환한다")
    void login_withoutRole() throws Exception {
        login("")
                .andExpect(status().isBadRequest());
    }

    private ResultActions login(String query) throws Exception {
        return mockMvc.perform(post("/api/v1/members/local-login?" + query).with(csrf()));
    }

    /** 로컬 로그인 후 응답의 회원 ID */
    private String loggedInId(String query) throws Exception {
        String json = login(query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id").toString();
    }

    @Test
    @DisplayName("없는 역할이면 400을 반환한다")
    void login_unknownRole() throws Exception {
        login("role=KING")
                .andExpect(status().isBadRequest());
    }
}

package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.testsupport.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 콘텐츠 분야 목록 내부 API (#769): 꺼진 분야까지 모두 코드 순으로 준다 */
@MemberIntegrationTest
@AutoConfigureMockMvc
class MemberContentCategoriesInternalApiTest {

    private static final String CATEGORIES = "/api/v1/internal/members/content-categories";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("처음 목록 23개를 코드 오름차순으로 code·name·active와 함께 준다")
    void allByCode() throws Exception {
        String body = mockMvc.perform(get(CATEGORIES).header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.items.length()").value(23))
                .andExpect(jsonPath("$.data.items[0].code").value("BATTLEGROUNDS"))
                .andExpect(jsonPath("$.data.items[0].name").value("배틀그라운드"))
                .andExpect(jsonPath("$.data.items[0].active").value(true))
                .andReturn().getResponse().getContentAsString();

        List<String> codes = JsonPath.read(body, "$.data.items[*].code");
        assertThat(codes).isSorted();
    }

    @Test
    @DisplayName("active가 꺼진 분야도 active=false로 함께 준다")
    void includesInactive() throws Exception {
        jdbcTemplate.update("update members.content_category set active = false where code = 'GAME'");

        mockMvc.perform(get(CATEGORIES).header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(23))
                .andExpect(jsonPath("$.data.items[?(@.code == 'GAME')].name").value("게임"))
                .andExpect(jsonPath("$.data.items[?(@.code == 'GAME')].active").value(false))
                .andExpect(jsonPath("$.data.items[?(@.code == 'MUKBANG')].active").value(true));
    }

    @Test
    @DisplayName("내부 키가 없거나 틀리면 401이다")
    void unauthorized() throws Exception {
        mockMvc.perform(get(CATEGORIES))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(CATEGORIES).header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY + "x"))
                .andExpect(status().isUnauthorized());
    }
}

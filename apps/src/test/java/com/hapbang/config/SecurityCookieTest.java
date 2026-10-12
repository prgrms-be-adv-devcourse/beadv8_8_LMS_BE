package com.hapbang.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.testsupport.PostgresTestcontainersConfig;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfig.class)
class SecurityCookieTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("비로그인 요청은 CSRF 토큰 쿠키만 받고 세션은 만들지 않는다")
    void anonymousRequest() throws Exception {
        mockMvc.perform(get("/api/v1/members/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().doesNotExist("SESSION"));
    }
}

package com.hapbang.config;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.testsupport.PostgresTestcontainersConfig;

/** 서버 nginx는 /api/만 Spring으로 보내므로 구글 로그인 주소가 /api/ 아래에 있어야 한다 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfig.class)
class OAuth2LoginPathTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("로그인 시작 주소로 요청하면 구글로 보내고, 돌아올 주소도 /api/v1/members 아래다")
    void startLogin() throws Exception {
        mockMvc.perform(get("/api/v1/members/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", allOf(
                        startsWith("https://accounts.google.com/o/oauth2/v2/auth"),
                        containsString("redirect_uri=http://localhost/api/v1/members/login/oauth2/code/google"))));
    }
}

package com.hapbang.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
class SwaggerDocsTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("@LoginUserId 파라미터는 API 명세에 입력 칸으로 나오지 않는다")
    void loginUserIdHidden() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/members/me'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/members/me'].get.parameters").doesNotExist());
    }

    @Test
    @DisplayName("강의 판매 수익 내부 API는 내부 API 키 헤더와 함께 명세에 나온다")
    void internalLectureEarnings() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/internal/lectures/earnings/summary'].get.parameters[?(@.name == 'X-Internal-Api-Key')].in")
                        .value("header"))
                .andExpect(jsonPath("$.paths['/api/v1/internal/lectures/earnings'].get.parameters[?(@.name == 'status')]")
                        .exists());
    }
}

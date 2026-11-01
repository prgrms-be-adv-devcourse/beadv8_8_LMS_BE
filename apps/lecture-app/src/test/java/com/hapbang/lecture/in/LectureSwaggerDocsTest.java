package com.hapbang.lecture.in;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.testsupport.IntegrationTest;

// Swagger 명세의 성공 응답 스키마가 실제 응답(RsData)과 같은지 확인한다 (D29).
@IntegrationTest
@AutoConfigureMockMvc
class LectureSwaggerDocsTest {

    private static final String SCHEMAS = "$.components.schemas.";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 성공_응답_스키마는_RsData로_감싼_형식이다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/lectures/cart'].get.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataCartResponse"))
                .andExpect(jsonPath(SCHEMAS + "RsDataCartResponse.properties.statusCode").exists())
                .andExpect(jsonPath(SCHEMAS + "RsDataCartResponse.properties.message").exists())
                .andExpect(jsonPath(SCHEMAS + "RsDataCartResponse.properties.data['$ref']")
                        .value("#/components/schemas/CartResponse"));
    }

    @Test
    void 페이지_응답_스키마는_RsData의_data에_PagedModel을_담는다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/lectures'].get.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataPageLectureSummaryResponse"))
                .andExpect(jsonPath(SCHEMAS + "RsDataPageLectureSummaryResponse.properties.data['$ref']")
                        .value("#/components/schemas/PagedModelLectureSummaryResponse"))
                .andExpect(jsonPath(SCHEMAS + "PagedModelLectureSummaryResponse.properties.content").exists())
                .andExpect(jsonPath(SCHEMAS + "PagedModelLectureSummaryResponse.properties.page").exists());
    }

    @Test
    void 지급_실패_관리_API는_명세에_없고_HP_작업_관리_API로_본다() throws Exception {
        // D70 (5): /admin/payout-failures를 지우고 /admin/hp-tasks?taskType=PAYOUT으로 본다
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/lectures/admin/payout-failures']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/lectures/admin/payout-failures/{orderItemId}/retry']")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/lectures/admin/hp-tasks'].get").exists());
    }
}

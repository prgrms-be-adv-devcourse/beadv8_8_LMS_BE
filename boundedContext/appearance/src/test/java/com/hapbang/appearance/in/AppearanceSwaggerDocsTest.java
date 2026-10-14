package com.hapbang.appearance.in;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.testsupport.IntegrationTest;

/**
 * Swagger 명세(/v3/api-docs)의 출연 요청서 성공 응답 스키마가 실제 응답(RsData)과 같은지 확인한다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class AppearanceSwaggerDocsTest {

    private static final String API_DOCS = "/v3/api-docs";
    private static final String BASE = "$.paths['/api/v1/appearances/requests";
    private static final String SCHEMAS = "$.components.schemas.";

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("보내기(201)·상세·수정 응답 스키마는 RsData로 감싼 요청서다")
    void detailSchemaIsRsData() throws Exception {
        mockMvc.perform(get(API_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath(BASE + "'].post.responses['201'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataAppearanceRequestResDto"))
                .andExpect(jsonPath(BASE + "/{requestId}'].get.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataAppearanceRequestResDto"))
                .andExpect(jsonPath(BASE + "/{requestId}'].patch.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataAppearanceRequestResDto"))
                .andExpect(jsonPath(SCHEMAS + "RsDataAppearanceRequestResDto.properties.statusCode").exists())
                .andExpect(jsonPath(SCHEMAS + "RsDataAppearanceRequestResDto.properties.message").exists())
                .andExpect(jsonPath(SCHEMAS + "RsDataAppearanceRequestResDto.properties.data['$ref']")
                        .value("#/components/schemas/AppearanceRequestResDto"));
    }

    @Test
    @DisplayName("보낸·받은 목록 응답 스키마는 RsData의 data에 PagedModel(content, page)을 담는다")
    void listSchemaIsRsDataOfPagedModel() throws Exception {
        mockMvc.perform(get(API_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath(BASE + "/sent'].get.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataPageAppearanceRequestSummaryResDto"))
                .andExpect(jsonPath(BASE + "/received'].get.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataPageAppearanceRequestSummaryResDto"))
                .andExpect(jsonPath(SCHEMAS + "RsDataPageAppearanceRequestSummaryResDto.properties.data['$ref']")
                        .value("#/components/schemas/PagedModelAppearanceRequestSummaryResDto"))
                .andExpect(jsonPath(SCHEMAS + "PagedModelAppearanceRequestSummaryResDto.properties.content").exists())
                .andExpect(jsonPath(SCHEMAS + "PagedModelAppearanceRequestSummaryResDto.properties.page").exists());
    }
}

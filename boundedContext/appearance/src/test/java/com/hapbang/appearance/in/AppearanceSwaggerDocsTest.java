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
    @DisplayName("수락·거절·응답 전 취소 응답 스키마도 RsData로 감싼 요청서다")
    void respondSchemaIsRsData() throws Exception {
        mockMvc.perform(get(API_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath(BASE + "/{requestId}/accept'].post.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataAppearanceRequestResDto"))
                .andExpect(jsonPath(BASE + "/{requestId}/reject'].post.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataAppearanceRequestResDto"))
                .andExpect(jsonPath(BASE + "/{requestId}/cancel'].post.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataAppearanceRequestResDto"));
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

    @Test
    @DisplayName("모집글 올리기(201)·상세·수정·조기 마감은 RsData로 감싼 모집글, 공개·내 목록은 RsData의 PagedModel이다")
    void recruitSchemaIsRsData() throws Exception {
        String recruits = "$.paths['/api/v1/appearances/recruits";
        mockMvc.perform(get(API_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath(recruits + "'].post.responses['201'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataAppearanceRecruitResDto"))
                .andExpect(jsonPath(recruits + "/{recruitId}'].get.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataAppearanceRecruitResDto"))
                .andExpect(jsonPath(recruits + "/{recruitId}'].patch.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataAppearanceRecruitResDto"))
                .andExpect(jsonPath(recruits + "/{recruitId}/close'].post.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataAppearanceRecruitResDto"))
                .andExpect(jsonPath(recruits + "'].get.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataPageAppearanceRecruitSummaryResDto"))
                .andExpect(jsonPath(recruits + "/mine'].get.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataPageAppearanceRecruitSummaryResDto"))
                .andExpect(jsonPath(SCHEMAS + "RsDataPageAppearanceRecruitSummaryResDto.properties.data['$ref']")
                        .value("#/components/schemas/PagedModelAppearanceRecruitSummaryResDto"));
    }

    @Test
    @DisplayName("모집 지원 API: 지원(201)·취소·수락·거절은 RsData로 감싼 지원, 지원자·내 지원 목록은 RsData의 PagedModel")
    void applicationSchemaIsRsData() throws Exception {
        String paths = "$.paths['/api/v1/appearances";
        mockMvc.perform(get(API_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath(paths + "/recruits/{recruitId}/applications'].post.responses['201'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataAppearanceApplicationResDto"))
                .andExpect(jsonPath(paths + "/applications/{applicationId}/accept'].post.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataAppearanceApplicationResDto"))
                .andExpect(jsonPath(paths + "/applications/{applicationId}/reject'].post.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataAppearanceApplicationResDto"))
                .andExpect(jsonPath(paths + "/applications/{applicationId}/withdraw'].post.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataAppearanceApplicationResDto"))
                .andExpect(jsonPath(paths + "/recruits/{recruitId}/applications'].get.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataPageAppearanceApplicationResDto"))
                .andExpect(jsonPath(paths + "/applications/mine'].get.responses['200'].content['*/*'].schema['$ref']")
                        .value("#/components/schemas/RsDataPageAppearanceApplicationResDto"))
                .andExpect(jsonPath(SCHEMAS + "AppearanceRecruitSummaryResDto.properties.applicantCount").exists());
    }
}

package com.hapbang.customersupport.in;

import static com.hapbang.customersupport.fixture.InquiryFixture.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.customersupport.app.InquiryUseCase;
import com.hapbang.customersupport.domain.InquiryCategory;
import com.hapbang.customersupport.out.InquiryRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

// 문의 API의 응답·검증·인증·인가를 MockMvc로 검증한다.
@IntegrationTest
@AutoConfigureMockMvc
class ApiV1InquiryControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long ADMIN_ID = 9L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    InquiryUseCase inquiryUseCase;

    @Autowired
    InquiryRepository inquiryRepository;

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"USER", "INFLUENCER", "VERIFIED_INFLUENCER"})
    void 회원은_문의를_접수하면_201(Role role) throws Exception {
        mockMvc.perform(post("/api/v1/inquiries").with(TestLogin.as(USER_ID, role)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "SERVICE_COMPLAINT", "title": "환전 계좌 변경", "content": "오류가 나요."}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.message").value("문의를 접수했습니다."))
                .andExpect(jsonPath("$.data.inquiryId").isNumber())
                .andExpect(jsonPath("$.data.category").value("SERVICE_COMPLAINT"))
                .andExpect(jsonPath("$.data.title").value("환전 계좌 변경"))
                .andExpect(jsonPath("$.data.content").value("오류가 나요."))
                .andExpect(jsonPath("$.data.status").value("WAITING"));
    }

    @ParameterizedTest
    @EnumSource(InquiryCategory.class)
    void 모든_문의_유형으로_접수할_수_있다(InquiryCategory category) throws Exception {
        mockMvc.perform(post("/api/v1/inquiries").with(TestLogin.as(USER_ID, Role.USER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "%s", "title": "제목", "content": "내용"}""".formatted(category.name())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.category").value(category.name()));
    }

    @Test
    void 제목이_비어_있으면_400() throws Exception {
        mockMvc.perform(post("/api/v1/inquiries").with(TestLogin.as(USER_ID, Role.USER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "OTHER", "title": " ", "content": "내용"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("title"));
    }

    @Test
    void 없는_문의_유형이면_400() throws Exception {
        mockMvc.perform(post("/api/v1/inquiries").with(TestLogin.as(USER_ID, Role.USER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "UNKNOWN", "title": "제목", "content": "내용"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 관리자는_문의를_접수할_수_없다_403() throws Exception {
        mockMvc.perform(post("/api/v1/inquiries").with(TestLogin.as(USER_ID, Role.ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "OTHER", "title": "제목", "content": "내용"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mockMvc.perform(get("/api/v1/inquiries"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 내_문의_목록을_최신순으로_조회한다() throws Exception {
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "첫 문의", "내용");
        inquiryUseCase.create(OTHER_USER_ID, InquiryCategory.OTHER, "남의 문의", "내용");
        inquiryUseCase.create(USER_ID, InquiryCategory.SERVICE_COMPLAINT, "두 번째 문의", "내용");

        mockMvc.perform(get("/api/v1/inquiries").with(TestLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].title").value("두 번째 문의"))
                .andExpect(jsonPath("$.data.content[1].title").value("첫 문의"))
                .andExpect(jsonPath("$.data.content[0].content").doesNotExist())
                .andExpect(jsonPath("$.data.page.size").value(20))
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.totalElements").value(2))
                .andExpect(jsonPath("$.data.page.totalPages").value(1));
    }

    @Test
    void 내_문의_목록을_페이지로_나눠_조회한다() throws Exception {
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "첫 문의", "내용");
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "두 번째 문의", "내용");
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "세 번째 문의", "내용");

        mockMvc.perform(get("/api/v1/inquiries").param("page", "1").param("size", "2")
                        .with(TestLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("첫 문의"))
                .andExpect(jsonPath("$.data.page.number").value(1))
                .andExpect(jsonPath("$.data.page.totalElements").value(3))
                .andExpect(jsonPath("$.data.page.totalPages").value(2));
    }

    @Test
    void sort가_OLDEST면_오래된순으로_조회한다() throws Exception {
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "첫 문의", "내용");
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "두 번째 문의", "내용");

        mockMvc.perform(get("/api/v1/inquiries").param("sort", "OLDEST").with(TestLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].title").value("첫 문의"))
                .andExpect(jsonPath("$.data.content[1].title").value("두 번째 문의"));
    }

    @Test
    void keyword로_내_문의를_검색한다() throws Exception {
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "환전 계좌 변경", "내용");
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "모집 글 수정", "가능한가요?");

        mockMvc.perform(get("/api/v1/inquiries").param("keyword", "환전").with(TestLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("환전 계좌 변경"))
                .andExpect(jsonPath("$.data.page.totalElements").value(1));
    }

    @Test
    void 검색어가_100자를_넘으면_400() throws Exception {
        mockMvc.perform(get("/api/v1/inquiries").param("keyword", "가".repeat(101))
                        .with(TestLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("keyword"));
    }

    @Test
    void sort에_필드_이름_정렬을_보내면_400() throws Exception {
        mockMvc.perform(get("/api/v1/inquiries").param("sort", "title,asc").with(TestLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 내_문의_상세를_조회한다() throws Exception {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "모집 글 수정 가능한가요?", "수정하고 싶어요.")
                .getId();

        mockMvc.perform(get("/api/v1/inquiries/{id}", inquiryId).with(TestLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inquiryId").value(inquiryId))
                .andExpect(jsonPath("$.data.content").value("수정하고 싶어요."));
    }

    @Test
    void 다른_회원의_문의를_조회하면_404() throws Exception {
        Long inquiryId = inquiryUseCase.create(OTHER_USER_ID, InquiryCategory.OTHER, "남의 문의", "내용").getId();

        mockMvc.perform(get("/api/v1/inquiries/{id}", inquiryId).with(TestLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
    }

    @Test
    void 내_문의를_삭제하면_204_이후_조회는_404() throws Exception {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용").getId();

        mockMvc.perform(delete("/api/v1/inquiries/{id}", inquiryId).with(TestLogin.as(USER_ID, Role.USER)).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/inquiries/{id}", inquiryId).with(TestLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
    }

    @Test
    void 답변이_달린_문의를_삭제하면_409() throws Exception {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용").getId();
        markAnswered(inquiryRepository, inquiryId);

        mockMvc.perform(delete("/api/v1/inquiries/{id}", inquiryId).with(TestLogin.as(USER_ID, Role.USER)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_DELETABLE"));
    }

    @Test
    void 다른_회원의_문의를_삭제하면_404() throws Exception {
        Long inquiryId = inquiryUseCase.create(OTHER_USER_ID, InquiryCategory.OTHER, "남의 문의", "내용").getId();

        mockMvc.perform(delete("/api/v1/inquiries/{id}", inquiryId).with(TestLogin.as(USER_ID, Role.USER)).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
    }

    @Test
    void 관리자는_답변_대기_문의를_오래된순으로_조회한다() throws Exception {
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "첫 문의", "내용");
        Long answered = inquiryUseCase.create(OTHER_USER_ID, InquiryCategory.OTHER, "답변된 문의", "내용").getId();
        inquiryUseCase.create(OTHER_USER_ID, InquiryCategory.OTHER, "두 번째 문의", "내용");
        markAnswered(inquiryRepository, answered);

        mockMvc.perform(get("/api/v1/inquiries/admin").param("status", "WAITING")
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].title").value("첫 문의"))
                .andExpect(jsonPath("$.data.content[0].userId").value(USER_ID))
                .andExpect(jsonPath("$.data.content[1].title").value("두 번째 문의"));
    }

    @Test
    void 관리자는_다른_회원의_문의_상세를_본다() throws Exception {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용").getId();

        mockMvc.perform(get("/api/v1/inquiries/admin/{id}", inquiryId).with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(USER_ID))
                .andExpect(jsonPath("$.data.content").value("내용"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"USER", "INFLUENCER", "VERIFIED_INFLUENCER"})
    void 관리자가_아니면_관리자_API는_403(Role role) throws Exception {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용").getId();

        mockMvc.perform(get("/api/v1/inquiries/admin").with(TestLogin.as(USER_ID, role)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/inquiries/admin/{id}", inquiryId).with(TestLogin.as(USER_ID, role)))
                .andExpect(status().isForbidden());
    }
}

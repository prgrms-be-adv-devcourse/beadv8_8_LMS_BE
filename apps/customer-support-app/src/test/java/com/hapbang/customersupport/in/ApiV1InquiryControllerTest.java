package com.hapbang.customersupport.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.customersupport.app.InquiryUseCase;
import com.hapbang.customersupport.app.LectureObjectionResultUseCase;
import com.hapbang.customersupport.domain.InquiryCategory;
import com.hapbang.customersupport.fixture.LectureObjectionFixture;
import com.hapbang.customersupport.global.auth.GatewayUserHeaderFilter;
import com.hapbang.customersupport.out.InquiryRepository;
import com.hapbang.customersupport.support.GatewayLogin;
import com.hapbang.shared.lecture.dto.LectureObjectionFileRequest;
import com.hapbang.shared.lecture.dto.LectureObjectionFiledDto;
import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionWithdrawnEvent;
import com.hapbang.shared.lecture.out.LectureApiCallExceptions;
import com.hapbang.shared.lecture.out.LectureApiClient;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

// 문의 API의 응답·검증·인증·인가를 MockMvc로 검증한다.
@IntegrationTest
@AutoConfigureMockMvc
class ApiV1InquiryControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long ADMIN_ID = 9L;
    private static final Long OTHER_ADMIN_ID = 10L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    InquiryUseCase inquiryUseCase;

    @Autowired
    InquiryRepository inquiryRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    LectureObjectionResultUseCase results;

    /** 강의 내부 API 대역 (#279) */
    @MockitoBean
    LectureApiClient lectureApiClient;

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"USER", "INFLUENCER", "VERIFIED_INFLUENCER"})
    void 회원은_문의를_접수하면_201(Role role) throws Exception {
        mockMvc.perform(post("/api/v1/inquiries").with(GatewayLogin.as(USER_ID, role))
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

    // 강의 내용 다름 이의 제기는 주문 항목 ID가 필요하다 (아래 이의 제기 테스트)
    @ParameterizedTest
    @EnumSource(value = InquiryCategory.class, names = "LECTURE_CONTENT_DISPUTE", mode = EnumSource.Mode.EXCLUDE)
    void 대상이_필요_없는_문의_유형으로_접수할_수_있다(InquiryCategory category) throws Exception {
        mockMvc.perform(post("/api/v1/inquiries").with(GatewayLogin.as(USER_ID, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "%s", "title": "제목", "content": "내용"}""".formatted(category.name())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.category").value(category.name()));
    }

    @Test
    void 제목이_비어_있으면_400() throws Exception {
        mockMvc.perform(post("/api/v1/inquiries").with(GatewayLogin.as(USER_ID, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "OTHER", "title": " ", "content": "내용"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("title"));
    }

    @Test
    void 없는_문의_유형이면_400() throws Exception {
        mockMvc.perform(post("/api/v1/inquiries").with(GatewayLogin.as(USER_ID, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "UNKNOWN", "title": "제목", "content": "내용"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 관리자는_문의를_접수할_수_없다_403() throws Exception {
        mockMvc.perform(post("/api/v1/inquiries").with(GatewayLogin.as(USER_ID, Role.ADMIN))
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

    // Gateway 사용자 헤더가 한쪽만 있거나 형식이 틀리면 익명이라 로그인이 필요한 문의 API는 401이다
    @ParameterizedTest(name = "[{index}] id={0}, role={1}")
    @CsvSource(value = {"1, NULL", "NULL, USER", "abc, USER", "0, USER", "1, ROLE_USER"}, nullValues = "NULL")
    void 사용자_헤더가_한쪽만_있거나_형식이_틀리면_401(String userId, String role) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/inquiries");
        if (userId != null) {
            request.header(GatewayUserHeaderFilter.USER_ID, userId);
        }
        if (role != null) {
            request.header(GatewayUserHeaderFilter.USER_ROLE, role);
        }
        mockMvc.perform(request).andExpect(status().isUnauthorized());
    }

    @Test
    void 내_문의_목록을_최신순으로_조회한다() throws Exception {
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "첫 문의", "내용", null);
        inquiryUseCase.create(OTHER_USER_ID, InquiryCategory.OTHER, "남의 문의", "내용", null);
        inquiryUseCase.create(USER_ID, InquiryCategory.SERVICE_COMPLAINT, "두 번째 문의", "내용", null);

        mockMvc.perform(get("/api/v1/inquiries").with(GatewayLogin.as(USER_ID, Role.USER)))
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
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "첫 문의", "내용", null);
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "두 번째 문의", "내용", null);
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "세 번째 문의", "내용", null);

        mockMvc.perform(get("/api/v1/inquiries").param("page", "1").param("size", "2")
                        .with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("첫 문의"))
                .andExpect(jsonPath("$.data.page.number").value(1))
                .andExpect(jsonPath("$.data.page.totalElements").value(3))
                .andExpect(jsonPath("$.data.page.totalPages").value(2));
    }

    @Test
    void sort가_OLDEST면_오래된순으로_조회한다() throws Exception {
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "첫 문의", "내용", null);
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "두 번째 문의", "내용", null);

        mockMvc.perform(get("/api/v1/inquiries").param("sort", "OLDEST").with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].title").value("첫 문의"))
                .andExpect(jsonPath("$.data.content[1].title").value("두 번째 문의"));
    }

    @Test
    void keyword로_내_문의를_검색한다() throws Exception {
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "환전 계좌 변경", "내용", null);
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "모집 글 수정", "가능한가요?", null);

        mockMvc.perform(get("/api/v1/inquiries").param("keyword", "환전").with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("환전 계좌 변경"))
                .andExpect(jsonPath("$.data.page.totalElements").value(1));
    }

    @Test
    void 검색어가_100자를_넘으면_400() throws Exception {
        mockMvc.perform(get("/api/v1/inquiries").param("keyword", "가".repeat(101))
                        .with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("keyword"));
    }

    @Test
    void sort에_필드_이름_정렬을_보내면_400() throws Exception {
        mockMvc.perform(get("/api/v1/inquiries").param("sort", "title,asc").with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 내_문의_상세를_조회한다() throws Exception {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "모집 글 수정 가능한가요?", "수정하고 싶어요.", null)
                .getId();

        mockMvc.perform(get("/api/v1/inquiries/{id}", inquiryId).with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inquiryId").value(inquiryId))
                .andExpect(jsonPath("$.data.content").value("수정하고 싶어요."));
    }

    @Test
    void 다른_회원의_문의를_조회하면_404() throws Exception {
        Long inquiryId = inquiryUseCase.create(OTHER_USER_ID, InquiryCategory.OTHER, "남의 문의", "내용", null).getId();

        mockMvc.perform(get("/api/v1/inquiries/{id}", inquiryId).with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
    }

    @Test
    void 내_문의를_삭제하면_204_이후_조회는_404() throws Exception {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용", null).getId();

        mockMvc.perform(delete("/api/v1/inquiries/{id}", inquiryId).with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/inquiries/{id}", inquiryId).with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
    }

    @Test
    void 답변이_달린_문의를_삭제하면_409() throws Exception {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용", null).getId();
        inquiryUseCase.reply(inquiryId, ADMIN_ID, "답변");

        mockMvc.perform(delete("/api/v1/inquiries/{id}", inquiryId).with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_DELETABLE"));
    }

    @Test
    void 다른_회원의_문의를_삭제하면_404() throws Exception {
        Long inquiryId = inquiryUseCase.create(OTHER_USER_ID, InquiryCategory.OTHER, "남의 문의", "내용", null).getId();

        mockMvc.perform(delete("/api/v1/inquiries/{id}", inquiryId).with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
    }

    @Test
    void 관리자는_답변_대기_문의를_오래된순으로_조회한다() throws Exception {
        inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "첫 문의", "내용", null);
        Long answered = inquiryUseCase.create(OTHER_USER_ID, InquiryCategory.OTHER, "답변된 문의", "내용", null).getId();
        inquiryUseCase.create(OTHER_USER_ID, InquiryCategory.OTHER, "두 번째 문의", "내용", null);
        inquiryUseCase.reply(answered, ADMIN_ID, "답변");

        mockMvc.perform(get("/api/v1/inquiries/admin").param("status", "WAITING")
                        .with(GatewayLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].title").value("첫 문의"))
                .andExpect(jsonPath("$.data.content[0].userId").value(USER_ID))
                .andExpect(jsonPath("$.data.content[1].title").value("두 번째 문의"));
    }

    @Test
    void 관리자는_다른_회원의_문의_상세를_본다() throws Exception {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용", null).getId();

        mockMvc.perform(get("/api/v1/inquiries/admin/{id}", inquiryId).with(GatewayLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(USER_ID))
                .andExpect(jsonPath("$.data.content").value("내용"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"USER", "INFLUENCER", "VERIFIED_INFLUENCER"})
    void 관리자가_아니면_관리자_API는_403(Role role) throws Exception {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용", null).getId();

        mockMvc.perform(get("/api/v1/inquiries/admin").with(GatewayLogin.as(USER_ID, role)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/inquiries/admin/{id}", inquiryId).with(GatewayLogin.as(USER_ID, role)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 관리자가_답변하면_201_답변_완료_회원_상세에_답변이_보인다() throws Exception {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용", null).getId();

        mockMvc.perform(post("/api/v1/inquiries/admin/{id}/replies", inquiryId)
                        .with(GatewayLogin.as(ADMIN_ID, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "확인했습니다."}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.message").value("답변을 등록했습니다."))
                .andExpect(jsonPath("$.data.status").value("ANSWERED"))
                .andExpect(jsonPath("$.data.replies[0].replyId").isNumber())
                .andExpect(jsonPath("$.data.replies[0].content").value("확인했습니다."));

        mockMvc.perform(get("/api/v1/inquiries/{id}", inquiryId).with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ANSWERED"))
                .andExpect(jsonPath("$.data.replies.length()").value(1))
                .andExpect(jsonPath("$.data.replies[0].content").value("확인했습니다."))
                .andExpect(jsonPath("$.data.replies[0].adminUserId").doesNotExist());
    }

    @Test
    void 답변_내용이_비어_있으면_400() throws Exception {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용", null).getId();

        mockMvc.perform(post("/api/v1/inquiries/admin/{id}/replies", inquiryId)
                        .with(GatewayLogin.as(ADMIN_ID, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": " "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("content"));
    }

    @Test
    void 다른_관리자도_답변을_고칠_수_있다() throws Exception {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용", null).getId();
        Long replyId = inquiryUseCase.reply(inquiryId, ADMIN_ID, "첫 답변").getReplies().getFirst().getId();

        mockMvc.perform(put("/api/v1/inquiries/admin/{id}/replies/{replyId}", inquiryId, replyId)
                        .with(GatewayLogin.as(OTHER_ADMIN_ID, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "고친 답변"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("답변을 수정했습니다."))
                .andExpect(jsonPath("$.data.status").value("ANSWERED"))
                .andExpect(jsonPath("$.data.replies[0].content").value("고친 답변"));
    }

    @Test
    void 마지막_답변을_지우면_204_문의는_답변_대기() throws Exception {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용", null).getId();
        Long replyId = inquiryUseCase.reply(inquiryId, ADMIN_ID, "답변").getReplies().getFirst().getId();

        mockMvc.perform(delete("/api/v1/inquiries/admin/{id}/replies/{replyId}", inquiryId, replyId)
                        .with(GatewayLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/inquiries/{id}", inquiryId).with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.replies.length()").value(0));
    }

    @Test
    void 없는_답변을_고치거나_지우면_404() throws Exception {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용", null).getId();

        mockMvc.perform(put("/api/v1/inquiries/admin/{id}/replies/{replyId}", inquiryId, 999L)
                        .with(GatewayLogin.as(ADMIN_ID, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "고친 답변"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_REPLY_NOT_FOUND"));
        mockMvc.perform(delete("/api/v1/inquiries/admin/{id}/replies/{replyId}", inquiryId, 999L)
                        .with(GatewayLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_REPLY_NOT_FOUND"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"USER", "INFLUENCER", "VERIFIED_INFLUENCER"})
    void 관리자가_아니면_답변을_등록_수정_삭제할_수_없다_403(Role role) throws Exception {
        Long inquiryId = inquiryUseCase.create(USER_ID, InquiryCategory.OTHER, "제목", "내용", null).getId();
        Long replyId = inquiryUseCase.reply(inquiryId, ADMIN_ID, "답변").getReplies().getFirst().getId();

        mockMvc.perform(post("/api/v1/inquiries/admin/{id}/replies", inquiryId)
                        .with(GatewayLogin.as(USER_ID, role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "답변"}"""))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/inquiries/admin/{id}/replies/{replyId}", inquiryId, replyId)
                        .with(GatewayLogin.as(USER_ID, role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "답변"}"""))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/inquiries/admin/{id}/replies/{replyId}", inquiryId, replyId)
                        .with(GatewayLogin.as(USER_ID, role)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 이의_제기를_강의가_접수하면_201_결과를_모르면_202_거절하면_사유_코드다() throws Exception {
        given(lectureApiClient.fileObjection(any())).willAnswer(invocation -> {
            LectureObjectionFileRequest request = invocation.getArgument(0);
            return new LectureObjectionFiledDto(request.commandId(), request.inquiryId(), request.orderItemId());
        });
        postDispute()
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.data.objectionLinkStatus").value("FILED"))
                .andExpect(jsonPath("$.data.targetId").value(100))
                // 회원 응답에는 명령 ID를 싣지 않는다
                .andExpect(jsonPath("$.data.objectionCommandId").doesNotExist());

        reset(lectureApiClient);
        given(lectureApiClient.fileObjection(any())).willThrow(LectureApiCallExceptions.unavailable());
        postDispute()
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.statusCode").value(202))
                .andExpect(jsonPath("$.message").value("문의를 저장했습니다. 이의 제기 접수를 처리하는 중입니다."))
                .andExpect(jsonPath("$.data.objectionLinkStatus").value("FILING"));

        reset(lectureApiClient);
        given(lectureApiClient.fileObjection(any()))
                .willThrow(LectureApiCallExceptions.rejected(404, "OBJECTION_NOT_BUYER"));
        postDispute()
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OBJECTION_NOT_BUYER"));
    }

    @Test
    void 이의_제기에_주문_항목이_없으면_400() throws Exception {
        mockMvc.perform(post("/api/v1/inquiries").with(GatewayLogin.as(USER_ID, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "LECTURE_CONTENT_DISPUTE", "title": "내용이 달라요", "content": "달라요."}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INQUIRY_TARGET_REQUIRED"));
    }

    @Test
    void 이의_제기_판단은_강의_반영_전이면_202_반영된_같은_판단은_200_다른_판단은_409다() throws Exception {
        Long inquiryId = saveDispute();

        resolve(inquiryId, true, "확인 결과 반환합니다.")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.statusCode").value(202))
                .andExpect(jsonPath("$.data.objectionLinkStatus").value("RESOLUTION_PENDING"))
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.objectionCommandId").value("RESOLVE:" + inquiryId));
        resolve(inquiryId, true, "확인 결과 반환합니다.").andExpect(status().isAccepted());
        resolve(inquiryId, false, "기각합니다.")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OBJECTION_IN_PROGRESS"));

        results.apply(new LectureObjectionResolvedEvent("RESOLVE:" + inquiryId, inquiryId, 100L, true));

        resolve(inquiryId, true, "확인 결과 반환합니다.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("이의 제기를 판단했습니다."))
                .andExpect(jsonPath("$.data.objectionLinkStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.data.status").value("ANSWERED"))
                .andExpect(jsonPath("$.data.objectionResult").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.hpReturnStatus").value("PENDING"));
    }

    @Test
    void 이의_제기_판단에_인정_여부가_없으면_400() throws Exception {
        Long inquiryId = saveDispute();

        mockMvc.perform(post("/api/v1/inquiries/admin/{id}/objection-resolution", inquiryId)
                        .with(GatewayLogin.as(ADMIN_ID, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "답변"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"USER", "INFLUENCER", "VERIFIED_INFLUENCER"})
    void 관리자가_아니면_이의_제기를_판단할_수_없다_403(Role role) throws Exception {
        Long inquiryId = saveDispute();

        mockMvc.perform(post("/api/v1/inquiries/admin/{id}/objection-resolution", inquiryId)
                        .with(GatewayLogin.as(USER_ID, role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accepted": true, "content": "답변"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void 이의_제기_문의_삭제는_철회_요청이라_202이고_철회가_끝난_뒤_다시_보내면_204다() throws Exception {
        Long inquiryId = saveDispute();

        mockMvc.perform(delete("/api/v1/inquiries/{id}", inquiryId).with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("이의 제기 철회를 처리하는 중입니다."))
                .andExpect(jsonPath("$.data.objectionLinkStatus").value("WITHDRAWAL_PENDING"));

        results.apply(new LectureObjectionWithdrawnEvent("WITHDRAW:" + inquiryId, inquiryId, 100L));

        mockMvc.perform(delete("/api/v1/inquiries/{id}", inquiryId).with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/inquiries/{id}", inquiryId).with(GatewayLogin.as(USER_ID, Role.USER)))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions postDispute() throws Exception {
        return mockMvc.perform(post("/api/v1/inquiries").with(GatewayLogin.as(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"category": "LECTURE_CONTENT_DISPUTE", "title": "내용이 달라요", "content": "커리큘럼과 달라요.",
                         "orderItemId": 100}"""));
    }

    private org.springframework.test.web.servlet.ResultActions resolve(Long inquiryId, boolean accepted, String content)
            throws Exception {
        return mockMvc.perform(post("/api/v1/inquiries/admin/{id}/objection-resolution", inquiryId)
                .with(GatewayLogin.as(ADMIN_ID, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accepted": %s, "content": "%s"}""".formatted(accepted, content)));
    }

    /** 강의에 접수된 이의 제기 문의 */
    private Long saveDispute() {
        return LectureObjectionFixture.filed(transactionTemplate, inquiryRepository, USER_ID, 100L);
    }
}

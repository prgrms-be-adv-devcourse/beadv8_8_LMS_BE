package com.hapbang.lecture.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.lecture.domain.LectureUser;
import com.hapbang.lecture.out.LectureUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

import com.jayway.jsonpath.JsonPath;

// 강의·검수 API를 HTTP부터 DB까지 관통해 검증한다. 상태 전이 세부 규칙은 LectureTest가 맡는다.
@IntegrationTest
@AutoConfigureMockMvc
class LectureApiTest {

    private static final Long SELLER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long ADMIN_ID = 3L;

    private static final String UPDATE_INFO_BODY = """
            {
              "title": "방송 장비 세팅 강의 (개정판)",
              "description": "2026년 장비 기준",
              "priceHp": 15000,
              "thumbnailFileId": 110
            }
            """;

    private static final String REGISTER_BODY = """
            {
              "title": "방송 장비 세팅 강의",
              "description": "처음 방송하는 사람을 위한 장비 세팅",
              "priceHp": 10000,
              "thumbnailFileId": 100,
              "contents": [
                {"fileId": 101, "title": "1강 장비 고르기", "contentType": "VIDEO"},
                {"fileId": 102, "title": "장비 목록", "contentType": "DOCUMENT"}
              ]
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LectureUserRepository lectureUserRepository;

    @BeforeEach
    void setUp() {
        lectureUserRepository.save(LectureUser.from(
                new UserDto(SELLER_ID, "판매자", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE)));
    }

    @Test
    void 강의를_등록하면_판매자는_검수_대기_상태와_콘텐츠를_순서대로_조회한다() throws Exception {
        Long lectureId = register(SELLER_ID);

        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lectureId").value(lectureId))
                .andExpect(jsonPath("$.sellerUserId").value(SELLER_ID))
                .andExpect(jsonPath("$.sellerNickname").value("판매자"))
                .andExpect(jsonPath("$.title").value("방송 장비 세팅 강의"))
                .andExpect(jsonPath("$.priceHp").value(10000))
                .andExpect(jsonPath("$.status").value("PENDING_INSPECTION"))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.reviewCount").value(0))
                .andExpect(jsonPath("$.contents[0].title").value("1강 장비 고르기"))
                .andExpect(jsonPath("$.contents[0].sequence").value(1))
                .andExpect(jsonPath("$.contents[0].contentType").value("VIDEO"))
                .andExpect(jsonPath("$.contents[1].title").value("장비 목록"))
                .andExpect(jsonPath("$.contents[1].sequence").value(2))
                .andExpect(jsonPath("$.contents[1].contentType").value("DOCUMENT"));
    }

    @Test
    void 검수_대기_중인_강의는_판매자가_아니면_없는_강의로_본다() throws Exception {
        Long lectureId = register(SELLER_ID);

        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
    }

    @Test
    void 관리자가_승인하면_판매자_닉네임과_함께_목록에_나온다() throws Exception {
        Long lectureId = register(SELLER_ID);

        approve(lectureId);

        mockMvc.perform(get("/api/v1/lectures").with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].lectureId").value(lectureId))
                .andExpect(jsonPath("$.content[0].title").value("방송 장비 세팅 강의"))
                .andExpect(jsonPath("$.content[0].priceHp").value(10000))
                .andExpect(jsonPath("$.content[0].sellerUserId").value(SELLER_ID))
                .andExpect(jsonPath("$.content[0].sellerNickname").value("판매자"))
                .andExpect(jsonPath("$.content[0].reviewCount").value(0))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.totalPages").value(1));
    }

    @Test
    void 비회원도_강의_목록을_조회한다() throws Exception {
        Long lectureId = registerOnSale("OBS 방송 세팅", 10_000);

        mockMvc.perform(get("/api/v1/lectures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].lectureId").value(lectureId));
    }

    @Test
    void 검수_대기_중인_강의는_목록에_나오지_않는다() throws Exception {
        register(SELLER_ID);

        mockMvc.perform(get("/api/v1/lectures").with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void 반려된_강의는_콘텐츠를_교체하고_재검수를_신청해_승인받으면_목록에_나온다() throws Exception {
        Long lectureId = register(SELLER_ID);
        mockMvc.perform(post("/api/v1/lectures/admin/{lectureId}/inspection/reject", lectureId)
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "영상 음질이 너무 낮습니다."}
                                """))
                .andExpect(status().isNoContent());

        mockMvc.perform(put("/api/v1/lectures/{lectureId}/contents", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contents": [
                                  {"fileId": 201, "title": "1강 장비 고르기 (재녹음)", "contentType": "VIDEO"}
                                ]}
                                """))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/lectures/{lectureId}/inspection-requests", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_INSPECTION"))
                .andExpect(jsonPath("$.rejectionReason").value("영상 음질이 너무 낮습니다."))
                .andExpect(jsonPath("$.contents.length()").value(1))
                .andExpect(jsonPath("$.contents[0].title").value("1강 장비 고르기 (재녹음)"))
                .andExpect(jsonPath("$.contents[0].sequence").value(1));

        approve(lectureId);

        mockMvc.perform(get("/api/v1/lectures").with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].lectureId").value(lectureId));
    }

    @Test
    void 판매_중인_강의의_콘텐츠는_바꿀_수_없다() throws Exception {
        Long lectureId = register(SELLER_ID);
        approve(lectureId);

        mockMvc.perform(put("/api/v1/lectures/{lectureId}/contents", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contents": [{"fileId": 201, "title": "새 영상", "contentType": "VIDEO"}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTENT_NOT_EDITABLE"));
    }

    @Test
    void 판매자는_판매_중인_강의의_정보를_재검수_없이_수정한다() throws Exception {
        Long lectureId = register(SELLER_ID);
        approve(lectureId);

        mockMvc.perform(patch("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UPDATE_INFO_BODY))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_SALE"))
                .andExpect(jsonPath("$.title").value("방송 장비 세팅 강의 (개정판)"))
                .andExpect(jsonPath("$.description").value("2026년 장비 기준"))
                .andExpect(jsonPath("$.priceHp").value(15000));
    }

    @Test
    void 판매자가_아니면_강의_정보를_수정할_수_없다() throws Exception {
        Long lectureId = register(SELLER_ID);
        approve(lectureId);

        mockMvc.perform(patch("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(OTHER_USER_ID, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UPDATE_INFO_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_LECTURE_SELLER"));
    }

    @Test
    void 판매자가_삭제한_강의는_목록에서_빠지고_판매자만_삭제된_상태로_조회한다() throws Exception {
        Long lectureId = register(SELLER_ID);
        approve(lectureId);

        mockMvc.perform(delete("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/lectures").with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
    }

    @Test
    void 내가_등록한_강의는_삭제된_강의를_포함해_최근_등록순으로_나오고_다른_판매자의_강의는_빠진다() throws Exception {
        Long deletedLectureId = register(SELLER_ID);
        mockMvc.perform(delete("/api/v1/lectures/{lectureId}", deletedLectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf()))
                .andExpect(status().isNoContent());
        Long pendingLectureId = register(SELLER_ID);
        register(OTHER_USER_ID);

        mockMvc.perform(get("/api/v1/lectures/me").with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].lectureId").value(pendingLectureId))
                .andExpect(jsonPath("$.content[0].status").value("PENDING_INSPECTION"))
                .andExpect(jsonPath("$.content[0].deleted").value(false))
                .andExpect(jsonPath("$.content[1].lectureId").value(deletedLectureId))
                .andExpect(jsonPath("$.content[1].deleted").value(true));
    }

    @Test
    void 검수_목록에는_검수_대기_강의가_신청이_오래된_순으로_나온다() throws Exception {
        Long firstLectureId = register(SELLER_ID);
        Long secondLectureId = register(SELLER_ID);
        approve(register(SELLER_ID));

        mockMvc.perform(get("/api/v1/lectures/admin/inspections").with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].lectureId").value(firstLectureId))
                .andExpect(jsonPath("$.content[0].sellerUserId").value(SELLER_ID))
                .andExpect(jsonPath("$.content[0].sellerNickname").value("판매자"))
                .andExpect(jsonPath("$.content[0].status").value("PENDING_INSPECTION"))
                .andExpect(jsonPath("$.content[0].inspectionRequestedAt").isNotEmpty())
                .andExpect(jsonPath("$.content[1].lectureId").value(secondLectureId));
    }

    @Test
    void 인증된_인플루언서가_아니면_강의를_등록할_수_없다() throws Exception {
        mockMvc.perform(post("/api/v1/lectures")
                        .with(TestLogin.as(OTHER_USER_ID, Role.INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void 인증된_인플루언서가_아니면_강의를_수정_삭제하거나_내_강의를_볼_수_없다() throws Exception {
        Long lectureId = register(SELLER_ID);
        var influencer = TestLogin.as(SELLER_ID, Role.INFLUENCER);

        mockMvc.perform(patch("/api/v1/lectures/{lectureId}", lectureId).with(influencer).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE_INFO_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/lectures/{lectureId}/contents", lectureId).with(influencer).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contents": [{"fileId": 201, "title": "새 영상", "contentType": "VIDEO"}]}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/lectures/{lectureId}/inspection-requests", lectureId)
                        .with(influencer).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/lectures/{lectureId}", lectureId).with(influencer).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/lectures/me").with(influencer))
                .andExpect(status().isForbidden());
    }

    @Test
    void 관리자가_아니면_검수를_승인할_수_없다() throws Exception {
        Long lectureId = register(SELLER_ID);

        mockMvc.perform(post("/api/v1/lectures/admin/{lectureId}/inspection/approve", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 검색어가_제목에_들어간_판매_중_강의만_대소문자_구분_없이_찾는다() throws Exception {
        Long obsLectureId = registerOnSale("OBS 방송 세팅", 10_000);
        registerOnSale("마이크 고르는 법", 10_000);

        mockMvc.perform(get("/api/v1/lectures").param("keyword", "obs")
                        .with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].lectureId").value(obsLectureId));
    }

    @Test
    void 가격순으로_정렬하고_페이지_단위로_나눈다() throws Exception {
        registerOnSale("중급 강의", 20_000);
        registerOnSale("입문 강의", 10_000);
        registerOnSale("고급 강의", 30_000);

        mockMvc.perform(get("/api/v1/lectures").param("sort", "PRICE_ASC").param("size", "2")
                        .with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].priceHp").value(10000))
                .andExpect(jsonPath("$.content[1].priceHp").value(20000))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));
        mockMvc.perform(get("/api/v1/lectures").param("sort", "PRICE_DESC").param("page", "1").param("size", "2")
                        .with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].priceHp").value(10000))
                .andExpect(jsonPath("$.page.number").value(1));
    }

    @Test
    void 요청_값이_잘못되면_400과_잘못된_항목을_알려_준다() throws Exception {
        mockMvc.perform(post("/api/v1/lectures")
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": " ", "priceHp": 10000, "contents": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[?(@.field == 'title')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'contents')]").exists());
    }

    @Test
    void 콘텐츠_목록에_빈_항목이_있으면_400() throws Exception {
        mockMvc.perform(post("/api/v1/lectures")
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "방송 장비 세팅 강의", "priceHp": 10000, "contents": [null]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 가격이_천_HP_단위가_아니면_등록할_수_없다() throws Exception {
        mockMvc.perform(post("/api/v1/lectures")
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "방송 장비 세팅 강의",
                                  "priceHp": 10500,
                                  "contents": [{"fileId": 101, "title": "1강", "contentType": "VIDEO"}]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PRICE"));
    }

    private Long registerOnSale(String title, long priceHp) throws Exception {
        String body = """
                {
                  "title": "%s",
                  "priceHp": %d,
                  "contents": [{"fileId": 101, "title": "1강", "contentType": "VIDEO"}]
                }
                """.formatted(title, priceHp);
        String response = mockMvc.perform(post("/api/v1/lectures")
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long lectureId = ((Number) JsonPath.read(response, "$.id")).longValue();
        approve(lectureId);
        return lectureId;
    }

    private void approve(Long lectureId) throws Exception {
        mockMvc.perform(post("/api/v1/lectures/admin/{lectureId}/inspection/approve", lectureId)
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)).with(csrf()))
                .andExpect(status().isNoContent());
    }

    private Long register(Long sellerId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/lectures")
                        .with(TestLogin.as(sellerId, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }
}

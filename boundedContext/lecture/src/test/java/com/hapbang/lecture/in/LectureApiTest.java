package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.deleted;
import static com.hapbang.lecture.fixture.LectureFixture.pendingFile;
import static com.hapbang.lecture.fixture.LectureFixture.uploadedFile;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.hamcrest.Matcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.FileResource;
import com.hapbang.lecture.domain.FileType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureUser;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.FileResourceRepository;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
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
    private static final Long BUYER_ID = 4L;

    private static final String UPDATE_INFO_BODY = """
            {
              "title": "방송 장비 세팅 강의 (개정판)",
              "description": "2026년 장비 기준",
              "priceHp": 15000,
              "thumbnailFileId": %d
            }
            """;

    private static final String REGISTER_BODY = """
            {
              "title": "방송 장비 세팅 강의",
              "description": "처음 방송하는 사람을 위한 장비 세팅",
              "priceHp": 10000,
              "thumbnailFileId": %d,
              "contents": [
                {"fileId": %d, "title": "1강 장비 고르기", "contentType": "VIDEO"},
                {"fileId": %d, "title": "장비 목록", "contentType": "DOCUMENT"}
              ]
            }
            """;

    private static final String CONTENTS_BODY = """
            {"contents": [{"fileId": %d, "title": "1강 장비 고르기 (재녹음)", "contentType": "VIDEO"}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LectureUserRepository lectureUserRepository;

    @Autowired
    private FileResourceRepository fileResourceRepository;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.lectureId").value(lectureId))
                .andExpect(jsonPath("$.data.sellerUserId").value(SELLER_ID))
                .andExpect(jsonPath("$.data.sellerNickname").value("판매자"))
                .andExpect(jsonPath("$.data.title").value("방송 장비 세팅 강의"))
                .andExpect(jsonPath("$.data.priceHp").value(10000))
                .andExpect(jsonPath("$.data.status").value("PENDING_INSPECTION"))
                .andExpect(jsonPath("$.data.deleted").value(false))
                .andExpect(jsonPath("$.data.reviewCount").value(0))
                .andExpect(jsonPath("$.data.contents[0].title").value("1강 장비 고르기"))
                .andExpect(jsonPath("$.data.contents[0].sequence").value(1))
                .andExpect(jsonPath("$.data.contents[0].contentType").value("VIDEO"))
                .andExpect(jsonPath("$.data.contents[1].title").value("장비 목록"))
                .andExpect(jsonPath("$.data.contents[1].sequence").value(2))
                .andExpect(jsonPath("$.data.contents[1].contentType").value("DOCUMENT"));
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
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].lectureId").value(lectureId))
                .andExpect(jsonPath("$.data.content[0].title").value("방송 장비 세팅 강의"))
                .andExpect(jsonPath("$.data.content[0].priceHp").value(10000))
                .andExpect(jsonPath("$.data.content[0].sellerUserId").value(SELLER_ID))
                .andExpect(jsonPath("$.data.content[0].sellerNickname").value("판매자"))
                .andExpect(jsonPath("$.data.content[0].reviewCount").value(0))
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.size").value(20))
                .andExpect(jsonPath("$.data.page.totalElements").value(1))
                .andExpect(jsonPath("$.data.page.totalPages").value(1));
    }

    @Test
    void 비회원도_강의_목록을_조회한다() throws Exception {
        Long lectureId = registerOnSale("OBS 방송 세팅", 10_000);

        mockMvc.perform(get("/api/v1/lectures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.content[0].lectureId").value(lectureId));
    }

    @Test
    void 판매_중인_강의_목록에_썸네일_조회_URL이_1시간_만료로_나온다() throws Exception {
        FileResource thumbnail = uploadedThumbnail(SELLER_ID);
        Long lectureId = register(SELLER_ID, thumbnail.getId());
        approve(lectureId);

        mockMvc.perform(get("/api/v1/lectures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.content[0].thumbnailUrl").value(thumbnailUrlOf(thumbnail)));
    }

    @Test
    void 판매자는_검수_대기_강의의_상세에서도_썸네일_URL을_본다() throws Exception {
        FileResource thumbnail = uploadedThumbnail(SELLER_ID);
        Long lectureId = register(SELLER_ID, thumbnail.getId());

        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.status").value("PENDING_INSPECTION"))
                .andExpect(jsonPath("$.data.thumbnailUrl").value(thumbnailUrlOf(thumbnail)));
    }

    @Test
    void 썸네일이_없는_강의는_목록과_상세에서_thumbnailUrl이_null이다() throws Exception {
        Long lectureId = register(SELLER_ID, null);
        approve(lectureId);

        mockMvc.perform(get("/api/v1/lectures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.content[0].lectureId").value(lectureId))
                .andExpect(jsonPath("$.data.content[0].thumbnailUrl").value(nullValue()));
        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.thumbnailUrl").value(nullValue()));
    }

    @Test
    void 썸네일_파일이_삭제되면_thumbnailUrl이_null이다() throws Exception {
        FileResource thumbnail = uploadedThumbnail(SELLER_ID);
        Long lectureId = register(SELLER_ID, thumbnail.getId());
        approve(lectureId);
        fileResourceRepository.save(deleted(thumbnail));

        mockMvc.perform(get("/api/v1/lectures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.content[0].lectureId").value(lectureId))
                .andExpect(jsonPath("$.data.content[0].thumbnailUrl").value(nullValue()));
    }

    @Test
    void 검수_대기_중인_강의는_목록에_나오지_않는다() throws Exception {
        register(SELLER_ID);

        mockMvc.perform(get("/api/v1/lectures").with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.page.totalElements").value(0));
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
                        .content(CONTENTS_BODY.formatted(uploaded(SELLER_ID, FileType.LECTURE_VIDEO))))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/lectures/{lectureId}/inspection-requests", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.status").value("PENDING_INSPECTION"))
                .andExpect(jsonPath("$.data.rejectionReason").value("영상 음질이 너무 낮습니다."))
                .andExpect(jsonPath("$.data.contents.length()").value(1))
                .andExpect(jsonPath("$.data.contents[0].title").value("1강 장비 고르기 (재녹음)"))
                .andExpect(jsonPath("$.data.contents[0].sequence").value(1));

        approve(lectureId);

        mockMvc.perform(get("/api/v1/lectures").with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.content[0].lectureId").value(lectureId));
    }

    @Test
    void 판매_중인_강의의_콘텐츠는_바꿀_수_없다() throws Exception {
        Long lectureId = register(SELLER_ID);
        approve(lectureId);

        mockMvc.perform(put("/api/v1/lectures/{lectureId}/contents", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONTENTS_BODY.formatted(uploaded(SELLER_ID, FileType.LECTURE_VIDEO))))
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
                        .content(updateInfoBody(SELLER_ID)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.status").value("ON_SALE"))
                .andExpect(jsonPath("$.data.title").value("방송 장비 세팅 강의 (개정판)"))
                .andExpect(jsonPath("$.data.description").value("2026년 장비 기준"))
                .andExpect(jsonPath("$.data.priceHp").value(15000));
    }

    @Test
    void 판매자가_아니면_강의_정보를_수정할_수_없다() throws Exception {
        Long lectureId = register(SELLER_ID);
        approve(lectureId);

        mockMvc.perform(patch("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(OTHER_USER_ID, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateInfoBody(SELLER_ID)))
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
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.page.totalElements").value(0));
        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.deleted").value(true));
    }

    @Test
    void 보유_중인_구매자는_삭제된_강의_상세를_조회한다() throws Exception {
        Long lectureId = register(SELLER_ID);
        approve(lectureId);
        purchase(lectureId);
        deleteLecture(lectureId);

        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId).with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.deleted").value(true))
                .andExpect(jsonPath("$.data.rejectionReason").value(nullValue()))
                .andExpect(jsonPath("$.data.contents.length()").value(2));
    }

    @Test
    void 구매_취소한_회원에게_삭제된_강의는_404다() throws Exception {
        Long lectureId = register(SELLER_ID);
        approve(lectureId);
        LectureOrder order = purchase(lectureId);
        transactionTemplate.executeWithoutResult(status -> lectureOrderRepository.findById(order.getId())
                .orElseThrow()
                .cancelItem(order.getItems().getFirst().getId(), LocalDateTime.now(LectureConfig.ZONE)));
        deleteLecture(lectureId);

        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId).with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
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
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.page.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].lectureId").value(pendingLectureId))
                .andExpect(jsonPath("$.data.content[0].status").value("PENDING_INSPECTION"))
                .andExpect(jsonPath("$.data.content[0].deleted").value(false))
                .andExpect(jsonPath("$.data.content[1].lectureId").value(deletedLectureId))
                .andExpect(jsonPath("$.data.content[1].deleted").value(true));
    }

    @Test
    void 내가_등록한_강의는_삭제된_강의도_썸네일_URL이_나온다() throws Exception {
        FileResource thumbnail = uploadedThumbnail(SELLER_ID);
        Long lectureId = register(SELLER_ID, thumbnail.getId());
        mockMvc.perform(delete("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/lectures/me").with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.content[0].deleted").value(true))
                .andExpect(jsonPath("$.data.content[0].thumbnailUrl").value(thumbnailUrlOf(thumbnail)));
    }

    @Test
    void 검수_목록에는_검수_대기_강의가_신청이_오래된_순으로_나온다() throws Exception {
        Long firstLectureId = register(SELLER_ID);
        Long secondLectureId = register(SELLER_ID);
        approve(register(SELLER_ID));

        mockMvc.perform(get("/api/v1/lectures/admin/inspections").with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.page.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].lectureId").value(firstLectureId))
                .andExpect(jsonPath("$.data.content[0].sellerUserId").value(SELLER_ID))
                .andExpect(jsonPath("$.data.content[0].sellerNickname").value("판매자"))
                .andExpect(jsonPath("$.data.content[0].status").value("PENDING_INSPECTION"))
                .andExpect(jsonPath("$.data.content[0].inspectionRequestedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.content[1].lectureId").value(secondLectureId));
    }

    @Test
    void 관리자는_검수_대기_강의_상세와_콘텐츠를_조회한다() throws Exception {
        Long lectureId = register(SELLER_ID);

        mockMvc.perform(get("/api/v1/lectures/admin/{lectureId}", lectureId)
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.lectureId").value(lectureId))
                .andExpect(jsonPath("$.data.sellerNickname").value("판매자"))
                .andExpect(jsonPath("$.data.status").value("PENDING_INSPECTION"))
                .andExpect(jsonPath("$.data.deleted").value(false))
                .andExpect(jsonPath("$.data.contents[0].title").value("1강 장비 고르기"))
                .andExpect(jsonPath("$.data.contents[0].sequence").value(1))
                .andExpect(jsonPath("$.data.contents[0].contentType").value("VIDEO"))
                .andExpect(jsonPath("$.data.contents[1].title").value("장비 목록"))
                .andExpect(jsonPath("$.data.contents[1].sequence").value(2))
                .andExpect(jsonPath("$.data.contents[1].contentType").value("DOCUMENT"));
    }

    @Test
    void 관리자는_반려된_강의를_반려_사유와_함께_조회한다() throws Exception {
        Long lectureId = register(SELLER_ID);
        reject(lectureId);

        mockMvc.perform(get("/api/v1/lectures/admin/{lectureId}", lectureId)
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectionReason").value("영상 음질이 너무 낮습니다."));
    }

    @Test
    void 관리자는_판매_중인_강의와_삭제된_강의를_조회한다() throws Exception {
        Long lectureId = register(SELLER_ID);
        approve(lectureId);

        mockMvc.perform(get("/api/v1/lectures/admin/{lectureId}", lectureId)
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ON_SALE"))
                .andExpect(jsonPath("$.data.deleted").value(false));

        mockMvc.perform(delete("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/lectures/admin/{lectureId}", lectureId)
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
    }

    @Test
    void 관리자가_아니면_관리자_강의_상세를_조회할_수_없다() throws Exception {
        Long lectureId = register(SELLER_ID);

        mockMvc.perform(get("/api/v1/lectures/admin/{lectureId}", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 관리자_강의_상세에서_없는_강의는_404() throws Exception {
        mockMvc.perform(get("/api/v1/lectures/admin/{lectureId}", 999_999L)
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
    }

    @Test
    void 관리자도_기존_강의_상세로는_검수_대기_강의를_볼_수_없다() throws Exception {
        Long lectureId = register(SELLER_ID);

        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
    }

    @Test
    void 인증된_인플루언서가_아니면_강의를_등록할_수_없다() throws Exception {
        mockMvc.perform(post("/api/v1/lectures")
                        .with(TestLogin.as(OTHER_USER_ID, Role.INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(OTHER_USER_ID)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 인증된_인플루언서가_아니면_강의를_수정_삭제하거나_내_강의를_볼_수_없다() throws Exception {
        Long lectureId = register(SELLER_ID);
        var influencer = TestLogin.as(SELLER_ID, Role.INFLUENCER);

        mockMvc.perform(patch("/api/v1/lectures/{lectureId}", lectureId).with(influencer).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(updateInfoBody(SELLER_ID)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/lectures/{lectureId}/contents", lectureId).with(influencer).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONTENTS_BODY.formatted(uploaded(SELLER_ID, FileType.LECTURE_VIDEO))))
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
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.page.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].lectureId").value(obsLectureId));
    }

    @Test
    void 가격순으로_정렬하고_페이지_단위로_나눈다() throws Exception {
        registerOnSale("중급 강의", 20_000);
        registerOnSale("입문 강의", 10_000);
        registerOnSale("고급 강의", 30_000);

        mockMvc.perform(get("/api/v1/lectures").param("sort", "PRICE_ASC").param("size", "2")
                        .with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.content[0].priceHp").value(10000))
                .andExpect(jsonPath("$.data.content[1].priceHp").value(20000))
                .andExpect(jsonPath("$.data.page.totalElements").value(3))
                .andExpect(jsonPath("$.data.page.totalPages").value(2));
        mockMvc.perform(get("/api/v1/lectures").param("sort", "PRICE_DESC").param("page", "1").param("size", "2")
                        .with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].priceHp").value(10000))
                .andExpect(jsonPath("$.data.page.number").value(1));
    }

    @Test
    void 강의_목록과_상세에_반올림한_평균_평점과_리뷰_수가_나오고_반환된_항목의_리뷰는_빠진다() throws Exception {
        Long lectureId = registerOnSale("OBS 방송 세팅", 10_000);
        review(lectureId, 4L, 5);
        review(lectureId, 5L, 4);
        review(lectureId, 6L, 4);
        Long returned = review(lectureId, 7L, 1);
        jdbcTemplate.update("update lectures.order_item set status = 'RETURNED' where id = ?", returned);

        mockMvc.perform(get("/api/v1/lectures").with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].averageRating").value(4.3))
                .andExpect(jsonPath("$.data.content[0].reviewCount").value(3));
        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId).with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.averageRating").value(4.3))
                .andExpect(jsonPath("$.data.reviewCount").value(3));
    }

    @Test
    void 리뷰가_없는_강의의_평균_평점은_null이다() throws Exception {
        Long lectureId = registerOnSale("OBS 방송 세팅", 10_000);

        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId).with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.averageRating").value(nullValue()))
                .andExpect(jsonPath("$.data.reviewCount").value(0));
    }

    @Test
    void 평점순으로_정렬하면_리뷰가_없는_강의는_뒤에_온다() throws Exception {
        Long noReview = registerOnSale("리뷰 없는 강의", 10_000);
        Long low = registerOnSale("평점 낮은 강의", 10_000);
        Long high = registerOnSale("평점 높은 강의", 10_000);
        review(low, 4L, 3);
        review(high, 4L, 5);

        mockMvc.perform(get("/api/v1/lectures").param("sort", "RATING")
                        .with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].lectureId").value(high))
                .andExpect(jsonPath("$.data.content[1].lectureId").value(low))
                .andExpect(jsonPath("$.data.content[2].lectureId").value(noReview))
                .andExpect(jsonPath("$.data.page.totalElements").value(3));
        mockMvc.perform(get("/api/v1/lectures").param("sort", "RATING").param("keyword", "낮은")
                        .with(TestLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].lectureId").value(low));
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
    void 업로드가_끝나지_않은_파일로_등록하면_400_INVALID_LECTURE_FILE이고_강의는_만들어지지_않는다() throws Exception {
        register(SELLER_ID, uploaded(SELLER_ID, FileType.LECTURE_THUMBNAIL), pending(SELLER_ID, FileType.LECTURE_VIDEO),
                uploaded(SELLER_ID, FileType.LECTURE_DOCUMENT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LECTURE_FILE"))
                .andExpect(jsonPath("$.detail", containsString("업로드가 끝나지 않은 파일")));

        mockMvc.perform(get("/api/v1/lectures/me").with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(jsonPath("$.data.page.totalElements").value(0));
    }

    @Test
    void 남의_파일과_없는_파일은_같은_메시지의_400_INVALID_LECTURE_FILE로_거부한다() throws Exception {
        register(SELLER_ID, uploaded(OTHER_USER_ID, FileType.LECTURE_THUMBNAIL),
                uploaded(SELLER_ID, FileType.LECTURE_VIDEO), uploaded(SELLER_ID, FileType.LECTURE_DOCUMENT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LECTURE_FILE"))
                .andExpect(jsonPath("$.detail", startsWith("본인이 올린 파일이 아니거나 없는 파일")));
        register(SELLER_ID, uploaded(SELLER_ID, FileType.LECTURE_THUMBNAIL), 999_999L,
                uploaded(SELLER_ID, FileType.LECTURE_DOCUMENT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LECTURE_FILE"))
                .andExpect(jsonPath("$.detail", startsWith("본인이 올린 파일이 아니거나 없는 파일")));
    }

    @Test
    void 용도가_다른_파일로_등록하면_400_INVALID_LECTURE_FILE() throws Exception {
        Long video = uploaded(SELLER_ID, FileType.LECTURE_VIDEO);
        Long document = uploaded(SELLER_ID, FileType.LECTURE_DOCUMENT);

        register(SELLER_ID, video, video, document)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LECTURE_FILE"))
                .andExpect(jsonPath("$.detail", containsString("썸네일 자리에는")));
        register(SELLER_ID, null, document, document)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LECTURE_FILE"))
                .andExpect(jsonPath("$.detail", containsString("영상 자리에는")));
    }

    @Test
    void 다른_강의에_연결한_본인_파일로_새_강의를_등록할_수_있다() throws Exception {
        Long thumbnail = uploaded(SELLER_ID, FileType.LECTURE_THUMBNAIL);
        Long video = uploaded(SELLER_ID, FileType.LECTURE_VIDEO);
        Long document = uploaded(SELLER_ID, FileType.LECTURE_DOCUMENT);
        register(SELLER_ID, thumbnail, video, document).andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201));

        register(SELLER_ID, thumbnail, video, document).andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201));
    }

    @Test
    void 정보_수정에서_남의_썸네일을_쓰면_400_INVALID_LECTURE_FILE이고_정보는_그대로다() throws Exception {
        Long lectureId = register(SELLER_ID);

        mockMvc.perform(patch("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UPDATE_INFO_BODY.formatted(uploaded(OTHER_USER_ID, FileType.LECTURE_THUMBNAIL))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LECTURE_FILE"));
        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(jsonPath("$.data.title").value("방송 장비 세팅 강의"));
    }

    @Test
    void 콘텐츠_교체에서_업로드가_끝나지_않은_파일을_쓰면_400_INVALID_LECTURE_FILE이고_콘텐츠는_그대로다() throws Exception {
        Long lectureId = register(SELLER_ID);
        reject(lectureId);

        replaceContents(SELLER_ID, lectureId, pending(SELLER_ID, FileType.LECTURE_VIDEO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LECTURE_FILE"));
        mockMvc.perform(get("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(jsonPath("$.data.contents.length()").value(2));
    }

    @Test
    void 남의_강의를_남의_파일로_교체하면_파일보다_판매자_검사가_먼저다() throws Exception {
        Long lectureId = register(SELLER_ID);
        reject(lectureId);

        replaceContents(OTHER_USER_ID, lectureId, pending(SELLER_ID, FileType.LECTURE_VIDEO))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_LECTURE_SELLER"));
    }

    @Test
    void 검수_대기_강의를_미완료_파일로_교체하면_파일보다_상태_검사가_먼저다() throws Exception {
        Long lectureId = register(SELLER_ID);

        replaceContents(SELLER_ID, lectureId, pending(SELLER_ID, FileType.LECTURE_VIDEO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTENT_NOT_EDITABLE"));
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
                  "contents": [{"fileId": %d, "title": "1강", "contentType": "VIDEO"}]
                }
                """.formatted(title, priceHp, uploaded(SELLER_ID, FileType.LECTURE_VIDEO));
        String response = mockMvc.perform(post("/api/v1/lectures")
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andReturn().getResponse().getContentAsString();
        Long lectureId = ((Number) JsonPath.read(response, "$.data.id")).longValue();
        approve(lectureId);
        return lectureId;
    }

    private void deleteLecture(Long lectureId) throws Exception {
        mockMvc.perform(delete("/api/v1/lectures/{lectureId}", lectureId)
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)).with(csrf()))
                .andExpect(status().isNoContent());
    }

    /** 구매자가 강의를 사서 직접 구매 확정하고 리뷰를 쓴다. 주문 항목 ID를 돌려준다 */
    private Long review(Long lectureId, Long buyerId, int rating) throws Exception {
        Lecture lecture = lectureRepository.findById(lectureId).orElseThrow();
        LectureOrder order = lectureOrderRepository.save(
                LectureOrderFixture.order(buyerId, List.of(lecture), LocalDateTime.now(LectureConfig.ZONE)));
        Long orderItemId = order.getItems().getFirst().getId();
        mockMvc.perform(post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/confirm", order.getId(), orderItemId)
                        .with(TestLogin.as(buyerId, Role.USER)).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/lectures/{lectureId}/reviews", lectureId)
                        .with(TestLogin.as(buyerId, Role.USER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderItemId\": %d, \"rating\": %d}".formatted(orderItemId, rating)))
                .andExpect(status().isCreated());
        return orderItemId;
    }

    /** 주문 생성 API(HP 보류)가 생기기 전에는 픽스처로 주문을 저장한다 */
    private LectureOrder purchase(Long lectureId) {
        Lecture lecture = lectureRepository.findById(lectureId).orElseThrow();
        return lectureOrderRepository.save(
                LectureOrderFixture.order(BUYER_ID, List.of(lecture), LocalDateTime.now(LectureConfig.ZONE)));
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
                        .content(registerBody(sellerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    /** 썸네일만 정하고 영상·자료는 업로드를 마친 파일로 등록한다. 썸네일이 null이면 썸네일 없는 강의다 */
    private Long register(Long sellerId, Long thumbnailFileId) throws Exception {
        String response = register(sellerId, thumbnailFileId, uploaded(sellerId, FileType.LECTURE_VIDEO),
                uploaded(sellerId, FileType.LECTURE_DOCUMENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    private ResultActions register(Long sellerId, Long thumbnailFileId, Long videoFileId, Long documentFileId)
            throws Exception {
        return mockMvc.perform(post("/api/v1/lectures")
                .with(TestLogin.as(sellerId, Role.VERIFIED_INFLUENCER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(REGISTER_BODY.formatted(thumbnailFileId, videoFileId, documentFileId)));
    }

    private ResultActions replaceContents(Long userId, Long lectureId, Long videoFileId) throws Exception {
        return mockMvc.perform(put("/api/v1/lectures/{lectureId}/contents", lectureId)
                .with(TestLogin.as(userId, Role.VERIFIED_INFLUENCER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(CONTENTS_BODY.formatted(videoFileId)));
    }

    private void reject(Long lectureId) throws Exception {
        mockMvc.perform(post("/api/v1/lectures/admin/{lectureId}/inspection/reject", lectureId)
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "영상 음질이 너무 낮습니다."}
                                """))
                .andExpect(status().isNoContent());
    }

    /** 이 판매자가 업로드를 마친 썸네일·영상·자료로 등록 요청 본문을 만든다 */
    private String registerBody(Long sellerId) {
        return REGISTER_BODY.formatted(uploaded(sellerId, FileType.LECTURE_THUMBNAIL),
                uploaded(sellerId, FileType.LECTURE_VIDEO), uploaded(sellerId, FileType.LECTURE_DOCUMENT));
    }

    private String updateInfoBody(Long sellerId) {
        return UPDATE_INFO_BODY.formatted(uploaded(sellerId, FileType.LECTURE_THUMBNAIL));
    }

    /** S3 없이 업로드를 마친 파일을 저장한다 */
    private Long uploaded(Long uploaderUserId, FileType fileType) {
        return fileResourceRepository.save(uploadedFile(uploaderUserId, fileType)).getId();
    }

    private FileResource uploadedThumbnail(Long uploaderUserId) {
        return fileResourceRepository.save(uploadedFile(uploaderUserId, FileType.LECTURE_THUMBNAIL));
    }

    /** 이 썸네일의 Presigned GET URL. 1시간(D23) 만료로 서명했는지 본다 */
    private static Matcher<String> thumbnailUrlOf(FileResource thumbnail) {
        return allOf(containsString(thumbnail.getObjectKey()), containsString("X-Amz-Expires=3600"));
    }

    private Long pending(Long uploaderUserId, FileType fileType) {
        return fileResourceRepository.save(pendingFile(uploaderUserId, fileType)).getId();
    }
}

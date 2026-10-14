package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureUser;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.out.LectureUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

import com.jayway.jsonpath.JsonPath;

// 리뷰 API를 HTTP부터 DB까지 관통해 검증한다. 작성 조건·작성자 검사 세부 규칙은 LectureReviewTest가 맡는다.
@IntegrationTest
@AutoConfigureMockMvc
class LectureReviewApiTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long OTHER_BUYER_ID = 4L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private LectureUserRepository lectureUserRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Lecture lecture;

    @BeforeEach
    void setUp() {
        lectureUserRepository.save(LectureUser.from(new UserDto(BUYER_ID, "구매자", Role.USER, UserStatus.ACTIVE)));
        lecture = saveOnSaleLecture();
    }

    @Test
    void 구매_확정한_구매자는_리뷰를_쓰고_목록에서_닉네임과_함께_본다() throws Exception {
        Long orderItemId = confirmedItem(BUYER_ID, lecture);

        Long reviewId = write(BUYER_ID, lecture.getId(), orderItemId, 5, "장비 고르는 데 도움이 됐어요")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andReturn().getResponse().getContentAsString().transform(LectureReviewApiTest::id);

        mockMvc.perform(get("/api/v1/lectures/{lectureId}/reviews", lecture.getId())
                        .with(TestLogin.as(OTHER_BUYER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].reviewId").value(reviewId))
                .andExpect(jsonPath("$.data.content[0].reviewerUserId").value(BUYER_ID))
                .andExpect(jsonPath("$.data.content[0].reviewerNickname").value("구매자"))
                .andExpect(jsonPath("$.data.content[0].rating").value(5))
                .andExpect(jsonPath("$.data.content[0].content").value("장비 고르는 데 도움이 됐어요"))
                .andExpect(jsonPath("$.data.page.totalElements").value(1));
    }

    @Test
    void 구매_확정_전_항목으로는_409_ORDER_ITEM_NOT_REVIEWABLE이다() throws Exception {
        Long orderItemId = purchasedItem(BUYER_ID, lecture);

        write(BUYER_ID, lecture.getId(), orderItemId, 5, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ITEM_NOT_REVIEWABLE"));
    }

    @Test
    void 취소_반환된_항목으로는_409_ORDER_ITEM_NOT_REVIEWABLE이다() throws Exception {
        Long canceled = purchasedItem(BUYER_ID, lecture);
        changeStatus(canceled, OrderItemStatus.CANCELED);
        Long returned = purchasedItem(BUYER_ID, lecture);
        changeStatus(returned, OrderItemStatus.RETURNED);

        write(BUYER_ID, lecture.getId(), canceled, 5, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ITEM_NOT_REVIEWABLE"));
        write(BUYER_ID, lecture.getId(), returned, 5, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ITEM_NOT_REVIEWABLE"));
    }

    @Test
    void 남의_항목이나_다른_강의의_항목으로는_404_ORDER_ITEM_NOT_FOUND다() throws Exception {
        Long othersItem = confirmedItem(OTHER_BUYER_ID, lecture);
        Long otherLectureItem = confirmedItem(BUYER_ID, saveOnSaleLecture());

        write(BUYER_ID, lecture.getId(), othersItem, 5, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_ITEM_NOT_FOUND"));
        write(BUYER_ID, lecture.getId(), otherLectureItem, 5, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_ITEM_NOT_FOUND"));
    }

    @Test
    void 없는_강의에는_404_LECTURE_NOT_FOUND다() throws Exception {
        Long orderItemId = confirmedItem(BUYER_ID, lecture);

        write(BUYER_ID, Long.MAX_VALUE, orderItemId, 5, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
    }

    @Test
    void 이미_리뷰를_쓴_강의에_다시_쓰면_409_ALREADY_REVIEWED다() throws Exception {
        Long orderItemId = confirmedItem(BUYER_ID, lecture);
        write(BUYER_ID, lecture.getId(), orderItemId, 5, null).andExpect(status().isCreated());

        write(BUYER_ID, lecture.getId(), orderItemId, 3, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REVIEWED"));
    }

    @Test
    void 리뷰를_삭제하면_목록에서_빠지고_다시_쓸_수_있다() throws Exception {
        Long orderItemId = confirmedItem(BUYER_ID, lecture);
        Long reviewId = writtenReview(BUYER_ID, lecture, orderItemId, 1);

        mockMvc.perform(delete("/api/v1/lectures/reviews/{reviewId}", reviewId)
                        .with(TestLogin.as(BUYER_ID, Role.USER)).with(csrf()))
                .andExpect(status().isNoContent());
        reviews(lecture).andExpect(jsonPath("$.data.content.length()").value(0));

        write(BUYER_ID, lecture.getId(), orderItemId, 4, "다시 봤어요").andExpect(status().isCreated());
        reviews(lecture).andExpect(jsonPath("$.data.content[0].rating").value(4));
    }

    @Test
    void 작성자는_리뷰를_고친다() throws Exception {
        Long reviewId = writtenReview(BUYER_ID, lecture, confirmedItem(BUYER_ID, lecture), 2);

        mockMvc.perform(patch("/api/v1/lectures/reviews/{reviewId}", reviewId)
                        .with(TestLogin.as(BUYER_ID, Role.USER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating": 4, "content": "두 번 보니 좋네요"}
                                """))
                .andExpect(status().isNoContent());

        reviews(lecture)
                .andExpect(jsonPath("$.data.content[0].rating").value(4))
                .andExpect(jsonPath("$.data.content[0].content").value("두 번 보니 좋네요"));
    }

    @Test
    void 반환된_항목의_리뷰는_목록에_나오지_않는다() throws Exception {
        Long orderItemId = confirmedItem(BUYER_ID, lecture);
        writtenReview(BUYER_ID, lecture, orderItemId, 1);

        changeStatus(orderItemId, OrderItemStatus.RETURNED);

        reviews(lecture)
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.page.totalElements").value(0));
    }

    @Test
    void 다시_산_강의에_리뷰를_쓰면_반환된_항목의_리뷰를_대체한다() throws Exception {
        Long returned = confirmedItem(BUYER_ID, lecture);
        writtenReview(BUYER_ID, lecture, returned, 1);
        changeStatus(returned, OrderItemStatus.RETURNED);
        Long rebought = confirmedItem(BUYER_ID, lecture);

        write(BUYER_ID, lecture.getId(), rebought, 5, "다시 사서 봤어요").andExpect(status().isCreated());

        reviews(lecture)
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].rating").value(5));
        Integer alive = jdbcTemplate.queryForObject("select count(*) from lectures.lecture_review "
                + "where reviewer_user_id = ? and deleted_at is null", Integer.class, BUYER_ID);
        assertThat(alive).isEqualTo(1);
    }

    @Test
    void 작성자가_아니면_403_NOT_REVIEW_WRITER이고_없는_리뷰는_404다() throws Exception {
        Long reviewId = writtenReview(BUYER_ID, lecture, confirmedItem(BUYER_ID, lecture), 2);

        mockMvc.perform(patch("/api/v1/lectures/reviews/{reviewId}", reviewId)
                        .with(TestLogin.as(OTHER_BUYER_ID, Role.USER)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\": 5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_REVIEW_WRITER"));
        mockMvc.perform(delete("/api/v1/lectures/reviews/{reviewId}", reviewId)
                        .with(TestLogin.as(OTHER_BUYER_ID, Role.USER)).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_REVIEW_WRITER"));
        mockMvc.perform(delete("/api/v1/lectures/reviews/{reviewId}", Long.MAX_VALUE)
                        .with(TestLogin.as(BUYER_ID, Role.USER)).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    @Test
    void 삭제한_리뷰는_고치거나_다시_삭제할_수_없다() throws Exception {
        Long reviewId = writtenReview(BUYER_ID, lecture, confirmedItem(BUYER_ID, lecture), 2);
        mockMvc.perform(delete("/api/v1/lectures/reviews/{reviewId}", reviewId)
                        .with(TestLogin.as(BUYER_ID, Role.USER)).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/lectures/reviews/{reviewId}", reviewId)
                        .with(TestLogin.as(BUYER_ID, Role.USER)).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    @Test
    void 탈퇴한_회원의_리뷰는_탈퇴한_회원으로_나온다() throws Exception {
        writtenReview(BUYER_ID, lecture, confirmedItem(BUYER_ID, lecture), 5);
        lectureUserRepository.save(LectureUser.from(new UserDto(BUYER_ID, "구매자", Role.USER, UserStatus.WITHDRAWN)));

        reviews(lecture).andExpect(jsonPath("$.data.content[0].reviewerNickname").value("탈퇴한 회원"));
    }

    @Test
    void 회원_복제가_아직_없는_작성자의_닉네임은_null이다() throws Exception {
        writtenReview(OTHER_BUYER_ID, lecture, confirmedItem(OTHER_BUYER_ID, lecture), 5);

        reviews(lecture).andExpect(jsonPath("$.data.content[0].reviewerNickname").value(nullValue()));
    }

    @Test
    void 관리자는_리뷰를_쓰거나_수정_삭제할_수_없다() throws Exception {
        Long reviewId = writtenReview(BUYER_ID, lecture, confirmedItem(BUYER_ID, lecture), 5);

        mockMvc.perform(post("/api/v1/lectures/{lectureId}/reviews", lecture.getId())
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderItemId\": 1, \"rating\": 5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(patch("/api/v1/lectures/reviews/{reviewId}", reviewId)
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\": 1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(delete("/api/v1/lectures/reviews/{reviewId}", reviewId)
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 관리자도_리뷰_목록은_본다() throws Exception {
        writtenReview(BUYER_ID, lecture, confirmedItem(BUYER_ID, lecture), 5);

        mockMvc.perform(get("/api/v1/lectures/{lectureId}/reviews", lecture.getId())
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1));
    }

    @Test
    void 평점이_범위를_벗어나거나_본문이_1000자를_넘으면_400_INVALID_REQUEST다() throws Exception {
        Long orderItemId = confirmedItem(BUYER_ID, lecture);

        write(BUYER_ID, lecture.getId(), orderItemId, 6, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("rating"));
        write(BUYER_ID, lecture.getId(), orderItemId, 5, "가".repeat(1001))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("content"));
        write(BUYER_ID, lecture.getId(), orderItemId, 5, "가".repeat(1000)).andExpect(status().isCreated());
    }

    @Test
    void 리뷰_목록은_최신순이다() throws Exception {
        Long first = writtenReview(BUYER_ID, lecture, confirmedItem(BUYER_ID, lecture), 5);
        Long second = writtenReview(OTHER_BUYER_ID, lecture, confirmedItem(OTHER_BUYER_ID, lecture), 3);

        reviews(lecture)
                .andExpect(jsonPath("$.data.content[0].reviewId").value(second))
                .andExpect(jsonPath("$.data.content[1].reviewId").value(first));
    }

    @Test
    void 판매_중이_아닌_강의의_리뷰_목록은_판매자와_보유_구매자만_본다() throws Exception {
        writtenReview(BUYER_ID, lecture, confirmedItem(BUYER_ID, lecture), 5);
        transactionTemplate.executeWithoutResult(status ->
                lectureRepository.findById(lecture.getId()).orElseThrow().delete(SELLER_ID, NOW));

        mockMvc.perform(get("/api/v1/lectures/{lectureId}/reviews", lecture.getId())
                        .with(TestLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/lectures/{lectureId}/reviews", lecture.getId())
                        .with(TestLogin.as(BUYER_ID, Role.USER)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/lectures/{lectureId}/reviews", lecture.getId())
                        .with(TestLogin.as(OTHER_BUYER_ID, Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
    }

    @Test
    void 보유_구매자는_삭제된_강의에도_리뷰를_쓴다() throws Exception {
        Long orderItemId = confirmedItem(BUYER_ID, lecture);
        transactionTemplate.executeWithoutResult(status ->
                lectureRepository.findById(lecture.getId()).orElseThrow().delete(SELLER_ID, NOW));

        write(BUYER_ID, lecture.getId(), orderItemId, 4, null).andExpect(status().isCreated());
    }

    private ResultActions write(Long userId, Long lectureId, Long orderItemId, int rating, String content)
            throws Exception {
        String body = content == null
                ? "{\"orderItemId\": %d, \"rating\": %d}".formatted(orderItemId, rating)
                : "{\"orderItemId\": %d, \"rating\": %d, \"content\": \"%s\"}".formatted(orderItemId, rating, content);
        return mockMvc.perform(post("/api/v1/lectures/{lectureId}/reviews", lectureId)
                .with(TestLogin.as(userId, Role.USER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private Long writtenReview(Long userId, Lecture lecture, Long orderItemId, int rating) throws Exception {
        return write(userId, lecture.getId(), orderItemId, rating, null)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(LectureReviewApiTest::id);
    }

    private ResultActions reviews(Lecture lecture) throws Exception {
        return mockMvc.perform(get("/api/v1/lectures/{lectureId}/reviews", lecture.getId())
                        .with(TestLogin.as(OTHER_BUYER_ID, Role.USER)))
                .andExpect(status().isOk());
    }

    private Lecture saveOnSaleLecture() {
        Lecture lecture = Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }

    private Long purchasedItem(Long buyerUserId, Lecture lecture) {
        LectureOrder order = lectureOrderRepository.save(LectureOrderFixture.order(buyerUserId, List.of(lecture), NOW));
        return order.getItems().getFirst().getId();
    }

    private Long confirmedItem(Long buyerUserId, Lecture lecture) {
        Long orderItemId = purchasedItem(buyerUserId, lecture);
        changeStatus(orderItemId, OrderItemStatus.CONFIRMED);
        return orderItemId;
    }

    /** 반환·지급 전이는 아직 API가 없어 상태를 SQL로 바꾼다 */
    private void changeStatus(Long orderItemId, OrderItemStatus status) {
        jdbcTemplate.update("update lectures.order_item set status = ? where id = ?", status.name(), orderItemId);
    }

    private static Long id(String json) {
        return ((Number) JsonPath.read(json, "$.data.id")).longValue();
    }
}

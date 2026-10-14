package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import com.hapbang.lecture.domain.FileType;
import com.hapbang.lecture.domain.Lecture;
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

import jakarta.persistence.EntityManagerFactory;

// 목록 응답의 썸네일·닉네임·평균 평점을 강의마다(리뷰 목록은 작성자 닉네임을 리뷰마다) 따로 조회하지 않는지(D28, D41)
// 강의·리뷰 수에 따른 SQL 문 수로 확인한다.
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        // 세션마다 남는 통계 로그를 끈다
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
})
class LectureThumbnailQueryCountTest {

    private static final Long BUYER_ID = 2L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureUserRepository lectureUserRepository;

    @Autowired
    private FileResourceRepository fileResourceRepository;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    private long nextSellerId = 10L;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void 강의가_늘어도_목록_조회_SQL_수는_같다() throws Exception {
        RequestBuilder lectures = get("/api/v1/lectures");
        saveOnSaleLectures(1, newSeller());
        long one = statementsOf(lectures);

        saveOnSaleLectures(1, newSeller());
        saveOnSaleLectures(1, newSeller());

        assertThat(statementsOf(lectures)).isEqualTo(one);
        mockMvc.perform(lectures)
                .andExpect(jsonPath("$.data.content.length()").value(3))
                .andExpect(jsonPath("$.data.content[*].thumbnailUrl", everyItem(notNullValue())));
    }

    @Test
    void 리뷰가_있는_강의가_늘어도_목록_조회_SQL_수는_정렬과_관계없이_같다() throws Exception {
        RequestBuilder latest = get("/api/v1/lectures");
        RequestBuilder rating = get("/api/v1/lectures").param("sort", "RATING");
        reviewAll(saveOnSaleLectures(1, newSeller()));
        long latestOne = statementsOf(latest);
        long ratingOne = statementsOf(rating);

        reviewAll(saveOnSaleLectures(2, newSeller()));

        assertThat(statementsOf(latest)).isEqualTo(latestOne);
        assertThat(statementsOf(rating)).isEqualTo(ratingOne);
        mockMvc.perform(rating)
                .andExpect(jsonPath("$.data.content.length()").value(3))
                .andExpect(jsonPath("$.data.content[*].averageRating", everyItem(notNullValue())));
    }

    @Test
    void 리뷰가_늘어도_리뷰_목록_SQL_수는_같다() throws Exception {
        Long lectureId = saveOnSaleLectures(1, newSeller()).getFirst();
        RequestBuilder reviews = get("/api/v1/lectures/{lectureId}/reviews", lectureId)
                .with(TestLogin.as(BUYER_ID, Role.USER));
        saveReview(lectureId, newSeller());
        long one = statementsOf(reviews);

        saveReview(lectureId, newSeller());
        saveReview(lectureId, newSeller());

        assertThat(statementsOf(reviews)).isEqualTo(one);
        mockMvc.perform(reviews)
                .andExpect(jsonPath("$.data.content.length()").value(3))
                .andExpect(jsonPath("$.data.content[*].reviewerNickname", everyItem(notNullValue())));
    }

    @Test
    void 내_강의가_늘어도_SQL_수는_같다() throws Exception {
        Long sellerId = newSeller();
        RequestBuilder myLectures = get("/api/v1/lectures/me").with(TestLogin.as(sellerId, Role.VERIFIED_INFLUENCER));
        saveOnSaleLectures(1, sellerId);
        long one = statementsOf(myLectures);

        saveOnSaleLectures(2, sellerId);

        assertThat(statementsOf(myLectures)).isEqualTo(one);
        mockMvc.perform(myLectures)
                .andExpect(jsonPath("$.data.content.length()").value(3))
                .andExpect(jsonPath("$.data.content[*].thumbnailUrl", everyItem(notNullValue())));
    }

    @Test
    void 장바구니_항목이_늘어도_SQL_수는_같다() throws Exception {
        RequestBuilder cart = get("/api/v1/lectures/cart").with(TestLogin.as(BUYER_ID, Role.USER));
        addItems(saveOnSaleLectures(1, newSeller()));
        long one = statementsOf(cart);

        addItems(saveOnSaleLectures(1, newSeller()));
        addItems(saveOnSaleLectures(1, newSeller()));

        assertThat(statementsOf(cart)).isEqualTo(one);
        mockMvc.perform(cart)
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[*].thumbnailUrl", everyItem(notNullValue())));
    }

    private long statementsOf(RequestBuilder request) throws Exception {
        statistics.clear();
        mockMvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200));
        return statistics.getPrepareStatementCount();
    }

    /** 회원 복제본이 있는 판매자 */
    private Long newSeller() {
        Long sellerId = nextSellerId++;
        lectureUserRepository.save(LectureUser.from(
                new UserDto(sellerId, "판매자" + sellerId, Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE)));
        return sellerId;
    }

    /** 썸네일이 있는 판매 중 강의 */
    private List<Long> saveOnSaleLectures(int count, Long sellerId) {
        List<Long> lectureIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Long thumbnailId = fileResourceRepository.save(uploadedFile(sellerId, FileType.LECTURE_THUMBNAIL)).getId();
            Lecture lecture = Lecture.register(sellerId, TITLE, DESCRIPTION, PRICE_HP, thumbnailId, contentSpecs(), NOW);
            lecture.approve(ADMIN_ID, NOW);
            lectureIds.add(lectureRepository.save(lecture).getId());
        }
        return lectureIds;
    }

    private void reviewAll(List<Long> lectureIds) {
        lectureIds.forEach(lectureId -> saveReview(lectureId, newSeller()));
    }

    /** 회원 복제본이 있는 회원이 구매 확정한 항목으로 쓴 리뷰 */
    private void saveReview(Long lectureId, Long reviewerId) {
        Lecture lecture = lectureRepository.findById(lectureId).orElseThrow();
        Long orderItemId = lectureOrderRepository.save(LectureOrderFixture.order(reviewerId, List.of(lecture), NOW))
                .getItems().getFirst().getId();
        jdbcTemplate.update("update lectures.order_item set status = 'CONFIRMED' where id = ?", orderItemId);
        jdbcTemplate.update("insert into lectures.lecture_review "
                + "(lecture_id, order_item_id, reviewer_user_id, rating, created_at, updated_at) "
                + "values (?, ?, ?, 4, now(), now())", lectureId, orderItemId, reviewerId);
    }

    private void addItems(List<Long> lectureIds) throws Exception {
        for (Long lectureId : lectureIds) {
            mockMvc.perform(post("/api/v1/lectures/cart/items")
                            .with(TestLogin.as(BUYER_ID, Role.USER)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"lectureId\": %d}".formatted(lectureId)))
                    .andExpect(status().isNoContent());
        }
    }
}

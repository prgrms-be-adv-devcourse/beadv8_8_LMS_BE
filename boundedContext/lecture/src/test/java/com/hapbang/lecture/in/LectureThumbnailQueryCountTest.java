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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import com.hapbang.lecture.domain.FileType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureUser;
import com.hapbang.lecture.out.FileResourceRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.out.LectureUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

import jakarta.persistence.EntityManagerFactory;

// 목록 응답의 썸네일·닉네임을 강의마다 따로 조회하지 않는지(D26) 강의 수에 따른 SQL 문 수로 확인한다.
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
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[*].thumbnailUrl", everyItem(notNullValue())));
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
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[*].thumbnailUrl", everyItem(notNullValue())));
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
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[*].thumbnailUrl", everyItem(notNullValue())));
    }

    private long statementsOf(RequestBuilder request) throws Exception {
        statistics.clear();
        mockMvc.perform(request).andExpect(status().isOk());
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

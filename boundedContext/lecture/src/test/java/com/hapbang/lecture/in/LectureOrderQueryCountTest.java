package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

import jakarta.persistence.EntityManagerFactory;

// 주문 목록·시청 가능한 강의 목록이 주문·항목마다 따로 조회하지 않는지 개수에 따른 SQL 문 수로 확인한다.
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        // 세션마다 남는 통계 로그를 끈다
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
})
class LectureOrderQueryCountTest {

    private static final Long BUYER_ID = OTHER_USER_ID;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void 주문이_늘어도_주문_목록_조회_SQL_수는_같다() throws Exception {
        RequestBuilder orders = get("/api/v1/lectures/orders").with(TestLogin.as(BUYER_ID, Role.USER));
        saveOrder();
        long one = statementsOf(orders);

        saveOrder();
        saveOrder();

        assertThat(statementsOf(orders)).isEqualTo(one);
        mockMvc.perform(orders).andExpect(jsonPath("$.data.content.length()").value(3));
    }

    @Test
    void 항목이_늘어도_시청_가능한_강의_목록_조회_SQL_수는_같다() throws Exception {
        RequestBuilder purchased = get("/api/v1/lectures/purchased").with(TestLogin.as(BUYER_ID, Role.USER));
        saveOrder();
        long one = statementsOf(purchased);

        saveOrder();
        saveOrder();

        assertThat(statementsOf(purchased)).isEqualTo(one);
        mockMvc.perform(purchased).andExpect(jsonPath("$.data.content.length()").value(6));
    }

    private long statementsOf(RequestBuilder request) throws Exception {
        statistics.clear();
        mockMvc.perform(request).andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
    }

    /** 강의 2개를 담은 주문 */
    private void saveOrder() {
        List<Lecture> lectures = List.of(saveOnSale(), saveOnSale());
        lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID, lectures, NOW));
    }

    private Lecture saveOnSale() {
        Lecture lecture = Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }
}

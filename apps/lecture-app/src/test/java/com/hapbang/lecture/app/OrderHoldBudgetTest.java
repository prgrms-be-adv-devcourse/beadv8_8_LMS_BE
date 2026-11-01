package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;

import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.ProcessingStatus;
import com.hapbang.lecture.fake.FakeBillingApiClient;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.testsupport.IntegrationTest;

/** 주문 요청 시간 예산 (D68). 예산을 넘기면 남은 보류는 요청 안에서 시도하지 않고 배치에 넘긴다 */
@IntegrationTest
@TestPropertySource(properties = "lecture.order.hold-request-budget=200ms")
@WithMockUser(roles = "USER")
class OrderHoldBudgetTest {

    private static final Long BUYER_ID = OTHER_USER_ID;

    @Autowired
    private LectureOrderFacade lectureOrderFacade;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureHpTaskBatchUseCase lectureHpTaskBatchUseCase;

    @Autowired
    private FakeBillingApiClient billing;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        billing.reset();
    }

    @Test
    void 시간_예산을_넘기면_남은_보류는_시도하지_않고_결제_처리_중으로_응답한다() {
        Lecture first = saveOnSale("OBS 방송 세팅");
        Lecture second = saveOnSale("마이크 고르기");
        // 첫 보류가 예산보다 오래 걸린다. 시작한 호출은 끊지 않는다
        billing.setDuringCall(() -> sleep(300));

        OrderResponse response = lectureOrderFacade.createOrder(BUYER_ID, List.of(first.getId(), second.getId()));

        assertThat(response.status()).isEqualTo(LectureOrderStatus.HOLD_PENDING);
        assertThat(response.processingStatus()).isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(billing.calls()).hasSize(1);

        billing.setDuringCall(() -> {
        });
        lectureHpTaskBatchUseCase.runDue();

        assertThat(lectureOrderFacade.getOrder(BUYER_ID, response.orderId()).status())
                .isEqualTo(LectureOrderStatus.PAID);
        assertThat(billing.calls()).hasSize(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from lectures.lecture_hp_task", Long.class))
                .isEqualTo(2);
    }

    private Lecture saveOnSale(String title) {
        Lecture lecture = Lecture.register(SELLER_ID, title, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.hapbang.lecture.global.ratelimit;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.fake.FakeBillingApiClient;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.support.GatewayLogin;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 주문·구매 취소의 회원별 요청 한도(D80)를 실제 보안 필터·인터셉터·공통 핸들러를 거쳐 확인한다. 한도는 작게(분당 2) 두고,
 * 다른 테스트와 카운터를 섞지 않게 이 클래스만 쓰는 컨텍스트를 띄워 끝나면 닫는다(지갑 RateLimitIntegrationTest와 같음).
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "lecture.rate-limit.write=2")
@DirtiesContext
class RateLimitIntegrationTest {

    // 카운터는 컨텍스트에 남으므로 테스트마다 다른 회원을 쓴다
    private static long nextUserId = 1_000L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private FakeBillingApiClient billing;

    @Autowired
    private MeterRegistry meterRegistry;

    private long userId;

    @BeforeEach
    void setUp() {
        billing.reset();
        billing.setSettledHp(PRICE_HP);
        userId = nextUserId++;
    }

    @Test
    void 주문을_한도보다_많이_보내면_429와_Retry_After이고_주문과_지갑_호출이_없다() throws Exception {
        order(saveOnSale("OBS 방송 세팅")).andExpect(status().isCreated());
        order(saveOnSale("마이크 고르기")).andExpect(status().isCreated());
        long ordersBefore = lectureOrderRepository.count();
        double rejectedBefore = rejected("order");

        order(saveOnSale("조명 배치"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", matchesPattern("[1-9][0-9]?")))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.detail").value("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."));

        assertThat(lectureOrderRepository.count()).isEqualTo(ordersBefore);
        assertThat(billing.calls()).hasSize(2);
        assertThat(rejected("order")).isEqualTo(rejectedBefore + 1);
    }

    @Test
    void 구매_취소는_주문과_따로_세고_다른_회원도_따로_센다() throws Exception {
        long orderId = orderId(order(saveOnSale("OBS 방송 세팅")).andExpect(status().isCreated()));
        long secondOrderId = orderId(order(saveOnSale("마이크 고르기")).andExpect(status().isCreated()));
        order(saveOnSale("조명 배치")).andExpect(status().isTooManyRequests());

        // 주문 한도를 다 써도 구매 취소는 따로 센다. 없는 항목이라 404지만 한도는 쓴다
        cancel(orderId, 999_999L).andExpect(status().isNotFound());
        cancel(secondOrderId, 999_999L).andExpect(status().isNotFound());
        cancel(orderId, 999_999L).andExpect(status().isTooManyRequests());

        long otherUser = nextUserId++;
        mockMvc.perform(post("/api/v1/lectures/orders")
                        .with(GatewayLogin.as(otherUser, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lectureIds\": [" + saveOnSale("카메라 고르기").getId() + "]}"))
                .andExpect(status().isCreated());
    }

    @Test
    void 요청_값이_틀려_400으로_끝나는_요청도_한도를_쓴다() throws Exception {
        for (int i = 0; i < 2; i++) {
            orderBody("{\"lectureIds\": []}").andExpect(status().isBadRequest());
        }

        order(saveOnSale("OBS 방송 세팅")).andExpect(status().isTooManyRequests());
    }

    @Test
    void 한도를_걸지_않은_API는_세지_않는다() throws Exception {
        long orderId = orderId(order(saveOnSale("OBS 방송 세팅")).andExpect(status().isCreated()));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/confirm", orderId, 999_999L)
                            .with(GatewayLogin.as(userId, Role.USER)))
                    .andExpect(status().isNotFound());
        }
    }

    private ResultActions order(Lecture lecture) throws Exception {
        return orderBody("{\"lectureIds\": [" + lecture.getId() + "]}");
    }

    private ResultActions orderBody(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/lectures/orders")
                .with(GatewayLogin.as(userId, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions cancel(long orderId, long orderItemId) throws Exception {
        return mockMvc.perform(post("/api/v1/lectures/orders/{orderId}/items/{orderItemId}/cancel", orderId,
                orderItemId).with(GatewayLogin.as(userId, Role.USER)));
    }

    private Lecture saveOnSale(String title) {
        Lecture lecture = Lecture.register(SELLER_ID, title, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }

    private double rejected(String api) {
        return meterRegistry.get(UserRateLimiter.REJECTED_METRIC).tag("api", api).functionCounter().count();
    }

    private static long orderId(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.orderId"))
                .longValue();
    }
}

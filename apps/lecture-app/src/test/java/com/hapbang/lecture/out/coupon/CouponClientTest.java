package com.hapbang.lecture.out.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.hapbang.shared.coupon.api.CouponCalculateRequest;
import com.hapbang.shared.coupon.api.CouponPriceRaiseBlockedResponse;
import com.hapbang.shared.internal.InternalApiHeaders;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

// 쿠폰 사용 내부 API를 부르는 강의 HTTP Interface와 어댑터의 요청 모양·결과 4분류·circuit breaker를 MockRestServiceServer로 확인한다
class CouponClientTest {

    private static final String BASE_URL = "http://coupon.test";
    private static final String API_KEY = "unit-internal-api-key";
    private static final String CALCULATE_URL = BASE_URL + "/api/v1/internal/coupons/calculate";
    private static final String BLOCKED_URL = BASE_URL + "/api/v1/internal/coupons/price-raise-blocked?lectureId=101";
    private static final CouponCalculateRequest CALCULATE = new CouponCalculateRequest(7L,
            List.of(new CouponCalculateRequest.Item(101L, 30L, 30_000L)), null);

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void 내부_키_헤더를_붙여_보내고_성공_응답의_data를_돌려준다() {
        CouponClient client = client(LectureCouponClientProperties.defaults());
        server.expect(requestTo(CALCULATE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(InternalApiHeaders.API_KEY, API_KEY))
                .andExpect(content().json("""
                        {"memberId":7,"items":[{"lectureId":101,"sellerId":30,"price":30000}],"selections":null}"""))
                .andRespond(withSuccess("""
                        {"statusCode":200,"message":"쿠폰 할인을 계산했습니다.",
                         "data":{"recommended":true,"optimal":true,"items":[{"lectureId":101,"price":30000,
                          "discount":5000,"platformDiscount":0,"sellerDiscount":5000,"buyerAmount":25000,
                          "sellerPayout":25000,"coupons":[]}],"totalDiscount":5000,"totalBuyerAmount":25000}}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BLOCKED_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"statusCode":200,"message":"","data":{"lectureId":101,"blocked":true}}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.calculate(CALCULATE)).isInstanceOfSatisfying(CouponCallResult.Succeeded.class,
                succeeded -> assertThat(succeeded.data()).hasFieldOrPropertyWithValue("totalBuyerAmount", 25_000L));
        assertThat(client.priceRaiseBlocked(101L))
                .isEqualTo(new CouponCallResult.Succeeded<>(new CouponPriceRaiseBlockedResponse(101L, true)));
        server.verify();
    }

    @Test
    void 쿠폰의_4xx_거절은_code와_발급_쿠폰_ID를_그대로_싣고_회로_실패로_세지_않는다() {
        CouponClient client = client(smallWindow());
        server.expect(requestTo(CALCULATE_URL)).andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("""
                        {"status":409,"detail":"쿠폰 상태가 바뀌었습니다.","code":"COUPON_GENERATION_MISMATCH",
                         "memberCouponId":502}"""));
        // 본문 없는 401(내부 API 키 오류)도 쿠폰이 판단한 거절이다
        server.expect(requestTo(CALCULATE_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(client.calculate(CALCULATE)).isEqualTo(new CouponCallResult.Rejected<>(409,
                "COUPON_GENERATION_MISMATCH", 502L, "쿠폰 상태가 바뀌었습니다."));
        assertThat(client.calculate(CALCULATE)).isEqualTo(new CouponCallResult.Rejected<>(401, null, null, null));
        assertThat(client.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(client.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
        server.verify();
    }

    @Test
    void 서버_오류와_응답_시간_초과와_data_없는_성공은_결과_불명이다() {
        CouponClient client = client(LectureCouponClientProperties.defaults());
        server.expect(requestTo(CALCULATE_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"status\":500,\"code\":\"INTERNAL_ERROR\"}"));
        server.expect(requestTo(CALCULATE_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));
        server.expect(requestTo(CALCULATE_URL)).andRespond(withSuccess("{\"statusCode\":200,\"message\":\"\"}",
                MediaType.APPLICATION_JSON));

        assertThat(client.calculate(CALCULATE)).isInstanceOfSatisfying(CouponCallResult.Unknown.class,
                unknown -> assertThat(unknown.status()).isEqualTo(500));
        assertThat(client.calculate(CALCULATE)).isInstanceOfSatisfying(CouponCallResult.Unknown.class,
                unknown -> assertThat(unknown.status()).isZero());
        assertThat(client.calculate(CALCULATE)).isInstanceOfSatisfying(CouponCallResult.Unknown.class,
                unknown -> assertThat(unknown.status()).isEqualTo(200));
        assertThat(client.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(3);
        server.verify();
    }

    @Test
    void 연결_실패와_처리하지_않았다는_응답은_보내지_않음이다() {
        CouponClient client = client(LectureCouponClientProperties.defaults());
        server.expect(requestTo(CALCULATE_URL)).andRespond(withException(new ConnectException("Connection refused")));
        server.expect(requestTo(CALCULATE_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(client.calculate(CALCULATE)).isInstanceOf(CouponCallResult.NotSent.class);
        assertThat(client.calculate(CALCULATE)).isInstanceOf(CouponCallResult.NotSent.class);
        assertThat(client.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
        server.verify();
    }

    @Test
    void 실패가_쌓여_회로가_열리면_HTTP를_부르지_않고_보내지_않음이다() {
        CouponClient client = client(smallWindow());
        assertThat(client.canCall()).isTrue();
        server.expect(times(2), requestTo(CALCULATE_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(client.calculate(CALCULATE)).isInstanceOf(CouponCallResult.Unknown.class);
        assertThat(client.calculate(CALCULATE)).isInstanceOf(CouponCallResult.Unknown.class);
        assertThat(client.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(client.canCall()).isFalse();

        assertThat(client.calculate(CALCULATE)).isInstanceOf(CouponCallResult.NotSent.class);
        server.verify();
    }

    private CouponClient client(LectureCouponClientProperties properties) {
        return new CouponClient(LectureCouponApiConfig.create(builder, BASE_URL, API_KEY), properties);
    }

    // 최근 2번 중 2번 모두 실패하면 열리고, 테스트 동안 다시 HALF_OPEN이 되지 않는 회로
    private static LectureCouponClientProperties smallWindow() {
        return new LectureCouponClientProperties(null, null, 2, 2, null, null, null, Duration.ofMinutes(5), null);
    }
}

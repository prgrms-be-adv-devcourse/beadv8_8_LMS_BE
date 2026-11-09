package com.hapbang.coupon.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.coupon.app.CouponCalculateUseCase;
import com.hapbang.coupon.app.CouponInternalFacade;
import com.hapbang.coupon.app.CouponPriceRaiseBlockedQuery;
import com.hapbang.coupon.app.CouponReleaseUseCase;
import com.hapbang.coupon.app.CouponReservationStatusQuery;
import com.hapbang.coupon.app.CouponReserveUseCase;
import com.hapbang.coupon.config.CouponCalculateConfig;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponExceptionHandler;
import com.hapbang.coupon.global.exception.CouponUsageException;
import com.hapbang.shared.coupon.api.CouponCalculateRequest;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;
import com.hapbang.shared.coupon.api.CouponPriceRaiseBlockedResponse;
import com.hapbang.shared.coupon.api.CouponReleaseRequest;
import com.hapbang.shared.coupon.api.CouponReleaseResponse;
import com.hapbang.shared.coupon.api.CouponReservationStatusResponse;
import com.hapbang.shared.coupon.api.CouponReserveRequest;
import com.hapbang.shared.coupon.api.CouponReserveResponse;
import com.hapbang.testsupport.WebSliceTestConfiguration;

// 쿠폰 사용 내부 API 다섯 경로의 요청 검증·응답 모양·역할 검사를 본다. Facade는 진짜(요청 모양 검사와 @PreAuthorize)이고 UseCase는 대역이다.
// 내부 API 키 검사는 lecture-app의 보안 체인이 /api/v1/internal/** 전체에 하므로 이 슬라이스에는 없고, 키 대신 INTERNAL 역할 사용자로 부른다.
// 요청 값 오류의 본문(code INVALID_REQUEST)은 같은 JVM의 강의 공통 핸들러가 만들어 이 슬라이스에서는 상태만 확인한다
@ContextConfiguration(classes = WebSliceTestConfiguration.class)
@WebMvcTest(controllers = ApiV1CouponInternalController.class,
        properties = "hapbang.coupon.calculate.max-items=2")
@Import({ApiV1CouponInternalController.class, CouponInternalFacade.class, CouponCalculateConfig.class,
        CouponExceptionHandler.class, ApiV1CouponInternalControllerTest.MethodSecurityConfig.class})
class ApiV1CouponInternalControllerTest {

    private static final String BASE = "/api/v1/internal/coupons";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CouponCalculateUseCase couponCalculateUseCase;
    @MockitoBean
    private CouponReserveUseCase couponReserveUseCase;
    @MockitoBean
    private CouponReleaseUseCase couponReleaseUseCase;
    @MockitoBean
    private CouponReservationStatusQuery couponReservationStatusQuery;
    @MockitoBean
    private CouponPriceRaiseBlockedQuery couponPriceRaiseBlockedQuery;

    // 각 테스트가 verify한 호출 말고는 UseCase 호출이 없어야 한다(검증에 걸린 요청은 UseCase까지 가지 않는다)
    @AfterEach
    void 확인한_호출_말고는_UseCase를_부르지_않는다() {
        verifyNoMoreInteractions(couponCalculateUseCase, couponReserveUseCase,
                couponReleaseUseCase, couponReservationStatusQuery, couponPriceRaiseBlockedQuery);
    }

    // ---------------------------------------------------------------------------------------------
    // 성공 응답 모양

    @Test
    void 계산은_추천_결과와_쿠폰별_근거를_돌려준다() throws Exception {
        CouponCalculateRequest request = new CouponCalculateRequest(7L,
                List.of(new CouponCalculateRequest.Item(101L, 30L, 30000L)), null);
        given(couponCalculateUseCase.calculate(request)).willReturn(new CouponCalculateResponse(true, true,
                List.of(new CouponCalculateResponse.Item(101L, 30000L, 5000L, 0L, 5000L, 25000L, 25000L,
                        List.of(new CouponCalculateResponse.Coupon(501L, 11L, "SELLER", 1, 30000L, 5000L, 0L,
                                5000L, 0L, 1)))),
                5000L, 25000L));

        mockMvc.perform(internal(post(BASE + "/calculate"))
                        .content("""
                                {"memberId":7,"items":[{"lectureId":101,"sellerId":30,"price":30000}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("쿠폰 할인을 계산했습니다."))
                .andExpect(jsonPath("$.data.recommended").value(true))
                .andExpect(jsonPath("$.data.optimal").value(true))
                .andExpect(jsonPath("$.data.items[0].lectureId").value(101))
                .andExpect(jsonPath("$.data.items[0].buyerAmount").value(25000))
                .andExpect(jsonPath("$.data.items[0].sellerPayout").value(25000))
                .andExpect(jsonPath("$.data.items[0].coupons[0].couponType").value("SELLER"))
                .andExpect(jsonPath("$.data.items[0].coupons[0].expectedGeneration").value(0))
                .andExpect(jsonPath("$.data.totalDiscount").value(5000))
                .andExpect(jsonPath("$.data.totalBuyerAmount").value(25000));

        verify(couponCalculateUseCase).calculate(request);
    }

    @Test
    void 계산의_selections가_빈_목록이면_빈_목록_그대로_넘긴다() throws Exception {
        CouponCalculateRequest request = new CouponCalculateRequest(7L,
                List.of(new CouponCalculateRequest.Item(101L, 30L, 30000L)), List.of());
        given(couponCalculateUseCase.calculate(request)).willReturn(new CouponCalculateResponse(false, true,
                List.of(new CouponCalculateResponse.Item(101L, 30000L, 0L, 0L, 0L, 30000L, 30000L, List.of())),
                0L, 30000L));

        mockMvc.perform(internal(post(BASE + "/calculate"))
                        .content("""
                                {"memberId":7,"items":[{"lectureId":101,"sellerId":30,"price":30000}],"selections":[]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommended").value(false))
                .andExpect(jsonPath("$.data.items[0].coupons").isEmpty());

        verify(couponCalculateUseCase).calculate(request);
    }

    @Test
    void 예약은_예약된_generation과_항목별_결과를_돌려준다() throws Exception {
        given(couponReserveUseCase.reserve(any())).willReturn(new CouponReserveResponse("order-9001:reserve",
                "RESERVED", List.of(new CouponReserveResponse.Item(90011L, 5000L, 0L, 5000L,
                List.of(new CouponReserveResponse.Coupon(501L, 11L, 1L, 1, 30000L, 5000L, 0L, 5000L, 1))))));

        mockMvc.perform(internal(post(BASE + "/reservations")).content(reserveBody("order-9001:reserve")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("쿠폰을 예약했습니다."))
                .andExpect(jsonPath("$.data.workId").value("order-9001:reserve"))
                .andExpect(jsonPath("$.data.status").value("RESERVED"))
                .andExpect(jsonPath("$.data.items[0].orderItemId").value(90011))
                .andExpect(jsonPath("$.data.items[0].coupons[0].reservedGeneration").value(1))
                .andExpect(jsonPath("$.data.items[0].coupons[0].baseAmount").value(30000));

        verify(couponReserveUseCase).reserve(new CouponReserveRequest(7L, 9001L, "order-9001:reserve",
                List.of(new CouponReserveRequest.Item(90011L, 101L, 30L, 30000L,
                        List.of(new CouponReserveRequest.Coupon(501L, 0L, 1, 1, 5000L, 0L, 5000L))))));
    }

    @Test
    void 예약_거절은_쿠폰_오류_코드와_실패한_발급_쿠폰_ID로_응답한다() throws Exception {
        given(couponReserveUseCase.reserve(any()))
                .willThrow(new CouponUsageException(CouponErrorCode.COUPON_GENERATION_MISMATCH, 501L));

        mockMvc.perform(internal(post(BASE + "/reservations")).content(reserveBody("order-9001:reserve")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_GENERATION_MISMATCH"))
                .andExpect(jsonPath("$.memberCouponId").value(501));

        verify(couponReserveUseCase).reserve(any());
    }

    @Test
    void 해제는_사용_시도별_해제_뒤_상태를_돌려준다() throws Exception {
        CouponReleaseRequest request = new CouponReleaseRequest(9001L, "order-9001:release",
                List.of(new CouponReleaseRequest.Usage(501L, 1L)), "HOLD_FAILED", null);
        given(couponReleaseUseCase.release(request)).willReturn(new CouponReleaseResponse("order-9001:release",
                List.of(new CouponReleaseResponse.Usage(501L, 1L, "EXPIRED"))));

        mockMvc.perform(internal(post(BASE + "/releases")).content(releaseBody("order-9001:release")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("쿠폰 예약을 해제했습니다."))
                .andExpect(jsonPath("$.data.workId").value("order-9001:release"))
                .andExpect(jsonPath("$.data.usages[0].memberCouponId").value(501))
                .andExpect(jsonPath("$.data.usages[0].generation").value(1))
                .andExpect(jsonPath("$.data.usages[0].releasedTo").value("EXPIRED"));

        verify(couponReleaseUseCase).release(request);
    }

    @Test
    void 예약_상태는_작업_상태와_사용_시도별_상태를_돌려준다() throws Exception {
        given(couponReservationStatusQuery.status("order-9001:reserve", 7L, 9001L))
                .willReturn(new CouponReservationStatusResponse("order-9001:reserve", "RESERVED", null,
                        List.of(new CouponReservationStatusResponse.Item(90011L, 5000L, 0L, 5000L,
                                List.of(new CouponReservationStatusResponse.Coupon(501L, 11L, 1L, 1, 30000L,
                                        5000L, 0L, 5000L, 1, "RESERVED"))))));

        mockMvc.perform(internal(get(BASE + "/reservations/{workId}", "order-9001:reserve"))
                        .param("memberId", "7").param("orderId", "9001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("쿠폰 예약 상태를 조회했습니다."))
                .andExpect(jsonPath("$.data.status").value("RESERVED"))
                .andExpect(jsonPath("$.data.items[0].coupons[0].status").value("RESERVED"));

        verify(couponReservationStatusQuery).status("order-9001:reserve", 7L, 9001L);
    }

    @Test
    void 가격_인상_제한은_강의_ID와_제한_여부를_돌려준다() throws Exception {
        given(couponPriceRaiseBlockedQuery.blocked(101L)).willReturn(new CouponPriceRaiseBlockedResponse(101L, true));

        mockMvc.perform(internal(get(BASE + "/price-raise-blocked")).param("lectureId", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("가격 인상 제한을 조회했습니다."))
                .andExpect(jsonPath("$.data.lectureId").value(101))
                .andExpect(jsonPath("$.data.blocked").value(true));

        verify(couponPriceRaiseBlockedQuery).blocked(101L);
    }

    // ---------------------------------------------------------------------------------------------
    // 요청 검증

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "공백 있는 작업",
            "a/b",
            "a.b",
            // 101자
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    })
    void 예약과_해제의_작업_ID_형식이_틀리면_400_INVALID_WORK_ID다(String workId) throws Exception {
        mockMvc.perform(internal(post(BASE + "/reservations")).content(reserveBody(workId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WORK_ID"));
        mockMvc.perform(internal(post(BASE + "/releases")).content(releaseBody(workId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WORK_ID"));
    }

    @Test
    void 작업_ID는_영문_숫자_콜론_밑줄_하이픈으로_100자까지_받는다() throws Exception {
        String workId = "Ab9:_-".repeat(16) + "abcd";
        given(couponReleaseUseCase.release(any())).willReturn(new CouponReleaseResponse(workId, List.of()));

        mockMvc.perform(internal(post(BASE + "/releases")).content(releaseBody(workId)))
                .andExpect(status().isOk());

        verify(couponReleaseUseCase).release(any());
    }

    @Test
    void 예약_상태의_작업_ID_형식이_틀리면_400_INVALID_WORK_ID다() throws Exception {
        mockMvc.perform(internal(get(BASE + "/reservations/{workId}", "a.b"))
                        .param("memberId", "7").param("orderId", "9001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WORK_ID"));
    }

    @Test
    void 계산과_예약의_항목이_상한을_넘으면_400_COUPON_TOO_MANY_ITEMS다() throws Exception {
        mockMvc.perform(internal(post(BASE + "/calculate"))
                        .content("""
                                {"memberId":7,"items":[{"lectureId":1,"sellerId":30,"price":100},
                                {"lectureId":2,"sellerId":30,"price":100},{"lectureId":3,"sellerId":30,"price":100}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COUPON_TOO_MANY_ITEMS"));
        mockMvc.perform(internal(post(BASE + "/reservations"))
                        .content("""
                                {"memberId":7,"orderId":9001,"workId":"w-1","items":[
                                {"orderItemId":1,"lectureId":1,"sellerId":30,"price":100,"coupons":[]},
                                {"orderItemId":2,"lectureId":2,"sellerId":30,"price":100,"coupons":[]},
                                {"orderItemId":3,"lectureId":3,"sellerId":30,"price":100,"coupons":[]}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COUPON_TOO_MANY_ITEMS"));
    }

    @Test
    void 계산과_예약에_같은_강의가_두_번_있으면_400_COUPON_DUPLICATE_LECTURE다() throws Exception {
        mockMvc.perform(internal(post(BASE + "/calculate"))
                        .content("""
                                {"memberId":7,"items":[{"lectureId":1,"sellerId":30,"price":100},
                                {"lectureId":1,"sellerId":30,"price":100}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COUPON_DUPLICATE_LECTURE"));
        mockMvc.perform(internal(post(BASE + "/reservations"))
                        .content("""
                                {"memberId":7,"orderId":9001,"workId":"w-1","items":[
                                {"orderItemId":1,"lectureId":1,"sellerId":30,"price":100,"coupons":[]},
                                {"orderItemId":2,"lectureId":1,"sellerId":30,"price":100,"coupons":[]}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COUPON_DUPLICATE_LECTURE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"items\":[{\"lectureId\":1,\"sellerId\":30,\"price\":100}]}",
            "{\"memberId\":7,\"items\":[]}",
            "{\"memberId\":7,\"items\":[{\"lectureId\":1,\"sellerId\":30}]}",
            "{\"memberId\":7,\"items\":[{\"lectureId\":1,\"sellerId\":30,\"price\":-1}]}",
            "{\"memberId\":7,\"items\":[{\"lectureId\":1,\"sellerId\":30,\"price\":100}],\"selections\":[{\"lectureId\":1}]}"
    })
    void 계산의_필수_값이_없거나_빈_항목_음수_가격이면_400이다(String body) throws Exception {
        mockMvc.perform(internal(post(BASE + "/calculate")).content(body))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"memberId\":7,\"workId\":\"w-1\",\"items\":[{\"orderItemId\":1,\"lectureId\":1,\"sellerId\":30,\"price\":100,\"coupons\":[]}]}",
            "{\"memberId\":7,\"orderId\":9001,\"workId\":\"w-1\",\"items\":[]}",
            "{\"memberId\":7,\"orderId\":9001,\"workId\":\"w-1\",\"items\":[{\"orderItemId\":1,\"lectureId\":1,\"sellerId\":30,\"price\":100}]}",
            "{\"memberId\":7,\"orderId\":9001,\"workId\":\"w-1\",\"items\":[{\"orderItemId\":1,\"lectureId\":1,\"sellerId\":30,\"price\":100,"
                    + "\"coupons\":[{\"memberCouponId\":501,\"policyVersion\":1,\"applyOrder\":1,\"discount\":0,\"platformAmount\":0,\"sellerAmount\":0}]}]}"
    })
    void 예약의_필수_값이_없거나_빈_항목이면_400이다(String body) throws Exception {
        mockMvc.perform(internal(post(BASE + "/reservations")).content(body))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"workId\":\"w-1\",\"usages\":[{\"memberCouponId\":501,\"generation\":1}],\"reason\":\"HOLD_FAILED\"}",
            "{\"orderId\":9001,\"workId\":\"w-1\",\"usages\":[],\"reason\":\"HOLD_FAILED\"}",
            "{\"orderId\":9001,\"workId\":\"w-1\",\"usages\":[{\"memberCouponId\":501}],\"reason\":\"HOLD_FAILED\"}",
            "{\"orderId\":9001,\"workId\":\"w-1\",\"usages\":[{\"memberCouponId\":501,\"generation\":1}]}"
    })
    void 해제의_필수_값이_없거나_빈_사용_시도면_400이다(String body) throws Exception {
        mockMvc.perform(internal(post(BASE + "/releases")).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 해제_사유는_500자_보상_근거는_200자까지_받고_넘으면_400이다() throws Exception {
        given(couponReleaseUseCase.release(any())).willReturn(new CouponReleaseResponse("w-1", List.of()));

        mockMvc.perform(internal(post(BASE + "/releases")).content(releaseBody("w-1", "r".repeat(500), "c".repeat(200))))
                .andExpect(status().isOk());
        mockMvc.perform(internal(post(BASE + "/releases")).content(releaseBody("w-1", "r".repeat(501), null)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(internal(post(BASE + "/releases")).content(releaseBody("w-1", "HOLD_FAILED", "c".repeat(201))))
                .andExpect(status().isBadRequest());

        verify(couponReleaseUseCase).release(any());
    }

    @Test
    void 조회의_필수_쿼리가_없거나_숫자가_아니면_400이다() throws Exception {
        mockMvc.perform(internal(get(BASE + "/reservations/{workId}", "w-1")).param("memberId", "7"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(internal(get(BASE + "/reservations/{workId}", "w-1")).param("orderId", "9001"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(internal(get(BASE + "/price-raise-blocked")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(internal(get(BASE + "/price-raise-blocked")).param("lectureId", "abc"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------------------------------------
    // 역할

    @Test
    void 회원_역할로는_다섯_경로_모두_403이고_UseCase를_부르지_않는다() throws Exception {
        RequestPostProcessor member = user("7").roles("USER", "ADMIN");

        mockMvc.perform(json(post(BASE + "/calculate")).with(member).with(csrf())
                        .content("{\"memberId\":7,\"items\":[{\"lectureId\":1,\"sellerId\":30,\"price\":100}]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post(BASE + "/reservations")).with(member).with(csrf()).content(reserveBody("w-1")))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post(BASE + "/releases")).with(member).with(csrf()).content(releaseBody("w-1")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/reservations/{workId}", "w-1").with(member)
                        .param("memberId", "7").param("orderId", "9001"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/price-raise-blocked").with(member).param("lectureId", "1"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(couponCalculateUseCase, couponReserveUseCase, couponReleaseUseCase,
                couponReservationStatusQuery, couponPriceRaiseBlockedQuery);
    }

    @Test
    void 인증이_없으면_401이고_UseCase를_부르지_않는다() throws Exception {
        mockMvc.perform(get(BASE + "/price-raise-blocked").param("lectureId", "1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/reservations/{workId}", "w-1").param("memberId", "7").param("orderId", "9001"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(couponPriceRaiseBlockedQuery, couponReservationStatusQuery);
    }

    // ---------------------------------------------------------------------------------------------

    /** 내부 API 키 검사를 통과한 요청과 같은 INTERNAL 역할로 부른다 */
    private static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
        return json(request).with(user("internal").roles("INTERNAL")).with(csrf());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request) {
        return request.contentType(MediaType.APPLICATION_JSON);
    }

    private static String reserveBody(String workId) {
        return """
                {"memberId":7,"orderId":9001,"workId":"%s","items":[{"orderItemId":90011,"lectureId":101,"sellerId":30,
                "price":30000,"coupons":[{"memberCouponId":501,"expectedGeneration":0,"policyVersion":1,"applyOrder":1,
                "discount":5000,"platformAmount":0,"sellerAmount":5000}]}]}""".formatted(workId);
    }

    private static String releaseBody(String workId) {
        return """
                {"orderId":9001,"workId":"%s","usages":[{"memberCouponId":501,"generation":1}],"reason":"HOLD_FAILED"}"""
                .formatted(workId);
    }

    private static String releaseBody(String workId, String reason, String compensationRef) {
        return """
                {"orderId":9001,"workId":"%s","usages":[{"memberCouponId":501,"generation":1}],"reason":"%s",
                "compensationRef":%s}""".formatted(workId, reason,
                compensationRef == null ? "null" : "\"" + compensationRef + "\"");
    }

    /** Facade의 @PreAuthorize를 켠다. 운영에서는 lecture-app 보안 설정이 켠다 */
    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }
}

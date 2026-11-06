package com.hapbang.coupon.in;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.coupon.app.CouponAdminFacade;
import com.hapbang.coupon.app.dto.CouponView;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.DiscountType;
import com.hapbang.coupon.domain.ExpiryType;
import com.hapbang.coupon.global.config.CouponWebMvcConfig;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.global.exception.CouponExceptionHandler;
import com.hapbang.testsupport.WebSliceTestConfiguration;

// 관리자 쿠폰 API의 경로·요청 값 검증·응답 모양만 본다. ADMIN 검사(/admin URL·Facade)와 열기 순서는 강의 보안 설정·Facade가 맡는다
@ContextConfiguration(classes = WebSliceTestConfiguration.class)
@WebMvcTest(controllers = ApiV1CouponAdminController.class)
@Import({ApiV1CouponAdminController.class, CouponWebMvcConfig.class, CouponExceptionHandler.class})
class ApiV1CouponAdminControllerTest {

    private static final long ADMIN_ID = 1L;
    private static final long COUPON_ID = 42L;
    private static final Instant START = Instant.parse("2026-11-07T00:00:00Z");
    private static final Instant END = Instant.parse("2026-11-14T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CouponAdminFacade couponAdminFacade;

    @Test
    void 플랫폼_쿠폰_발행은_로그인_관리자로_부르고_201이다() throws Exception {
        given(couponAdminFacade.createPlatform(eq(ADMIN_ID), any())).willReturn(view(CouponStatus.DRAFT));

        mockMvc.perform(post("/api/v1/coupons/admin")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"ANY_LECTURE","discountType":"RATE","discountValue":10,"maxDiscountAmount":3000,
                                 "quantity":100,"issueStartAt":"2026-11-07T00:00:00Z","issueEndAt":"2026-11-14T00:00:00Z",
                                 "expiryType":"FIXED","fixedExpiresAt":"2026-12-31T00:00:00Z","stackable":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.data.type").value("PLATFORM"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void 열기는_200과_바뀐_쿠폰을_돌려준다() throws Exception {
        given(couponAdminFacade.open(COUPON_ID)).willReturn(view(CouponStatus.OPEN));

        mockMvc.perform(post("/api/v1/coupons/admin/{couponId}/open", COUPON_ID).with(admin()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.couponId").value(COUPON_ID))
                .andExpect(jsonPath("$.data.status").value("OPEN"));
    }

    @Test
    void 열기_실패는_쿠폰_오류_코드로_응답한다() throws Exception {
        given(couponAdminFacade.open(COUPON_ID))
                .willThrow(new CouponException(CouponErrorCode.PROMOTION_BUDGET_UNAVAILABLE));

        mockMvc.perform(post("/api/v1/coupons/admin/{couponId}/open", COUPON_ID).with(admin()).with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PROMOTION_BUDGET_UNAVAILABLE"));
    }

    @Test
    void 거절은_사유를_넘긴다() throws Exception {
        given(couponAdminFacade.reject(COUPON_ID, "할인율이 과도합니다")).willReturn(view(CouponStatus.REJECTED));

        mockMvc.perform(post("/api/v1/coupons/admin/{couponId}/reject", COUPON_ID)
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"할인율이 과도합니다\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"reason\":\"  \"}"})
    void 거절_사유가_비면_400이고_거절하지_않는다(String body) throws Exception {
        mockMvc.perform(post("/api/v1/coupons/admin/{couponId}/reject", COUPON_ID)
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(couponAdminFacade, never()).reject(anyLong(), anyString());
    }

    private static RequestPostProcessor admin() {
        return user(String.valueOf(ADMIN_ID)).roles("ADMIN");
    }

    private static CouponView view(CouponStatus status) {
        return new CouponView(COUPON_ID, CouponType.PLATFORM, ADMIN_ID, CouponTargetType.ANY_LECTURE, null, null,
                DiscountType.RATE, 10, 3_000L, 100, 0, START, END, ExpiryType.RELATIVE, 30, null, false, 1, status, null);
    }
}

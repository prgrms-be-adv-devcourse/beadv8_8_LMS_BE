package com.hapbang.coupon.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.coupon.app.CouponSellerFacade;
import com.hapbang.coupon.app.dto.CouponCreateCommand;
import com.hapbang.coupon.app.dto.CouponView;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.DiscountType;
import com.hapbang.coupon.domain.ExpiryType;
import com.hapbang.coupon.global.config.CouponWebMvcConfig;
import com.hapbang.coupon.global.exception.CouponExceptionHandler;
import com.hapbang.testsupport.WebSliceTestConfiguration;

// 판매자 쿠폰 API의 경로·요청 값 검증·응답 모양만 본다. 역할·본인 검사와 발행 규칙은 Facade·도메인이 맡는다
@ContextConfiguration(classes = WebSliceTestConfiguration.class)
@WebMvcTest(controllers = ApiV1CouponSellerController.class)
@Import({ApiV1CouponSellerController.class, CouponWebMvcConfig.class, CouponExceptionHandler.class})
class ApiV1CouponSellerControllerTest {

    private static final long SELLER_ID = 10L;
    private static final long COUPON_ID = 42L;
    private static final Instant START = Instant.parse("2026-11-07T00:00:00Z");
    private static final Instant END = Instant.parse("2026-11-14T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CouponSellerFacade couponSellerFacade;

    @Test
    void 쿠폰_신청은_요청을_발행_입력으로_넘기고_201과_쿠폰을_돌려준다() throws Exception {
        given(couponSellerFacade.create(eq(SELLER_ID), any())).willReturn(view(CouponStatus.DRAFT));

        mockMvc.perform(post("/api/v1/coupons/seller")
                        .with(seller()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"LECTURE","lectureId":7,"discountType":"RATE","discountValue":10,
                                 "quantity":100,"issueStartAt":"2026-11-07T00:00:00Z","issueEndAt":"2026-11-14T00:00:00Z",
                                 "expiryType":"RELATIVE","expiryDays":30,"stackable":false}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.data.couponId").value(COUPON_ID))
                .andExpect(jsonPath("$.data.type").value("SELLER"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.basePrice").value(10_000));

        ArgumentCaptor<CouponCreateCommand> command = ArgumentCaptor.forClass(CouponCreateCommand.class);
        verify(couponSellerFacade).create(eq(SELLER_ID), command.capture());
        assertThat(command.getValue()).isEqualTo(new CouponCreateCommand(CouponTargetType.LECTURE, 7L,
                DiscountType.RATE, 10, null, 100, START, END, ExpiryType.RELATIVE, 30, null, false));
    }

    @Test
    void 필수_값이_빠지면_400이고_신청하지_않는다() throws Exception {
        // quantity 없음
        mockMvc.perform(post("/api/v1/coupons/seller")
                        .with(seller()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"LECTURE","lectureId":7,"discountType":"RATE","discountValue":10,
                                 "issueStartAt":"2026-11-07T00:00:00Z","issueEndAt":"2026-11-14T00:00:00Z",
                                 "expiryType":"RELATIVE","expiryDays":30,"stackable":false}
                                """))
                .andExpect(status().isBadRequest());

        verify(couponSellerFacade, never()).create(anyLong(), any());
    }

    @Test
    void 심사_요청과_중단은_로그인_판매자로_부르고_200이다() throws Exception {
        given(couponSellerFacade.submitReview(SELLER_ID, COUPON_ID)).willReturn(view(CouponStatus.REVIEW));
        given(couponSellerFacade.suspend(SELLER_ID, COUPON_ID)).willReturn(view(CouponStatus.SUSPENDED));

        mockMvc.perform(post("/api/v1/coupons/seller/{couponId}/review", COUPON_ID).with(seller()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.status").value("REVIEW"));
        mockMvc.perform(post("/api/v1/coupons/seller/{couponId}/suspend", COUPON_ID).with(seller()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
    }

    private static RequestPostProcessor seller() {
        return user(String.valueOf(SELLER_ID)).roles("VERIFIED_INFLUENCER");
    }

    private static CouponView view(CouponStatus status) {
        return new CouponView(COUPON_ID, CouponType.SELLER, SELLER_ID, CouponTargetType.LECTURE, 7L, 10_000L,
                DiscountType.RATE, 10, null, 100, 0, START, END, ExpiryType.RELATIVE, 30, null, false, 1, status, null);
    }
}

package com.hapbang.coupon.in;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.coupon.app.CouponAdminFacade;
import com.hapbang.coupon.app.CouponIssueFacade;
import com.hapbang.coupon.app.CouponSellerFacade;
import com.hapbang.coupon.global.config.CouponWebMvcConfig;
import com.hapbang.coupon.global.exception.CouponExceptionHandler;
import com.hapbang.testsupport.WebSliceTestConfiguration;

// 세 컨트롤러를 함께 올려 /api/v1/coupons 아래 이름 경로(/seller, /admin)가 쿠폰 상세(/{couponId})로 가지 않는지 본다
@ContextConfiguration(classes = WebSliceTestConfiguration.class)
@WebMvcTest(controllers = {ApiV1CouponController.class, ApiV1CouponSellerController.class,
        ApiV1CouponAdminController.class})
@Import({ApiV1CouponController.class, ApiV1CouponSellerController.class, ApiV1CouponAdminController.class,
        CouponWebMvcConfig.class, CouponExceptionHandler.class})
class ApiV1CouponRoutingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CouponIssueFacade couponIssueFacade;
    @MockitoBean
    private CouponSellerFacade couponSellerFacade;
    @MockitoBean
    private CouponAdminFacade couponAdminFacade;

    @Test
    void 판매자_관리자_목록_경로는_회원_쿠폰_상세로_가지_않는다() throws Exception {
        mockMvc.perform(get("/api/v1/coupons/seller").with(user("10").roles("VERIFIED_INFLUENCER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/coupons/admin").with(user("1").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(couponSellerFacade).myCoupons(10L, null);
        verify(couponAdminFacade).coupons(null);
        verify(couponIssueFacade, never()).coupon(anyLong(), anyLong());
    }
}

package com.hapbang.coupon.in;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
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

// 세 컨트롤러를 함께 올려 /api/v1/coupons 아래 경로가 각자 맞는 Facade 메서드로만 가는지 본다.
// 이름 경로(/me, /seller, /admin)가 쿠폰 상세(/{couponId})로 가거나 그 반대가 되지 않아야 한다
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

    // 각 테스트가 verify한 호출 말고는 어느 Facade에도 호출이 없어야 한다
    @AfterEach
    void 다른_Facade와_다른_메서드는_호출되지_않는다() {
        verifyNoMoreInteractions(couponIssueFacade, couponSellerFacade, couponAdminFacade);
    }

    @Test
    void 내_쿠폰_목록은_회원_Facade의_내_쿠폰_조회로만_간다() throws Exception {
        mockMvc.perform(get("/api/v1/coupons/me").with(user("7").roles("USER")))
                .andExpect(status().isOk());

        verify(couponIssueFacade).myCoupons(7L, null);
    }

    @Test
    void 강의에_쓸_수_있는_쿠폰은_회원_Facade의_사용_가능_조회로만_간다() throws Exception {
        mockMvc.perform(get("/api/v1/coupons/me/usable").param("lectureId", "3").with(user("7").roles("USER")))
                .andExpect(status().isOk());

        verify(couponIssueFacade).usableFor(7L, 3L);
    }

    @Test
    void 숫자_경로는_회원_Facade의_쿠폰_상세로만_간다() throws Exception {
        mockMvc.perform(get("/api/v1/coupons/5").with(user("7").roles("USER")))
                .andExpect(status().isOk());

        verify(couponIssueFacade).coupon(7L, 5L);
    }

    @Test
    void 판매자_목록은_판매자_Facade의_목록_조회로만_간다() throws Exception {
        mockMvc.perform(get("/api/v1/coupons/seller").with(user("10").roles("VERIFIED_INFLUENCER")))
                .andExpect(status().isOk());

        verify(couponSellerFacade).myCoupons(10L, null);
    }

    @Test
    void 판매자_숫자_경로는_판매자_Facade의_쿠폰_상세로만_간다() throws Exception {
        mockMvc.perform(get("/api/v1/coupons/seller/5").with(user("10").roles("VERIFIED_INFLUENCER")))
                .andExpect(status().isOk());

        verify(couponSellerFacade).myCoupon(10L, 5L);
    }

    @Test
    void 관리자_목록은_관리자_Facade의_목록_조회로만_간다() throws Exception {
        mockMvc.perform(get("/api/v1/coupons/admin").with(user("1").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(couponAdminFacade).coupons(null);
    }

    @Test
    void 관리자_숫자_경로는_관리자_Facade의_쿠폰_상세로만_간다() throws Exception {
        mockMvc.perform(get("/api/v1/coupons/admin/5").with(user("1").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(couponAdminFacade).coupon(5L);
    }
}

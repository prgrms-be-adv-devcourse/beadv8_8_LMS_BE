package com.hapbang.coupon.in;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.coupon.app.CouponReconciliationAdminFacade;
import com.hapbang.coupon.app.CouponReconciliationUseCase;
import com.hapbang.coupon.app.dto.ReconcileHoldReleaseView;
import com.hapbang.coupon.global.config.CouponWebMvcConfig;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.global.exception.CouponExceptionHandler;
import com.hapbang.testsupport.WebSliceTestConfiguration;

// 대사 보류 해제 API의 경로·로그인 관리자 ID·응답 모양과 Facade의 ADMIN 검사(메서드 보안을 켠 실제 Facade, UseCase는 대역)
@ContextConfiguration(classes = WebSliceTestConfiguration.class)
@WebMvcTest(controllers = ApiV1CouponReconcileAdminController.class)
@Import({ApiV1CouponReconcileAdminController.class, CouponReconciliationAdminFacade.class, CouponWebMvcConfig.class,
        CouponExceptionHandler.class, ApiV1CouponReconcileAdminControllerTest.MethodSecurity.class})
class ApiV1CouponReconcileAdminControllerTest {

    private static final long ADMIN_ID = 1L;
    private static final long COUPON_ID = 42L;
    private static final String PATH = "/api/v1/coupons/admin/{couponId}/reconcile/release";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CouponReconciliationUseCase couponReconciliationUseCase;

    @Test
    void 관리자가_보류를_풀면_로그인_관리자_ID로_부르고_200이다() throws Exception {
        given(couponReconciliationUseCase.releaseReconcileHold(ADMIN_ID, COUPON_ID))
                .willReturn(new ReconcileHoldReleaseView(COUPON_ID, true, null));

        mockMvc.perform(post(PATH, COUPON_ID).with(user(String.valueOf(ADMIN_ID)).roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.couponId").value(COUPON_ID))
                .andExpect(jsonPath("$.data.released").value(true));
    }

    @Test
    void 다른_사유의_보류면_409_코드로_응답한다() throws Exception {
        given(couponReconciliationUseCase.releaseReconcileHold(ADMIN_ID, COUPON_ID))
                .willThrow(new CouponException(CouponErrorCode.COUPON_RECONCILE_HOLD_NOT_RELEASABLE));

        mockMvc.perform(post(PATH, COUPON_ID).with(user(String.valueOf(ADMIN_ID)).roles("ADMIN")).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_RECONCILE_HOLD_NOT_RELEASABLE"));
    }

    @Test
    void 관리자가_아니면_403이고_보류를_건드리지_않는다() throws Exception {
        mockMvc.perform(post(PATH, COUPON_ID).with(user("2").roles("VERIFIED_INFLUENCER")).with(csrf()))
                .andExpect(status().isForbidden());

        verify(couponReconciliationUseCase, never()).releaseReconcileHold(anyLong(), anyLong());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurity {
    }
}

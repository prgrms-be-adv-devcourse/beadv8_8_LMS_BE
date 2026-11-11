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

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.coupon.app.CouponRecoveryAdminFacade;
import com.hapbang.coupon.app.CouponRecoveryUseCase;
import com.hapbang.coupon.app.dto.RecoveryRunView;
import com.hapbang.coupon.domain.RecoveryStep;
import com.hapbang.coupon.global.config.CouponWebMvcConfig;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.global.exception.CouponExceptionHandler;
import com.hapbang.testsupport.WebSliceTestConfiguration;

// 복구 취소 API의 경로·로그인 관리자 전달·오류 응답과 Facade 역할 검사. 단계 규칙은 RecoveryRunTest·CouponRecoveryUseCaseTest가 본다
@ContextConfiguration(classes = WebSliceTestConfiguration.class)
@WebMvcTest(controllers = ApiV1CouponRecoveryAdminController.class)
@Import({ApiV1CouponRecoveryAdminController.class, CouponRecoveryAdminFacade.class, CouponWebMvcConfig.class,
        CouponExceptionHandler.class, ApiV1CouponRecoveryAdminControllerTest.MethodSecurityConfig.class})
class ApiV1CouponRecoveryAdminControllerTest {

    private static final long ADMIN_ID = 1L;
    private static final long COUPON_ID = 42L;
    private static final long RECOVERY_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final String ABORT = "/api/v1/coupons/admin/{couponId}/recovery/{recoveryId}/abort";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CouponRecoveryUseCase couponRecoveryUseCase;

    @Test
    void 취소는_로그인_관리자_ID로_부르고_취소된_복구를_돌려준다() throws Exception {
        given(couponRecoveryUseCase.abort(ADMIN_ID, COUPON_ID, RECOVERY_ID)).willReturn(abortedView());

        mockMvc.perform(post(ABORT, COUPON_ID, RECOVERY_ID).with(login("ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.recoveryId").value(RECOVERY_ID))
                .andExpect(jsonPath("$.data.step").value("ABORTED"))
                .andExpect(jsonPath("$.data.abortedBy").value(ADMIN_ID));

        verify(couponRecoveryUseCase).abort(ADMIN_ID, COUPON_ID, RECOVERY_ID);
    }

    @Test
    void 이미_취소한_복구를_다시_취소해도_200으로_그_복구를_돌려준다() throws Exception {
        given(couponRecoveryUseCase.abort(ADMIN_ID, COUPON_ID, RECOVERY_ID)).willReturn(abortedView(), abortedView());

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post(ABORT, COUPON_ID, RECOVERY_ID).with(login("ADMIN")).with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.step").value("ABORTED"))
                    .andExpect(jsonPath("$.data.abortedAt").value(NOW.toString()));
        }
    }

    @Test
    void 재구축_뒤_취소는_409_COUPON_RECOVERY_STEP_NOT_ALLOWED다() throws Exception {
        given(couponRecoveryUseCase.abort(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .willThrow(new CouponException(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED, "재구축한 복구는 취소할 수 없습니다."));

        mockMvc.perform(post(ABORT, COUPON_ID, RECOVERY_ID).with(login("ADMIN")).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_RECOVERY_STEP_NOT_ALLOWED"));
    }

    @Test
    void 관리자가_아니면_403이고_취소하지_않는다() throws Exception {
        mockMvc.perform(post(ABORT, COUPON_ID, RECOVERY_ID).with(login("USER")).with(csrf()))
                .andExpect(status().isForbidden());

        verify(couponRecoveryUseCase, never()).abort(anyLong(), anyLong(), anyLong());
    }

    private static RequestPostProcessor login(String role) {
        return user(String.valueOf(ADMIN_ID)).roles(role);
    }

    private static RecoveryRunView abortedView() {
        return new RecoveryRunView(RECOVERY_ID, COUPON_ID, 1L, 2L, RecoveryStep.ABORTED, true, null, null, null, null,
                null, null, null, null, ADMIN_ID, NOW, NOW, null, ADMIN_ID, NOW, NOW, null);
    }

    /** 운영에서는 강의 앱 SecurityConfig가 켜는 메서드 보안 */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }
}

package com.hapbang.coupon.in;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.coupon.app.CouponReconciliationAdminFacade;
import com.hapbang.coupon.app.dto.ReconcileHoldReleaseView;
import com.hapbang.coupon.global.auth.LoginUserId;
import com.hapbang.coupon.global.response.RsData;

import lombok.RequiredArgsConstructor;

/** 관리자 쿠폰 발급 대사 API. Swagger 명세는 {@link ApiV1CouponReconcileAdminSpec} */
@RestController
// 관리자 API는 /admin 경로(강의 앱 SecurityConfig)와 CouponReconciliationAdminFacade의 @PreAuthorize로 함께 막는다
@RequestMapping("/api/v1/coupons/admin/{couponId}/reconcile")
@RequiredArgsConstructor
public class ApiV1CouponReconcileAdminController implements ApiV1CouponReconcileAdminSpec {

    private final CouponReconciliationAdminFacade couponReconciliationAdminFacade;

    @Override
    @PostMapping("/release")
    public RsData<ReconcileHoldReleaseView> release(@LoginUserId Long adminId,
                                                    @PathVariable("couponId") Long couponId) {
        ReconcileHoldReleaseView view = couponReconciliationAdminFacade.releaseReconcileHold(adminId, couponId);
        return RsData.ok(view.released() ? "대사 불일치 보류를 풀었습니다." : "풀 대사 불일치 보류가 없습니다.", view);
    }
}

package com.hapbang.coupon.in;

import com.hapbang.coupon.app.dto.ReconcileHoldReleaseView;
import com.hapbang.coupon.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 관리자 쿠폰 발급 대사 API Swagger 명세. 매핑은 {@link ApiV1CouponReconcileAdminController} */
@Tag(name = "쿠폰 발급 대사", description = "관리자의 발급 수량 대사 불일치 보류 해제")
public interface ApiV1CouponReconcileAdminSpec {

    @Operation(summary = "대사 불일치 보류 해제",
            description = "관리자. 대사 배치가 수량 불일치로 건 신규 발급 보류(RECONCILE)를 원인을 확인한 뒤 푼다. 원인이 그대로면 다음 대사 회차가 다시 "
                    + "보류한다. 보류가 없으면 바꾸지 않고 released=false로 응답한다(다시 보내도 된다). 보류가 다른 사유(적체 BACKLOG·복구 RECOVERY)면 "
                    + "409 COUPON_RECONCILE_HOLD_NOT_RELEASABLE이고 바꾸지 않는다. 쿠폰이 없으면 404, 발급 저장소 장애면 503.")
    RsData<ReconcileHoldReleaseView> release(Long adminId, Long couponId);
}

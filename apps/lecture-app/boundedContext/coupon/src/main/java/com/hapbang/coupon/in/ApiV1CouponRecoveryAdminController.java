package com.hapbang.coupon.in;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.coupon.app.CouponRecoveryAdminFacade;
import com.hapbang.coupon.app.dto.RecoveryRunView;
import com.hapbang.coupon.global.auth.LoginUserId;
import com.hapbang.coupon.global.response.RsData;
import com.hapbang.coupon.in.dto.RecoveryResumeRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 관리자 쿠폰 발급 복구 API. Swagger 명세는 {@link ApiV1CouponRecoveryAdminSpec} */
@RestController
// 관리자 API는 /admin 경로(강의 앱 SecurityConfig)와 CouponRecoveryAdminFacade의 @PreAuthorize로 함께 막는다
@RequestMapping("/api/v1/coupons/admin/{couponId}/recovery")
@RequiredArgsConstructor
public class ApiV1CouponRecoveryAdminController implements ApiV1CouponRecoveryAdminSpec {

    private final CouponRecoveryAdminFacade couponRecoveryAdminFacade;

    @Override
    @GetMapping
    public RsData<List<RecoveryRunView>> list(@PathVariable("couponId") Long couponId) {
        return RsData.ok("복구 실행 목록입니다.", couponRecoveryAdminFacade.list(couponId));
    }

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<RecoveryRunView> start(@LoginUserId Long adminId, @PathVariable("couponId") Long couponId) {
        return RsData.created("복구를 시작해 신규 발급을 막았습니다.", couponRecoveryAdminFacade.start(adminId, couponId));
    }

    @Override
    @PostMapping("/{recoveryId}/boundary")
    public RsData<RecoveryRunView> fixBoundary(@LoginUserId Long adminId, @PathVariable("couponId") Long couponId,
                                               @PathVariable("recoveryId") Long recoveryId) {
        return RsData.ok("복구 경계를 확정했습니다.", couponRecoveryAdminFacade.fixBoundary(adminId, couponId, recoveryId));
    }

    @Override
    @PostMapping("/{recoveryId}/results")
    public RsData<RecoveryRunView> confirmResults(@LoginUserId Long adminId, @PathVariable("couponId") Long couponId,
                                                  @PathVariable("recoveryId") Long recoveryId) {
        return RsData.ok("복구 결과를 확인했습니다.",
                couponRecoveryAdminFacade.confirmResults(adminId, couponId, recoveryId));
    }

    @Override
    @PostMapping("/{recoveryId}/rebuild")
    public RsData<RecoveryRunView> rebuild(@LoginUserId Long adminId, @PathVariable("couponId") Long couponId,
                                           @PathVariable("recoveryId") Long recoveryId) {
        return RsData.ok("발급 기록을 재구축했습니다.", couponRecoveryAdminFacade.rebuild(adminId, couponId, recoveryId));
    }

    @Override
    @PostMapping("/{recoveryId}/abort")
    public RsData<RecoveryRunView> abort(@LoginUserId Long adminId, @PathVariable("couponId") Long couponId,
                                         @PathVariable("recoveryId") Long recoveryId) {
        return RsData.ok("복구를 취소했습니다. 신규 발급은 계속 막혀 있습니다.",
                couponRecoveryAdminFacade.abort(adminId, couponId, recoveryId));
    }

    @Override
    @PostMapping("/{recoveryId}/resume")
    public RsData<RecoveryRunView> resume(@LoginUserId Long adminId, @PathVariable("couponId") Long couponId,
                                          @PathVariable("recoveryId") Long recoveryId,
                                          @Valid @RequestBody RecoveryResumeRequest request) {
        return RsData.ok("복구를 마치고 발급을 재개했습니다.",
                couponRecoveryAdminFacade.resume(adminId, couponId, recoveryId, request.resumeBasis()));
    }
}

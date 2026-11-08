package com.hapbang.coupon.in;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.coupon.app.CouponAdminFacade;
import com.hapbang.coupon.app.dto.CouponView;
import com.hapbang.coupon.global.auth.LoginUserId;
import com.hapbang.coupon.global.response.RsData;
import com.hapbang.coupon.in.dto.CouponCreateRequest;
import com.hapbang.coupon.in.dto.CouponRejectRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 관리자 쿠폰 API. Swagger 명세는 {@link ApiV1CouponAdminSpec} */
@RestController
// 관리자 API는 /admin 경로(강의 앱 SecurityConfig)와 CouponAdminFacade의 @PreAuthorize로 함께 막는다
@RequestMapping("/api/v1/coupons/admin")
@RequiredArgsConstructor
public class ApiV1CouponAdminController implements ApiV1CouponAdminSpec {

    private final CouponAdminFacade couponAdminFacade;

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<CouponView> createPlatform(@LoginUserId Long adminId, @Valid @RequestBody CouponCreateRequest request) {
        return RsData.created("플랫폼 쿠폰을 발행했습니다.", couponAdminFacade.createPlatform(adminId, request.toCommand()));
    }

    @Override
    @PostMapping("/{couponId}/open")
    public RsData<CouponView> open(@LoginUserId Long adminId, @PathVariable("couponId") Long couponId) {
        return RsData.ok("쿠폰 발급을 열었습니다.", couponAdminFacade.open(adminId, couponId));
    }

    @Override
    @PostMapping("/{couponId}/approve")
    public RsData<CouponView> approve(@LoginUserId Long adminId, @PathVariable("couponId") Long couponId) {
        return RsData.ok("쿠폰을 승인했습니다.", couponAdminFacade.approve(adminId, couponId));
    }

    @Override
    @PostMapping("/{couponId}/reject")
    public RsData<CouponView> reject(@LoginUserId Long adminId, @PathVariable("couponId") Long couponId,
                                     @Valid @RequestBody CouponRejectRequest request) {
        return RsData.ok("쿠폰을 거절했습니다.", couponAdminFacade.reject(adminId, couponId, request.reason()));
    }

    @Override
    @PostMapping("/{couponId}/suspend")
    public RsData<CouponView> suspend(@LoginUserId Long adminId, @PathVariable("couponId") Long couponId) {
        return RsData.ok("쿠폰 발급을 중단했습니다.", couponAdminFacade.suspend(adminId, couponId));
    }

    @Override
    @PostMapping("/{couponId}/resume")
    public RsData<CouponView> resume(@LoginUserId Long adminId, @PathVariable("couponId") Long couponId) {
        return RsData.ok("쿠폰 발급을 재개했습니다.", couponAdminFacade.resume(adminId, couponId));
    }

    @Override
    @PostMapping("/{couponId}/close")
    public RsData<CouponView> close(@LoginUserId Long adminId, @PathVariable("couponId") Long couponId) {
        return RsData.ok("쿠폰 발급을 종료했습니다.", couponAdminFacade.close(adminId, couponId));
    }
}

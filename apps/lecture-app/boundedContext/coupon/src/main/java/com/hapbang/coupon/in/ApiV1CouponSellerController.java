package com.hapbang.coupon.in;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.coupon.app.CouponSellerFacade;
import com.hapbang.coupon.app.dto.CouponView;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.global.auth.LoginUserId;
import com.hapbang.coupon.global.response.RsData;
import com.hapbang.coupon.in.dto.CouponCreateRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 판매자 쿠폰 API. 역할 검사는 {@link CouponSellerFacade}, 본인 여부는 UseCase가 한다. Swagger 명세는 {@link ApiV1CouponSellerSpec} */
@RestController
@RequestMapping("/api/v1/coupons/seller")
@RequiredArgsConstructor
public class ApiV1CouponSellerController implements ApiV1CouponSellerSpec {

    private final CouponSellerFacade couponSellerFacade;

    @Override
    @GetMapping
    public RsData<List<CouponView>> myCoupons(@LoginUserId Long sellerId,
                                              @RequestParam(name = "status", required = false) CouponStatus status) {
        return RsData.ok("신청한 쿠폰을 조회했습니다.", couponSellerFacade.myCoupons(sellerId, status));
    }

    @Override
    @GetMapping("/{couponId:\\d+}")
    public RsData<CouponView> myCoupon(@LoginUserId Long sellerId, @PathVariable("couponId") Long couponId) {
        return RsData.ok("쿠폰을 조회했습니다.", couponSellerFacade.myCoupon(sellerId, couponId));
    }

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<CouponView> create(@LoginUserId Long sellerId, @Valid @RequestBody CouponCreateRequest request) {
        return RsData.created("쿠폰을 신청했습니다.", couponSellerFacade.create(sellerId, request.toCommand()));
    }

    @Override
    @PostMapping("/{couponId}/review")
    public RsData<CouponView> submitReview(@LoginUserId Long sellerId, @PathVariable("couponId") Long couponId) {
        return RsData.ok("쿠폰 심사를 요청했습니다.", couponSellerFacade.submitReview(sellerId, couponId));
    }

    @Override
    @PostMapping("/{couponId}/suspend")
    public RsData<CouponView> suspend(@LoginUserId Long sellerId, @PathVariable("couponId") Long couponId) {
        return RsData.ok("쿠폰 발급을 중단했습니다.", couponSellerFacade.suspend(sellerId, couponId));
    }
}

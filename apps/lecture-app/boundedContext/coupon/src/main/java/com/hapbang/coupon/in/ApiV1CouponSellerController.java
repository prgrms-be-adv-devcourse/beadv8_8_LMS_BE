package com.hapbang.coupon.in;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.coupon.app.CouponSellerFacade;
import com.hapbang.coupon.app.dto.CouponView;
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

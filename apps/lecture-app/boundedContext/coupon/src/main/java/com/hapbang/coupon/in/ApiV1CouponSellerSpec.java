package com.hapbang.coupon.in;

import com.hapbang.coupon.app.dto.CouponView;
import com.hapbang.coupon.global.response.RsData;
import com.hapbang.coupon.in.dto.CouponCreateRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 판매자 쿠폰 API Swagger 명세. 매핑은 {@link ApiV1CouponSellerController} */
@Tag(name = "판매자 쿠폰", description = "인증 인플루언서의 자기 강의 쿠폰 신청·심사 요청·중단")
public interface ApiV1CouponSellerSpec {

    @Operation(summary = "판매자 쿠폰 신청",
            description = "VERIFIED_INFLUENCER. 자기 강의(targetType LECTURE)만. 강의 가격을 조회해 snapshot하고 DRAFT로 만든다(201). "
                    + "강의가 없으면 404 LECTURE_NOT_FOUND, 자기 강의가 아니면 403 NOT_LECTURE_SELLER, "
                    + "발행 조건 오류는 400 INVALID_COUPON_POLICY, 강의 조회 실패는 503 LECTURE_CATALOG_UNAVAILABLE.")
    RsData<CouponView> create(Long sellerId, CouponCreateRequest request);

    @Operation(summary = "심사 요청",
            description = "VERIFIED_INFLUENCER, 신청한 판매자 본인. DRAFT → REVIEW. 관리자가 승인하면 발급이 열린다.")
    RsData<CouponView> submitReview(Long sellerId, Long couponId);

    @Operation(summary = "판매자 쿠폰 발급 중단",
            description = "VERIFIED_INFLUENCER, 신청한 판매자 본인. 새 발급 접수를 먼저 막고 SUSPENDED로 바꾼다. "
                    + "이미 중단된 쿠폰에 다시 보내면 접수 차단만 다시 확인한다. 발급 저장소 장애면 503이고 상태는 그대로다.")
    RsData<CouponView> suspend(Long sellerId, Long couponId);
}

package com.hapbang.coupon.in;

import java.util.List;

import com.hapbang.coupon.app.dto.CouponView;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.global.response.RsData;
import com.hapbang.coupon.in.dto.CouponCreateRequest;
import com.hapbang.coupon.in.dto.CouponRejectRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 관리자 쿠폰 API Swagger 명세. 매핑은 {@link ApiV1CouponAdminController} */
@Tag(name = "쿠폰 관리", description = "관리자의 판매자 쿠폰 심사와 플랫폼 쿠폰 발행·발급 운영·조회")
public interface ApiV1CouponAdminSpec {

    @Operation(summary = "쿠폰 목록", description = "관리자. 판매자·플랫폼 쿠폰 전체. 최신순, 최대 100건.")
    RsData<List<CouponView>> coupons(@Parameter(description = "쿠폰 상태(DRAFT·REVIEW·REJECTED·OPEN·SUSPENDED·CLOSED). 없으면 전체")
                                     CouponStatus status);

    @Operation(summary = "쿠폰 상세", description = "관리자. 없으면 404 COUPON_NOT_FOUND.")
    RsData<CouponView> coupon(Long couponId);

    @Operation(summary = "플랫폼 쿠폰 발행",
            description = "관리자. DRAFT로 만든다(201). 특정 강의 대상이면 강의 가격을 조회해 snapshot한다(강의가 없으면 404 LECTURE_NOT_FOUND, "
                    + "판매 중이 아니면 409 COUPON_LECTURE_NOT_ON_SALE). 발급은 열기(open)로 시작한다.")
    RsData<CouponView> createPlatform(Long adminId, CouponCreateRequest request);

    @Operation(summary = "플랫폼 쿠폰 열기",
            description = "관리자. DRAFT → OPEN. 프로모션 예산을 예약하고 발급 저장소를 준비한 뒤 연다. "
                    + "특정 강의 대상이면 강의가 판매 중이어야 한다(아니면 409 COUPON_LECTURE_NOT_ON_SALE). "
                    + "예산 거절은 409 PROMOTION_BUDGET_REJECTED(다시 보내면 새 예약으로 다시 시도한다), "
                    + "예산 결과를 모르면 503 PROMOTION_BUDGET_UNAVAILABLE, "
                    + "발급 저장소 준비 실패는 503 COUPON_ISSUE_STORE_UNAVAILABLE이며 쿠폰은 DRAFT로 남는다. 503이면 같은 요청을 다시 보낸다.")
    RsData<CouponView> open(Long adminId, Long couponId);

    @Operation(summary = "판매자 쿠폰 승인", description = "관리자. REVIEW → OPEN. 발급 저장소를 준비한 뒤 연다. "
            + "강의가 판매 중이 아니면 409 COUPON_LECTURE_NOT_ON_SALE.")
    RsData<CouponView> approve(Long adminId, Long couponId);

    @Operation(summary = "판매자 쿠폰 거절", description = "관리자. REVIEW → REJECTED. 사유 필수.")
    RsData<CouponView> reject(Long adminId, Long couponId, CouponRejectRequest request);

    @Operation(summary = "발급 중단",
            description = "관리자. 새 발급 접수를 먼저 막고 SUSPENDED로 바꾼다. 이미 중단된 쿠폰에 다시 보내면 접수 차단만 다시 확인한다. "
                    + "발급 저장소 장애면 503이고 상태는 그대로다.")
    RsData<CouponView> suspend(Long adminId, Long couponId);

    @Operation(summary = "발급 재개",
            description = "관리자. SUSPENDED → OPEN 뒤 발급 접수를 다시 연다. 접수 재개에 실패하면 503이며, 같은 요청을 다시 보내면 접수 재개만 다시 한다.")
    RsData<CouponView> resume(Long adminId, Long couponId);

    @Operation(summary = "발급 종료",
            description = "관리자. 새 발급 접수를 먼저 막고 CLOSED로 바꾼다. 종료한 쿠폰은 다시 열지 않는다.")
    RsData<CouponView> close(Long adminId, Long couponId);
}

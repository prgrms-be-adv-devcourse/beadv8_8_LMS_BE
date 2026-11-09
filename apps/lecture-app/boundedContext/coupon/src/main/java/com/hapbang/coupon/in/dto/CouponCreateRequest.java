package com.hapbang.coupon.in.dto;

import java.time.Instant;

import com.hapbang.coupon.app.dto.CouponCreateCommand;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.DiscountType;
import com.hapbang.coupon.domain.ExpiryType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 쿠폰 발행 요청(판매자 신청·관리자 플랫폼 발행 공통). 여기서는 값이 있는지만 본다. 값끼리의 규칙(수량·기간·할인 값·대상·만료 방식)은
 * 도메인이 검증해 400 {@code INVALID_COUPON_POLICY}로 응답한다. 강의 가격은 받지 않고 강의 조회로 snapshot한다.
 */
public record CouponCreateRequest(
        // 길이(앞뒤 공백을 뺀 1~50자, 코드 포인트 기준)는 도메인이 본다. 길이 애노테이션은 공백 제거 전 UTF-16 길이라 정상 이름을 거절한다
        @Schema(description = "쿠폰 이름 (앞뒤 공백을 뺀 1~50자). 앞뒤 공백은 빼고 저장한다", example = "겨울 특강 10% 할인")
        @NotBlank String name,
        @Schema(description = "적용 대상. 판매자 쿠폰은 LECTURE만")
        @NotNull CouponTargetType targetType,
        @Schema(description = "대상 강의 ID. LECTURE면 필수, ANY_LECTURE면 비운다", nullable = true)
        Long lectureId,
        @NotNull DiscountType discountType,
        @Schema(description = "RATE면 퍼센트 정수(1~100), AMOUNT면 HP")
        @NotNull Long discountValue,
        @Schema(description = "장당 최대 할인 HP. 모든 강의 대상 정률(ANY_LECTURE + RATE)이면 필수, 그 밖에는 비운다", nullable = true)
        Long maxDiscountAmount,
        @Schema(description = "발행 수량 (1 이상)")
        @NotNull Integer quantity,
        @NotNull Instant issueStartAt,
        @Schema(description = "발급 종료 시각. 시작보다 뒤")
        @NotNull Instant issueEndAt,
        @Schema(description = "RELATIVE: 최초 접수 시각 + expiryDays일, FIXED: fixedExpiresAt")
        @NotNull ExpiryType expiryType,
        @Schema(description = "RELATIVE면 필수(1 이상), FIXED면 비운다", nullable = true)
        Integer expiryDays,
        @Schema(description = "FIXED면 필수, RELATIVE면 비운다", nullable = true)
        Instant fixedExpiresAt,
        @Schema(description = "다른 쿠폰과 함께 쓸 수 있는가")
        @NotNull Boolean stackable
) {

    public CouponCreateCommand toCommand() {
        return new CouponCreateCommand(name, targetType, lectureId, discountType, discountValue, maxDiscountAmount, quantity,
                issueStartAt, issueEndAt, expiryType, expiryDays, fixedExpiresAt, stackable);
    }
}

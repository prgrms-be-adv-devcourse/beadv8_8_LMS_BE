package com.hapbang.coupon.in.dto;

import java.util.List;

import com.hapbang.coupon.domain.usage.Selection;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 계산 미리보기 요청. 형식이 틀리면 400 {@code INVALID_REQUEST}다. 항목 수 상한·같은 강의 중복은 쿠폰 모듈이 400으로 막는다.
 *
 * @param lectureIds 주문할 강의 ID(강의마다 한 번)
 * @param selections 고른 쿠폰 매핑. {@code null}(생략)이면 추천, 빈 목록이면 쿠폰을 쓰지 않음, 값이 있으면 그 매핑만 검증·계산
 */
public record CouponQuoteRequest(
        @Schema(description = "주문할 강의 ID 목록", example = "[3, 5]")
        @NotEmpty
        List<@NotNull Long> lectureIds,
        @Schema(description = "고른 쿠폰 매핑. 생략(null)하면 추천, 빈 목록이면 쿠폰 없이 계산한다", nullable = true)
        List<@NotNull @Valid SelectionRequest> selections
) {

    /** 도메인 매핑으로 바꾼다. 생략했으면 {@code null}(추천) */
    public List<Selection> toSelections() {
        return selections == null ? null : selections.stream()
                .map(selection -> new Selection(selection.memberCouponId(), selection.lectureId()))
                .toList();
    }

    /**
     * 발급 쿠폰 하나를 어느 강의에 쓸지 고른 매핑.
     *
     * @param memberCouponId 발급 쿠폰 ID
     * @param lectureId      쿠폰을 쓸 강의 ID. {@code lectureIds}에 있어야 한다
     */
    public record SelectionRequest(
            @NotNull Long memberCouponId,
            @NotNull Long lectureId
    ) {
    }
}

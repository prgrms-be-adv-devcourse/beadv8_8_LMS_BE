package com.hapbang.lecture.domain;

import java.util.List;
import java.util.Set;

import com.hapbang.shared.coupon.api.CouponReservationStatusResponse;

/**
 * 쿠폰 예약 상태 조회 결과를 관리자 조치로 판정한다. 판정은 사용 시도별 상태({@code items[].coupons[].status})로 한다.
 * 최상위 {@code status}는 결제 완료·취소된 사용 시도도 {@code RESERVED}로 주므로 보지 않는다(거절·기록 없음만 본다).
 * <ul>
 *     <li>{@link #CONFLICT}: 결제 완료({@code PAID})·취소({@code CANCELED})나 모르는 상태의 사용 시도가 있다. 이미 결제나 취소가
 *     쿠폰에 반영된 것이라 자동으로 고치지 않는다. 다른 판정보다 먼저 본다.</li>
 *     <li>{@link #RESERVED}: 예약된({@code RESERVED}) 사용 시도가 하나라도 있다. 해제해야 한다.</li>
 *     <li>{@link #REJECTED}: 쿠폰이 예약을 거절했다. 예약된 것이 없다.</li>
 *     <li>{@link #NOT_RESERVED}: 기록이 없거나({@code NOT_FOUND}) 모두 해제됐다. 쿠폰 쪽에 남은 예약이 없다.</li>
 * </ul>
 */
public enum CouponReservationOutcome {
    RESERVED,
    REJECTED,
    NOT_RESERVED,
    CONFLICT;

    static final String REJECTED_STATUS = "REJECTED";
    private static final String RESERVED_USAGE = "RESERVED";
    private static final Set<String> NOT_HELD_USAGES = Set.of("RELEASED");

    public static CouponReservationOutcome of(CouponReservationStatusResponse response) {
        List<String> usageStatuses = response.items().stream()
                .flatMap(item -> item.coupons().stream())
                .map(CouponReservationStatusResponse.Coupon::status)
                .toList();
        if (usageStatuses.stream().anyMatch(status -> !RESERVED_USAGE.equals(status)
                && !NOT_HELD_USAGES.contains(status))) {
            return CONFLICT;
        }
        if (usageStatuses.contains(RESERVED_USAGE)) {
            return RESERVED;
        }
        if (REJECTED_STATUS.equals(response.status())) {
            return REJECTED;
        }
        return NOT_RESERVED;
    }
}

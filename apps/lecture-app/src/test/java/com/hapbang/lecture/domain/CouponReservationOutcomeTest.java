package com.hapbang.lecture.domain;

import static org.assertj.core.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.hapbang.shared.coupon.api.CouponReservationStatusResponse;

// 쿠폰 예약 상태 조회 결과 → 관리자 조치 판정. 사용 시도별 상태로 판정하고 최상위 상태는 거절·기록 없음만 본다
class CouponReservationOutcomeTest {

    @ParameterizedTest(name = "최상위 {0}, 사용 시도 [{1}] → {2}")
    @CsvSource(delimiter = '|', value = {
            "NOT_FOUND |                   | NOT_RESERVED",
            "REJECTED  |                   | REJECTED",
            "RESERVED  | RESERVED          | RESERVED",
            "RESERVED  | RESERVED RELEASED | RESERVED",
            "RELEASED  | RELEASED RELEASED | NOT_RESERVED",
            // 최상위는 결제 완료·취소된 사용 시도도 RESERVED로 준다. 사용 시도별 상태로 판정한다
            "RESERVED  | PAID              | CONFLICT",
            "RESERVED  | RESERVED CANCELED | CONFLICT",
            "RESERVED  | RELEASED CANCELED | CONFLICT",
            "RESERVED  | SOMETHING_NEW     | CONFLICT"
    })
    void 사용_시도별_상태로_조치를_판정한다(String status, String usageStatuses, CouponReservationOutcome expected) {
        assertThat(CouponReservationOutcome.of(response(status, usageStatuses))).isEqualTo(expected);
    }

    private static CouponReservationStatusResponse response(String status, String usageStatuses) {
        List<CouponReservationStatusResponse.Coupon> coupons = usageStatuses == null ? List.of()
                : Arrays.stream(usageStatuses.trim().split(" "))
                .map(usage -> new CouponReservationStatusResponse.Coupon(501L, 11L, 5L, 1, 10_000L, 3_000L, 0L,
                        3_000L, 2, usage))
                .toList();
        List<CouponReservationStatusResponse.Item> items = coupons.isEmpty() ? List.of()
                : List.of(new CouponReservationStatusResponse.Item(31L, 3_000L, 0L, 3_000L, coupons));
        return new CouponReservationStatusResponse("lecture-order:1:reserve", status.trim(),
                "REJECTED".equals(status.trim()) ? "COUPON_NOT_USABLE" : null, items);
    }
}

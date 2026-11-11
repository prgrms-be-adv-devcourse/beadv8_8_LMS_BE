package com.hapbang.coupon.in;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.coupon.app.CouponInternalFacade;
import com.hapbang.coupon.global.response.RsData;
import com.hapbang.shared.coupon.api.CouponCalculateRequest;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;
import com.hapbang.shared.coupon.api.CouponPriceRaiseBlockedResponse;
import com.hapbang.shared.coupon.api.CouponReleaseRequest;
import com.hapbang.shared.coupon.api.CouponReleaseResponse;
import com.hapbang.shared.coupon.api.CouponReservationStatusResponse;
import com.hapbang.shared.coupon.api.CouponReserveRequest;
import com.hapbang.shared.coupon.api.CouponReserveResponse;

import lombok.RequiredArgsConstructor;

/**
 * 쿠폰 사용 내부 API(강의 → 쿠폰). 계산·예약·해제·예약 상태·가격 인상 제한. 요청 검증과 역할 검사는 {@link CouponInternalFacade}가 한다.
 * Swagger 명세는 {@link ApiV1CouponInternalSpec}
 */
// 내부 API 키 검사는 같은 JVM의 lecture-app 내부 API 보안 체인이 /api/v1/internal/** 전체에 한다
@RestController
@RequestMapping("/api/v1/internal/coupons")
@RequiredArgsConstructor
public class ApiV1CouponInternalController implements ApiV1CouponInternalSpec {

    private final CouponInternalFacade couponInternalFacade;

    @Override
    @PostMapping("/calculate")
    public RsData<CouponCalculateResponse> calculate(@RequestBody CouponCalculateRequest request) {
        return RsData.ok("쿠폰 할인을 계산했습니다.", couponInternalFacade.calculate(request));
    }

    @Override
    @PostMapping("/reservations")
    public RsData<CouponReserveResponse> reserve(@RequestBody CouponReserveRequest request) {
        return RsData.ok("쿠폰을 예약했습니다.", couponInternalFacade.reserve(request));
    }

    @Override
    @PostMapping("/releases")
    public RsData<CouponReleaseResponse> release(@RequestBody CouponReleaseRequest request) {
        return RsData.ok("쿠폰 예약을 해제했습니다.", couponInternalFacade.release(request));
    }

    @Override
    @GetMapping("/reservations/{workId}")
    public RsData<CouponReservationStatusResponse> reservationStatus(@PathVariable("workId") String workId,
                                                                     @RequestParam(name = "memberId") Long memberId,
                                                                     @RequestParam(name = "orderId") Long orderId) {
        return RsData.ok("쿠폰 예약 상태를 조회했습니다.",
                couponInternalFacade.reservationStatus(workId, memberId, orderId));
    }

    @Override
    @GetMapping("/price-raise-blocked")
    public RsData<CouponPriceRaiseBlockedResponse> priceRaiseBlocked(@RequestParam(name = "lectureId") Long lectureId) {
        return RsData.ok("가격 인상 제한을 조회했습니다.", couponInternalFacade.priceRaiseBlocked(lectureId));
    }
}

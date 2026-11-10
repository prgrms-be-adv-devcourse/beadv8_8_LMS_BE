package com.hapbang.lecture.out.coupon;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import com.hapbang.shared.coupon.api.CouponCalculateRequest;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;
import com.hapbang.shared.coupon.api.CouponPriceRaiseBlockedResponse;
import com.hapbang.shared.coupon.api.CouponReleaseRequest;
import com.hapbang.shared.coupon.api.CouponReleaseResponse;
import com.hapbang.shared.coupon.api.CouponReservationStatusResponse;
import com.hapbang.shared.coupon.api.CouponReserveRequest;
import com.hapbang.shared.coupon.api.CouponReserveResponse;

/**
 * 쿠폰 사용 내부 HTTP API(docs/api/internal-coupon.md)의 강의 전용 HTTP Interface. 프록시는 {@link LectureCouponApiConfig}가 만든다
 * (주소·내부 API 키 헤더·오류 상태 변환). 응답은 쿠폰의 성공 봉투(RsData)다. 직접 쓰지 않고 {@link CouponApiClient}로 부른다.
 */
@HttpExchange(LectureCouponInternalApi.COUPONS_PATH)
public interface LectureCouponInternalApi {

    String COUPONS_PATH = "/api/v1/internal/coupons";

    /** 할인 계산(읽기만) */
    @PostExchange("/calculate")
    ResponseEntity<Envelope<CouponCalculateResponse>> calculate(@RequestBody CouponCalculateRequest request);

    /** 쿠폰 예약. 같은 workId·같은 내용이면 처음 결과를 다시 준다 */
    @PostExchange("/reservations")
    ResponseEntity<Envelope<CouponReserveResponse>> reserve(@RequestBody CouponReserveRequest request);

    /** 쿠폰 예약 해제. 같은 workId·같은 내용이면 처음 결과를 다시 준다 */
    @PostExchange("/releases")
    ResponseEntity<Envelope<CouponReleaseResponse>> release(@RequestBody CouponReleaseRequest request);

    /** 예약 작업 결과(읽기만). 예약 결과를 모를 때 쓴다 */
    @GetExchange("/reservations/{workId}")
    ResponseEntity<Envelope<CouponReservationStatusResponse>> reservationStatus(
            @PathVariable("workId") String workId, @RequestParam("memberId") long memberId,
            @RequestParam("orderId") long orderId);

    /** 강의 가격 인상 제한(읽기만) */
    @GetExchange("/price-raise-blocked")
    ResponseEntity<Envelope<CouponPriceRaiseBlockedResponse>> priceRaiseBlocked(
            @RequestParam("lectureId") long lectureId);

    /** 쿠폰 성공 응답(RsData)의 모양 */
    record Envelope<T>(Integer statusCode, String message, T data) {
    }
}

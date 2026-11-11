package com.hapbang.lecture.out.coupon;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.function.Supplier;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import com.hapbang.lecture.out.coupon.LectureCouponApiConfig.CouponNotProcessedResponse;
import com.hapbang.lecture.out.coupon.LectureCouponApiConfig.CouponRejectedResponse;
import com.hapbang.lecture.out.coupon.LectureCouponApiConfig.CouponUnknownResponse;
import com.hapbang.lecture.out.coupon.LectureCouponInternalApi.Envelope;
import com.hapbang.shared.coupon.api.CouponCalculateRequest;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;
import com.hapbang.shared.coupon.api.CouponPriceRaiseBlockedResponse;
import com.hapbang.shared.coupon.api.CouponReleaseRequest;
import com.hapbang.shared.coupon.api.CouponReleaseResponse;
import com.hapbang.shared.coupon.api.CouponReservationStatusResponse;
import com.hapbang.shared.coupon.api.CouponReserveRequest;
import com.hapbang.shared.coupon.api.CouponReserveResponse;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

/**
 * 쿠폰 사용 내부 API를 부르는 어댑터. 강의 전용 HTTP Interface({@link LectureCouponInternalApi})로 부르고, 결과를
 * {@link CouponCallResult} 네 가지(성공·거절·보내지 않음·결과 불명)로 나눈다. 예외를 던지지 않는다.
 * <ul>
 *     <li>호출한 쪽 트랜잭션 밖에서 부른다. 여기서 다시 보내지 않는다. 예약·해제의 재전송은 같은 workId로 호출한 쪽이 한다.</li>
 *     <li>연결을 맺기 전에 실패했을 때(연결 거부, 주소 없음, 경로 없음)만 보내지 않음이다. 연결 시간 초과는 응답 시간 초과와 같은 예외라
 *     구분하지 않고 결과 불명으로 둔다(지갑 호출과 같은 기준).</li>
 *     <li>성공 상태인데 본문이 없거나 {@code data}가 없거나 읽을 수 없으면 결과 불명이다.</li>
 * </ul>
 * 호출은 circuit breaker({@value #CIRCUIT_NAME}, 설정 {@link LectureCouponClientProperties}) 안에서 한다.
 * 보내지 않음·결과 불명만 실패로 세고, 거절(4xx)은 쿠폰이 판단한 응답이라 성공으로 센다. 회로가 열려 있으면 HTTP를 부르지 않고
 * 보내지 않음이다. 열린 회로는 시간이 지나면 스스로 HALF_OPEN이 된다.
 */
@Component
public class CouponApiClient {

    static final String CIRCUIT_NAME = "coupon";

    private final LectureCouponInternalApi couponInternalApi;
    private final CircuitBreaker circuitBreaker;

    public CouponApiClient(LectureCouponInternalApi couponInternalApi, LectureCouponClientProperties properties) {
        this.couponInternalApi = couponInternalApi;
        this.circuitBreaker = CircuitBreaker.of(CIRCUIT_NAME, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .slowCallDurationThreshold(properties.slowCallDuration())
                .slowCallRateThreshold(properties.slowCallRateThreshold())
                .waitDurationInOpenState(properties.openDuration())
                .permittedNumberOfCallsInHalfOpenState(properties.halfOpenCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(exception -> exception instanceof CallFailure)
                .build());
    }

    /** 할인 계산(읽기만) */
    public CouponCallResult<CouponCalculateResponse> calculate(CouponCalculateRequest request) {
        return call(() -> couponInternalApi.calculate(request));
    }

    /** 쿠폰 예약. 결과를 모르면 같은 workId·같은 내용으로 다시 보내거나 {@link #reservationStatus}로 확인한다 */
    public CouponCallResult<CouponReserveResponse> reserve(CouponReserveRequest request) {
        return call(() -> couponInternalApi.reserve(request));
    }

    /** 쿠폰 예약 해제. 결과를 모르면 같은 workId·같은 내용으로 다시 보낸다 */
    public CouponCallResult<CouponReleaseResponse> release(CouponReleaseRequest request) {
        return call(() -> couponInternalApi.release(request));
    }

    /** 예약 작업 결과(읽기만) */
    public CouponCallResult<CouponReservationStatusResponse> reservationStatus(String workId, long memberId,
                                                                              long orderId) {
        return call(() -> couponInternalApi.reservationStatus(workId, memberId, orderId));
    }

    /** 강의 가격 인상 제한(읽기만) */
    public CouponCallResult<CouponPriceRaiseBlockedResponse> priceRaiseBlocked(long lectureId) {
        return call(() -> couponInternalApi.priceRaiseBlocked(lectureId));
    }

    private <T> CouponCallResult<T> call(Supplier<ResponseEntity<Envelope<T>>> request) {
        try {
            return circuitBreaker.executeSupplier(() -> send(request));
        } catch (CallNotPermittedException open) {
            return new CouponCallResult.NotSent<>("쿠폰 회로가 열려 있어 쿠폰 내부 API를 부르지 않았습니다.");
        } catch (CallFailure failure) {
            return failure.result();
        }
    }

    private static <T> CouponCallResult<T> send(Supplier<ResponseEntity<Envelope<T>>> request) {
        ResponseEntity<Envelope<T>> response;
        try {
            response = request.get();
        } catch (CouponRejectedResponse rejected) {
            return rejected.toResult();
        } catch (CouponNotProcessedResponse notProcessed) {
            throw new CallFailure(new CouponCallResult.NotSent<>(notProcessed.getMessage()));
        } catch (CouponUnknownResponse unknown) {
            throw new CallFailure(new CouponCallResult.Unknown<>(unknown.status(), unknown.getMessage()));
        } catch (RestClientException e) {
            // 오류 상태는 프록시가 먼저 바꾸므로 여기 오는 것은 연결 실패·시간 초과나 성공 상태의 읽을 수 없는 본문이다
            throw new CallFailure(notSent(e)
                    ? new CouponCallResult.NotSent<>("쿠폰 내부 API에 연결하지 못했습니다: " + e.getMessage())
                    : new CouponCallResult.Unknown<>(0, "쿠폰 내부 API의 응답을 받지 못했거나 읽지 못했습니다: "
                            + e.getMessage()));
        }
        Envelope<T> body = response.getBody();
        if (body == null || body.data() == null) {
            throw new CallFailure(new CouponCallResult.Unknown<>(response.getStatusCode().value(),
                    "쿠폰 내부 API 응답에 data가 없습니다."));
        }
        return new CouponCallResult.Succeeded<>(body.data());
    }

    /** 연결을 맺기 전에 실패해 요청이 쿠폰에 닿지 않은 것이 확실한지 */
    private static boolean notSent(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException) {
                return true;
            }
        }
        return false;
    }

    /** 회로가 열려 있지 않아 보낼 수 있는지. 재전송 배치가 회로가 열린 동안 작업의 시도 횟수를 쓰지 않게 먼저 본다 */
    public boolean canCall() {
        CircuitBreaker.State state = circuitBreaker.getState();
        return state != CircuitBreaker.State.OPEN && state != CircuitBreaker.State.FORCED_OPEN;
    }

    /** 테스트에서 회로 상태를 확인할 때 쓴다 */
    CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    /** 회로 실패로 셀 결과(보내지 않음·결과 불명)를 circuit breaker 밖으로 옮긴다 */
    private static final class CallFailure extends RuntimeException {

        private final CouponCallResult<?> result;

        CallFailure(CouponCallResult<?> result) {
            super(null, null, false, false);
            this.result = result;
        }

        @SuppressWarnings("unchecked")
        <T> CouponCallResult<T> result() {
            return (CouponCallResult<T>) result;
        }
    }
}

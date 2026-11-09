package com.hapbang.lecture.out.coupon;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 강의가 쿠폰 사용 내부 API를 부르는 호출의 제한 시간과 circuit breaker 설정 ({@code lecture.coupon-client.*}).
 * 값을 주지 않으면 기본값이다. 기본값은 측정·합의 전 값으로, 쿠폰의 강의 조회 호출과 지갑·회원의 나가는 호출 회로와 같다.
 * 배포 뒤 지표를 보고 다시 맞춘다.
 *
 * @param connectTimeout        연결 제한 시간. 기본 2초
 * @param readTimeout           응답 제한 시간. 기본 5초
 * @param slidingWindowSize     최근 몇 번의 호출로 실패율을 계산할지. 기본 20
 * @param minimumNumberOfCalls  계산을 시작할 최소 호출 수. 기본 10
 * @param failureRateThreshold  회로를 여는 실패율(%). 기본 50
 * @param slowCallDuration      느린 호출로 보는 시간. 기본 4초
 * @param slowCallRateThreshold 회로를 여는 느린 호출 비율(%). 기본 50
 * @param openDuration          열린 뒤 HALF_OPEN으로 가기까지. 기본 30초
 * @param halfOpenCalls         HALF_OPEN에서 시험 삼아 보낼 호출 수. 기본 3
 */
@ConfigurationProperties("lecture.coupon-client")
public record LectureCouponClientProperties(
        Duration connectTimeout,
        Duration readTimeout,
        Integer slidingWindowSize,
        Integer minimumNumberOfCalls,
        Float failureRateThreshold,
        Duration slowCallDuration,
        Float slowCallRateThreshold,
        Duration openDuration,
        Integer halfOpenCalls
) {

    public LectureCouponClientProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
        slidingWindowSize = slidingWindowSize == null ? 20 : slidingWindowSize;
        minimumNumberOfCalls = minimumNumberOfCalls == null ? 10 : minimumNumberOfCalls;
        failureRateThreshold = failureRateThreshold == null ? 50f : failureRateThreshold;
        slowCallDuration = slowCallDuration == null ? Duration.ofSeconds(4) : slowCallDuration;
        slowCallRateThreshold = slowCallRateThreshold == null ? 50f : slowCallRateThreshold;
        openDuration = openDuration == null ? Duration.ofSeconds(30) : openDuration;
        halfOpenCalls = halfOpenCalls == null ? 3 : halfOpenCalls;
    }

    /** 설정 없이 쓰는 기본값 (테스트에서 직접 만들 때) */
    public static LectureCouponClientProperties defaults() {
        return new LectureCouponClientProperties(null, null, null, null, null, null, null, null, null);
    }
}

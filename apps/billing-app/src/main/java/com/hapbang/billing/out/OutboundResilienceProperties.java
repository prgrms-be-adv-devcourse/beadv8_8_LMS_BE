package com.hapbang.billing.out;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

// 지갑이 다른 서비스를 부르는 호출의 회복성 설정 (#562, BD-134). 회로 하나의 값을 상대(강의·출연·회원·TossPayments)마다 따로 쓴다.
// 기본값은 shared BillingClientProperties(강의·출연이 지갑을 부를 때, D64·D66)와 같다. 배포 뒤 지표를 보고 다시 맞춘다.
// - slidingWindowSize·minimumNumberOfCalls: 최근 20번 중 10번 이상 호출했을 때 실패율을 본다
// - failureRateThreshold·slowCallRateThreshold: 실패·느린 호출 비율(%)이 넘으면 연다. slowCallDuration보다 오래 걸리면 느린 호출이다
// - openDuration: 열린 뒤 HALF_OPEN으로 가기까지. halfOpenCalls: HALF_OPEN에서 시험 삼아 보낼 호출 수
// - earningRetryWait: 수익 조회에서 연결 자체가 안 됐을 때 한 번 더 보내기 전 대기
@ConfigurationProperties("hapbang.billing.outbound")
public record OutboundResilienceProperties(
        Integer slidingWindowSize,
        Integer minimumNumberOfCalls,
        Float failureRateThreshold,
        Duration slowCallDuration,
        Float slowCallRateThreshold,
        Duration openDuration,
        Integer halfOpenCalls,
        Duration earningRetryWait
) {

    @ConstructorBinding
    public OutboundResilienceProperties {
        slidingWindowSize = slidingWindowSize == null ? 20 : slidingWindowSize;
        minimumNumberOfCalls = minimumNumberOfCalls == null ? 10 : minimumNumberOfCalls;
        failureRateThreshold = failureRateThreshold == null ? 50f : failureRateThreshold;
        slowCallDuration = slowCallDuration == null ? Duration.ofSeconds(4) : slowCallDuration;
        slowCallRateThreshold = slowCallRateThreshold == null ? 50f : slowCallRateThreshold;
        openDuration = openDuration == null ? Duration.ofSeconds(30) : openDuration;
        halfOpenCalls = halfOpenCalls == null ? 3 : halfOpenCalls;
        earningRetryWait = earningRetryWait == null ? Duration.ofMillis(200) : earningRetryWait;
    }

    /** 설정 없이 쓰는 기본값 (테스트에서 Client를 직접 만들 때) */
    public static OutboundResilienceProperties defaults() {
        return new OutboundResilienceProperties(null, null, null, null, null, null, null, null);
    }
}

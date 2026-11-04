package com.hapbang.member.out;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 회원이 다른 앱(출연·강의) 내부 API를 부르는 호출의 circuit breaker 설정 (#654). 회로 하나의 값을 상대마다 따로 쓴다.
 * 기본값은 지갑 OutboundResilienceProperties(#562)와 같다. 배포 뒤 지표를 보고 다시 맞춘다.
 * <ul>
 *     <li>slidingWindowSize·minimumNumberOfCalls: 최근 20번 중 10번 이상 호출했을 때 실패율을 본다</li>
 *     <li>failureRateThreshold·slowCallRateThreshold: 실패·느린 호출 비율(%)이 넘으면 연다. slowCallDuration보다 오래 걸리면 느린 호출이다</li>
 *     <li>openDuration: 열린 뒤 HALF_OPEN으로 가기까지. halfOpenCalls: HALF_OPEN에서 시험 삼아 보낼 호출 수</li>
 * </ul>
 */
@ConfigurationProperties("hapbang.member.outbound")
public record MemberOutboundResilienceProperties(
        Integer slidingWindowSize,
        Integer minimumNumberOfCalls,
        Float failureRateThreshold,
        Duration slowCallDuration,
        Float slowCallRateThreshold,
        Duration openDuration,
        Integer halfOpenCalls
) {

    @ConstructorBinding
    public MemberOutboundResilienceProperties {
        slidingWindowSize = slidingWindowSize == null ? 20 : slidingWindowSize;
        minimumNumberOfCalls = minimumNumberOfCalls == null ? 10 : minimumNumberOfCalls;
        failureRateThreshold = failureRateThreshold == null ? 50f : failureRateThreshold;
        slowCallDuration = slowCallDuration == null ? Duration.ofSeconds(4) : slowCallDuration;
        slowCallRateThreshold = slowCallRateThreshold == null ? 50f : slowCallRateThreshold;
        openDuration = openDuration == null ? Duration.ofSeconds(30) : openDuration;
        halfOpenCalls = halfOpenCalls == null ? 3 : halfOpenCalls;
    }

    /** 설정 없이 쓰는 기본값 (테스트에서 직접 만들 때) */
    public static MemberOutboundResilienceProperties defaults() {
        return new MemberOutboundResilienceProperties(null, null, null, null, null, null, null);
    }
}

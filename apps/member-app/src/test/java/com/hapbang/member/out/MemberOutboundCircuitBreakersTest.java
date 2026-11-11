package com.hapbang.member.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.shared.appearance.out.AppearanceApiCallException;
import com.hapbang.shared.lecture.out.LectureApiCallException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * 회원의 다른 앱 호출 circuit breaker (#654). 장애(UNAVAILABLE)만 실패로 세고, 최근 10번 이상 중 절반 넘게 실패하면 열려
 * 상대를 부르지 않고 바로 실패한다. 거절(REJECTED, 4xx)은 상대가 정상으로 판단한 것이라 실패로 세지 않는다
 */
class MemberOutboundCircuitBreakersTest {

    private final MemberOutboundCircuitBreakers circuitBreakers = MemberOutboundCircuitBreakers.defaults();

    @Test
    @DisplayName("출연 장애가 10번 이어지면 회로가 열리고, 그다음 호출은 출연을 부르지 않고 바로 실패한다")
    void opensOnUnavailable() {
        AppearanceApiCallException unavailable = appearanceFailure(AppearanceApiCallException.Reason.UNAVAILABLE);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> circuitBreakers.call(MemberOutboundCircuitBreakers.Target.APPEARANCE, () -> {
                throw unavailable;
            })).isSameAs(unavailable);
        }

        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> circuitBreakers.call(MemberOutboundCircuitBreakers.Target.APPEARANCE,
                calls::incrementAndGet)).isInstanceOf(CallNotPermittedException.class);
        assertThat(calls).hasValue(0);
        assertThat(state(MemberOutboundCircuitBreakers.Target.APPEARANCE)).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("거절(4xx)은 아무리 이어져도 회로를 열지 않는다")
    void rejectedDoesNotOpen() {
        LectureApiCallException rejected = lectureFailure(LectureApiCallException.Reason.REJECTED);
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> circuitBreakers.call(MemberOutboundCircuitBreakers.Target.LECTURE, () -> {
                throw rejected;
            })).isSameAs(rejected);
        }

        assertThat(state(MemberOutboundCircuitBreakers.Target.LECTURE)).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("회로는 상대 앱마다 따로다: 출연 회로가 열려도 강의는 그대로 부른다")
    void separatePerTarget() {
        circuitBreakers.circuitBreaker(MemberOutboundCircuitBreakers.Target.APPEARANCE).transitionToOpenState();

        assertThat(circuitBreakers.call(MemberOutboundCircuitBreakers.Target.LECTURE, () -> "ok")).isEqualTo("ok");
    }

    private CircuitBreaker.State state(MemberOutboundCircuitBreakers.Target target) {
        return circuitBreakers.circuitBreaker(target).getState();
    }

    private static AppearanceApiCallException appearanceFailure(AppearanceApiCallException.Reason reason) {
        AppearanceApiCallException exception = mock(AppearanceApiCallException.class);
        given(exception.getReason()).willReturn(reason);
        return exception;
    }

    private static LectureApiCallException lectureFailure(LectureApiCallException.Reason reason) {
        LectureApiCallException exception = mock(LectureApiCallException.class);
        given(exception.getReason()).willReturn(reason);
        return exception;
    }
}

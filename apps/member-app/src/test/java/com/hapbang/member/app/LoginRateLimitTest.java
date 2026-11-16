package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.member.global.ratelimit.RateLimitExceededException;
import com.hapbang.member.out.LoginRateLimitStore;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class LoginRateLimitTest {

    private LoginRateLimitStore store;
    private SimpleMeterRegistry registry;
    private AtomicLong now;
    private LoginRateLimit rateLimit;

    @BeforeEach
    void setUp() {
        store = mock(LoginRateLimitStore.class);
        registry = new SimpleMeterRegistry();
        now = new AtomicLong(1_000);
        rateLimit = new LoginRateLimit(store, registry, now::get);
    }

    @Test
    @DisplayName("공인 IP는 세는 단위(IPv6는 /64)로 저장소에 세고, 한도 안이면 통과한다")
    void allowed() {
        rateLimit.acquire("2001:db8:1:2::1");

        verify(store).tryAcquire("v6/64:2001:0db8:0001:0002");
        assertThat(count(LoginRateLimit.REJECTED_METRIC)).isZero();
    }

    @Test
    @DisplayName("한도를 넘으면 남은 시간을 초로 올림해 429 예외를 던지고 거절 수를 센다")
    void rejected() {
        given(store.tryAcquire("203.0.113.10")).willReturn(1_500L);

        assertThatThrownBy(() -> rateLimit.acquire("203.0.113.10"))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(2));
        assertThat(count(LoginRateLimit.REJECTED_METRIC)).isEqualTo(1);
    }

    @Test
    @DisplayName("1초보다 적게 남아도 Retry-After는 1초다")
    void retryAfterAtLeastOneSecond() {
        given(store.tryAcquire("203.0.113.10")).willReturn(1L);

        assertThatThrownBy(() -> rateLimit.acquire("203.0.113.10"))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(1));
    }

    @Test
    @DisplayName("공인 주소가 아니면 세지 않고 통과시키고, 건너뛴 수를 센다")
    void skipNonPublic() {
        rateLimit.acquire("10.42.0.15");
        rateLimit.acquire("127.0.0.1");

        verify(store, never()).tryAcquire(anyString());
        assertThat(count(LoginRateLimit.SKIPPED_METRIC)).isEqualTo(2);
    }

    @Test
    @DisplayName("WARN 간격: 처음 한 번 그 건수를, 간격 안에서는 0을, 간격이 지나면 그 사이 건수를 돌려준다")
    void intervalWarn() {
        LoginRateLimit.IntervalWarn warn = new LoginRateLimit.IntervalWarn(Duration.ofMinutes(10).toNanos(), now::get);

        assertThat(warn.countAndCheck()).isEqualTo(1);
        assertThat(warn.countAndCheck()).isZero();
        assertThat(warn.countAndCheck()).isZero();
        now.addAndGet(Duration.ofMinutes(10).toNanos());
        assertThat(warn.countAndCheck()).isEqualTo(3);
        assertThat(warn.countAndCheck()).isZero();
    }

    private double count(String metric) {
        return registry.get(metric).counter().count();
    }
}

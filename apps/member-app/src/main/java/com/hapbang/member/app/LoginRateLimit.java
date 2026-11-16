package com.hapbang.member.app;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hapbang.member.domain.ClientIpKey;
import com.hapbang.member.global.ratelimit.RateLimitExceededException;
import com.hapbang.member.out.LoginRateLimitStore;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 비밀번호 로그인 IP 한도 (#506). 한 IP(IPv6는 /64)가 1분에 보낼 수 있는 로그인 요청 수를 묶는다(기본 10번, 성공·실패 모두 센다).
 * 넘으면 429 {@code TOO_MANY_REQUESTS}와 {@code Retry-After}다. 비밀번호를 비교(bcrypt)하기 전에 세서 대입 비용을 줄인다.
 * <ul>
 *     <li>IP는 {@code request.getRemoteAddr()}만 쓴다({@link ClientIpKey}).</li>
 *     <li>공인 주소가 아니면 IP 한도를 건너뛴다. Envoy 앞 경로가 깨져 모두가 같은 내부 주소로 보이는 신호라, 그때 한 사람이 모든 회원의 로그인을
 *     막지 않게 한다. 그동안은 로그인 잠금이 대입을 막는다.</li>
 *     <li>거절은 요청마다 로그를 남기지 않는다({@code docs/dev/logging.md} 429). 처음 한 번과 그 뒤 {@value #WARN_INTERVAL_MINUTES}분마다
 *     그 사이 건수를 WARN 한 줄로 남긴다. IP는 남기지 않는다(남용한 IP는 같은 시각 Envoy access log의 429로 찾는다).</li>
 * </ul>
 * 지표
 * <ul>
 *     <li>{@code hapbang.member.login.rate.limit.rejected}: IP 한도로 429를 준 수</li>
 *     <li>{@code hapbang.member.login.rate.limit.skipped}: 공인 주소가 아니라 IP 한도를 건너뛴 수. 0이 아니면 Envoy 앞 경로를 확인한다</li>
 * </ul>
 */
@Slf4j
@Component
public class LoginRateLimit {

    static final String REJECTED_METRIC = "hapbang.member.login.rate.limit.rejected";
    static final String SKIPPED_METRIC = "hapbang.member.login.rate.limit.skipped";
    static final int WARN_INTERVAL_MINUTES = 10;

    private final LoginRateLimitStore store;
    private final Counter rejected;
    private final Counter skipped;
    private final IntervalWarn rejectedWarn;
    private final IntervalWarn skippedWarn;

    @Autowired
    public LoginRateLimit(LoginRateLimitStore store, MeterRegistry registry) {
        this(store, registry, System::nanoTime);
    }

    /** 테스트가 WARN 간격의 시각을 바꿀 때 쓴다 */
    LoginRateLimit(LoginRateLimitStore store, MeterRegistry registry, LongSupplier nanoTime) {
        this.store = store;
        this.rejected = Counter.builder(REJECTED_METRIC).description("로그인 IP 한도로 429를 준 수").register(registry);
        this.skipped = Counter.builder(SKIPPED_METRIC).description("client IP가 공인 주소가 아니라 로그인 IP 한도를 건너뛴 수")
                .register(registry);
        long interval = Duration.ofMinutes(WARN_INTERVAL_MINUTES).toNanos();
        this.rejectedWarn = new IntervalWarn(interval, nanoTime);
        this.skippedWarn = new IntervalWarn(interval, nanoTime);
    }

    /**
     * 로그인 요청 하나를 센다.
     *
     * @param remoteAddr {@code request.getRemoteAddr()}
     * @throws RateLimitExceededException 이 IP가 한도를 넘었을 때
     */
    public void acquire(String remoteAddr) {
        Optional<ClientIpKey> ipKey = ClientIpKey.from(remoteAddr);
        if (ipKey.isEmpty()) {
            skipped.increment();
            long count = skippedWarn.countAndCheck();
            if (count > 0) {
                log.warn("로그인 client IP가 공인 주소가 아니라 IP 한도를 건너뛰었다. Envoy가 진짜 접속 IP를 넘기는지 확인한다 ip={}, skipped={}",
                        remoteAddr, count);
            }
            return;
        }
        long retryAfterMillis = store.tryAcquire(ipKey.get().value());
        if (retryAfterMillis <= 0) {
            return;
        }
        rejected.increment();
        long count = rejectedWarn.countAndCheck();
        if (count > 0) {
            log.warn("로그인 IP 한도로 요청을 거절했다 rejected={}", count);
        }
        throw new RateLimitExceededException(ceilSeconds(retryAfterMillis));
    }

    private static long ceilSeconds(long millis) {
        return (millis + 999) / 1000;
    }

    /** 건수를 세고, 처음 한 번과 그 뒤 간격마다 한 번만 "그 사이 건수"를 돌려준다(그 밖에는 0) */
    static final class IntervalWarn {

        private final long intervalNanos;
        private final LongSupplier nanoTime;
        private final AtomicLong sinceWarn = new AtomicLong();
        /** 다음 WARN을 남길 수 있는 시각. 0이면 아직 남긴 적 없음 */
        private final AtomicLong nextWarnAt = new AtomicLong();

        IntervalWarn(long intervalNanos, LongSupplier nanoTime) {
            this.intervalNanos = intervalNanos;
            this.nanoTime = nanoTime;
        }

        long countAndCheck() {
            long count = sinceWarn.incrementAndGet();
            long now = nanoTime.getAsLong();
            long next = nextWarnAt.get();
            if ((next == 0 || now - next >= 0) && nextWarnAt.compareAndSet(next, now + intervalNanos)) {
                sinceWarn.addAndGet(-count);
                return count;
            }
            return 0;
        }
    }
}

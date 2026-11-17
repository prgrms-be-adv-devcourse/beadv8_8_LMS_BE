package com.hapbang.auxiliary.search.common.app;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 실패가 이어져도 WARN을 요청마다 남기지 않게 간격을 둔다(#977). 처음 실패에 한 번, 그 뒤 간격이 지난 실패에 한 번씩 남기고, 그 사이 실패 건수를
 * 함께 준다. 여러 스레드가 함께 불러도 한 간격에 한 번만 남긴다.
 */
final class WarnThrottle {

    private final long intervalNanos;
    private final LongSupplier nanoClock;
    private final AtomicLong nextAt;
    private final AtomicLong failures = new AtomicLong();

    /**
     * @param nanoClock {@link System#nanoTime} 같은 나노초 시계(테스트에서 바꿔 넣는다)
     */
    WarnThrottle(Duration interval, LongSupplier nanoClock) {
        this.intervalNanos = interval.toNanos();
        this.nanoClock = nanoClock;
        this.nextAt = new AtomicLong(nanoClock.getAsLong());
    }

    /**
     * 실패 하나를 센다.
     *
     * @return WARN을 남길 차례면 지난 WARN 뒤 실패 건수(이번 포함), 아니면 0
     */
    long fail() {
        long count = failures.incrementAndGet();
        long now = nanoClock.getAsLong();
        long next = nextAt.get();
        if (now - next >= 0 && nextAt.compareAndSet(next, now + intervalNanos)) {
            failures.addAndGet(-count);
            return count;
        }
        return 0;
    }
}

package com.hapbang.billing.global.scheduling;

import java.sql.SQLException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 배치·Backfill을 pod 사이에서 한 번에 한 곳만 실행하게 하는 락 (#598, BD-138).
 * <p>
 * 배포(rolling update) 중에는 새 pod와 옛 pod가 잠깐 함께 떠서 같은 cron 시각에 같은 배치가 겹칠 수 있다. 작업을 시작할 때
 * PostgreSQL advisory lock을 기다리지 않고 잡고, 잡지 못하면 그 회차를 건너뛴다.
 * <ul>
 *     <li>모든 락을 락 전용 연결 하나({@link AdvisoryLockSession})로 잡는다. 스케줄러 스레드가 여러 개라 메서드를 직렬화한다.</li>
 *     <li>advisory lock은 같은 session이면 다시 잡혀도 성공한다(재진입). 그래서 이 pod가 쥔 작업을 메모리에 두고,
 *     이미 쥐고 있으면 DB에 묻지 않고 실패로 돌려준다(같은 pod 안의 중복).</li>
 *     <li>락 연결이 끊기면 그 연결의 락은 풀리고, 돌던 작업은 끝까지 둔다. 건마다 잡는 락과 멱등성이 마지막 방어선이다.</li>
 *     <li>두 pod가 동시에 실행하는 것만 막는다. 한쪽이 끝내고 푼 뒤 다른 쪽이 늦게 시작하면 같은 회차가 한 번 더 돈다.</li>
 * </ul>
 * 건너뛴 횟수는 {@code hapbang.billing.job.lock.skipped{job, reason=held|error}}로 낸다.
 */
@Slf4j
@Component
public class JobLock implements MeterBinder {

    static final String SKIPPED_METRIC = "hapbang.billing.job.lock.skipped";

    /** 락을 잡지 못한 이유 */
    enum SkipReason {
        /** 다른 pod 또는 이 pod의 다른 실행이 쥐고 있다 */
        HELD,
        /** 락을 시도하지 못했다(DB 연결 불가, SQL 오류) */
        ERROR
    }

    private final AdvisoryLockSession session;
    private final Set<BillingJob> held = EnumSet.noneOf(BillingJob.class);
    private final Map<BillingJob, Map<SkipReason, LongAdder>> skipped = new EnumMap<>(BillingJob.class);

    @Autowired
    JobLock(JdbcConnectionDetails connectionDetails) {
        this(PostgresAdvisoryLockSession.of(connectionDetails));
    }

    public JobLock(AdvisoryLockSession session) {
        this.session = session;
        for (BillingJob job : BillingJob.values()) {
            Map<SkipReason, LongAdder> counters = new EnumMap<>(SkipReason.class);
            for (SkipReason reason : SkipReason.values()) {
                counters.put(reason, new LongAdder());
            }
            skipped.put(job, counters);
        }
    }

    /**
     * 작업의 락을 잡는다. 다른 실행이 쥐고 있으면 빈 값이다.
     *
     * @throws JobLockException 락을 시도하지 못했을 때(DB 연결 불가, SQL 오류). 작업을 락 없이 실행하지 않는다
     */
    public synchronized Optional<Lease> tryAcquire(BillingJob job) {
        if (held.contains(job)) {
            return skip(job);
        }
        boolean locked;
        try {
            locked = session.tryLock(job.lockKey());
        } catch (SQLException | RuntimeException e) {
            skipped.get(job).get(SkipReason.ERROR).increment();
            log.warn("배치 락을 시도하지 못해 이번 실행을 건너뜁니다 job={}", job.tag(), e);
            throw new JobLockException(job, e);
        }
        if (!locked) {
            return skip(job);
        }
        held.add(job);
        return Optional.of(new Lease(job));
    }

    /** 배치용. 락을 잡으면 작업을 실행하고 풀며, 잡지 못하면(다른 실행이 쥠·락 오류) 실행하지 않고 돌아온다 */
    public void runOrSkip(BillingJob job, Runnable task) {
        Optional<Lease> lease;
        try {
            lease = tryAcquire(job);
        } catch (JobLockException e) {
            return;
        }
        if (lease.isEmpty()) {
            return;
        }
        try (Lease ignored = lease.get()) {
            task.run();
        }
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        skipped.forEach((job, counters) -> counters.forEach((reason, counter) ->
                FunctionCounter.builder(SKIPPED_METRIC, counter, LongAdder::sum)
                        .description("락을 잡지 못해 건너뛰거나 거절한 지갑 작업 실행 수")
                        .tag("job", job.tag())
                        .tag("reason", reason.name().toLowerCase())
                        .register(registry)));
    }

    @PreDestroy
    synchronized void close() {
        held.clear();
        session.close();
    }

    private Optional<Lease> skip(BillingJob job) {
        skipped.get(job).get(SkipReason.HELD).increment();
        log.info("다른 실행이 배치 락을 쥐고 있어 이번 실행을 건너뜁니다 job={}", job.tag());
        return Optional.empty();
    }

    private synchronized void release(BillingJob job) {
        held.remove(job);
        try {
            if (!session.unlock(job.lockKey())) {
                log.warn("배치 락 연결이 끊겨 락이 이미 풀려 있었습니다 job={}", job.tag());
            }
        } catch (SQLException | RuntimeException e) {
            // 연결 문제면 그 연결의 락은 이미 풀렸고, 다음 시도에서 다시 연결한다
            log.warn("배치 락을 풀지 못했습니다 job={}", job.tag(), e);
        }
    }

    /** 잡은 락. 닫으면 푼다(여러 번 닫아도 한 번만 푼다) */
    public final class Lease implements AutoCloseable {

        private final BillingJob job;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Lease(BillingJob job) {
            this.job = job;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                release(job);
            }
        }
    }

    /** 락을 시도하지 못했다(DB 연결 불가, SQL 오류). 다른 실행이 쥐고 있는 것과 다르다 */
    public static class JobLockException extends RuntimeException {

        JobLockException(BillingJob job, Throwable cause) {
            super("배치 락을 시도하지 못했습니다 job=" + job.tag(), cause);
        }
    }
}

package com.hapbang.auxiliary.global.scheduling;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 배치·시작 작업을 pod 사이에서 한 번에 한 곳만 실행하게 하는 락 (지갑 #598 {@code JobLock} 구조, #715).
 * <p>
 * 배포(rolling update) 중에는 새 pod와 옛 pod가 잠깐 함께 떠서 같은 작업이 겹칠 수 있다. 작업을 시작할 때 PostgreSQL advisory lock을
 * 기다리지 않고 잡고, 잡지 못하면 그 회차를 건너뛴다.
 * <ul>
 *     <li>모든 락을 락 전용 연결 하나({@link AdvisoryLockSession})로 잡는다. 메서드를 직렬화한다.</li>
 *     <li>advisory lock은 같은 session이면 다시 잡혀도 성공한다(재진입). 그래서 이 pod가 쥔 작업을 메모리에 두고,
 *     이미 쥐고 있으면 DB에 묻지 않고 실패로 돌려준다(같은 pod 안의 중복).</li>
 *     <li>락 연결이 끊기면 그 연결의 락은 풀리고, 돌던 작업은 끝까지 둔다. 작업 자체의 멱등성이 마지막 방어선이다.</li>
 *     <li>두 pod가 동시에 실행하는 것만 막는다. 한쪽이 끝내고 푼 뒤 다른 쪽이 늦게 시작하면 같은 회차가 한 번 더 돈다.</li>
 * </ul>
 * 지갑과 다른 점: 작업 목록이 모듈마다 있으므로 enum 하나가 아니라 {@link LockedJob}을 받는다. 건너뛴 횟수 지표
 * {@code hapbang.auxiliary.job.lock.skipped{job, reason=held|error}}는 처음 건너뛸 때 만든다.
 */
@Slf4j
@Component
public class JobLock {

    static final String SKIPPED_METRIC = "hapbang.auxiliary.job.lock.skipped";

    private final AdvisoryLockSession session;
    private final MeterRegistry meterRegistry;
    private final Set<Long> held = new HashSet<>();

    @Autowired
    JobLock(JdbcConnectionDetails connectionDetails, ObjectProvider<MeterRegistry> meterRegistry) {
        this(PostgresAdvisoryLockSession.of(connectionDetails), meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    public JobLock(AdvisoryLockSession session, MeterRegistry meterRegistry) {
        this.session = session;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 작업의 락을 잡는다. 다른 실행이 쥐고 있으면 빈 값이다.
     *
     * @throws JobLockException 락을 시도하지 못했을 때(DB 연결 불가, SQL 오류). 작업을 락 없이 실행하지 않는다
     */
    public synchronized Optional<Lease> tryAcquire(LockedJob job) {
        if (held.contains(job.lockKey())) {
            return skip(job);
        }
        boolean locked;
        try {
            locked = session.tryLock(job.lockKey());
        } catch (SQLException | RuntimeException e) {
            skipped(job, "error").increment();
            log.warn("작업 락을 시도하지 못해 이번 실행을 건너뛴다 job={}", job.tag(), e);
            throw new JobLockException(job, e);
        }
        if (!locked) {
            return skip(job);
        }
        held.add(job.lockKey());
        return Optional.of(new Lease(job));
    }

    /**
     * 락을 잡으면 작업을 실행하고 풀며, 잡지 못하면(다른 실행이 쥠·락 오류) 실행하지 않고 돌아온다.
     *
     * @return 작업을 실행했으면 true
     */
    public boolean runOrSkip(LockedJob job, Runnable task) {
        Optional<Lease> lease;
        try {
            lease = tryAcquire(job);
        } catch (JobLockException e) {
            return false;
        }
        if (lease.isEmpty()) {
            return false;
        }
        try (Lease ignored = lease.get()) {
            task.run();
        }
        return true;
    }

    @PreDestroy
    synchronized void close() {
        held.clear();
        session.close();
    }

    private Optional<Lease> skip(LockedJob job) {
        skipped(job, "held").increment();
        log.info("다른 실행이 작업 락을 쥐고 있어 이번 실행을 건너뛴다 job={}", job.tag());
        return Optional.empty();
    }

    private Counter skipped(LockedJob job, String reason) {
        return Counter.builder(SKIPPED_METRIC)
                .description("락을 잡지 못해 건너뛴 auxiliary-app 작업 실행 수")
                .tag("job", job.tag())
                .tag("reason", reason)
                .register(meterRegistry);
    }

    private synchronized void release(LockedJob job) {
        held.remove(job.lockKey());
        try {
            if (!session.unlock(job.lockKey())) {
                log.warn("작업 락 연결이 끊겨 락이 이미 풀려 있었다 job={}", job.tag());
            }
        } catch (SQLException | RuntimeException e) {
            // 연결 문제면 그 연결의 락은 이미 풀렸고, 다음 시도에서 다시 연결한다
            log.warn("작업 락을 풀지 못했다 job={}", job.tag(), e);
        }
    }

    /** 잡은 락. 닫으면 푼다(여러 번 닫아도 한 번만 푼다) */
    public final class Lease implements AutoCloseable {

        private final LockedJob job;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Lease(LockedJob job) {
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

        JobLockException(LockedJob job, Throwable cause) {
            super("작업 락을 시도하지 못했습니다 job=" + job.tag(), cause);
        }
    }
}

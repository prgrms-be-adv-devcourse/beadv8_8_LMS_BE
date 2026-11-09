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
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 배치·관리자 실행을 pod 사이에서 한 번에 한 곳만 실행하게 하는 락 (지갑 #598 {@code JobLock}을 옮김, #716).
 * <p>
 * 배포(rolling update) 중에는 새 pod와 옛 pod가 잠깐 함께 떠서 같은 cron 시각에 같은 배치가 겹칠 수 있다. 작업을 시작할 때
 * PostgreSQL advisory lock을 기다리지 않고 잡고, 잡지 못하면 그 회차를 건너뛴다.
 * <ul>
 *     <li>모든 락을 락 전용 연결 하나({@link AdvisoryLockSession})로 잡는다. 스케줄러 스레드가 여러 개라 메서드를 직렬화한다.</li>
 *     <li>advisory lock은 같은 session이면 다시 잡혀도 성공한다(재진입). 그래서 이 pod가 쥔 작업을 메모리에 두고,
 *     이미 쥐고 있으면 DB에 묻지 않고 실패로 돌려준다(같은 pod 안의 중복).</li>
 *     <li>session 단위 락이라 잡은 스레드와 다른 스레드에서 풀어도 된다. 관리자 실행은 요청 스레드에서 잡고 비동기 작업이 끝나며 푼다.</li>
 *     <li>락 연결이 끊기면 그 연결의 락은 풀리고, 돌던 작업은 끝까지 둔다. 작업의 멱등성이 마지막 방어선이다.</li>
 *     <li>두 pod가 동시에 실행하는 것만 막는다. 한쪽이 끝내고 푼 뒤 다른 쪽이 늦게 시작하면 같은 회차가 한 번 더 돈다.</li>
 * </ul>
 * 지갑과 달리 작업 목록을 global이 모른다. 모듈이 {@link JobLockKey}로 작업을 넘기고, 번호 규칙은 그 인터페이스에 있다.
 * 건너뛴 횟수는 {@code hapbang.auxiliary.job.lock.skipped{job, reason=held|error}}로 낸다.
 */
@Slf4j
@Component
public class JobLock {

    static final String SKIPPED_METRIC = "hapbang.auxiliary.job.lock.skipped";

    /** 락을 잡지 못한 이유 */
    enum SkipReason {
        /** 다른 pod 또는 이 pod의 다른 실행이 쥐고 있다 */
        HELD,
        /** 락을 시도하지 못했다(DB 연결 불가, SQL 오류) */
        ERROR
    }

    private final AdvisoryLockSession session;
    private final ObjectProvider<MeterRegistry> meterRegistry;
    /** 쥐고 있는 락 번호 */
    private final Set<Long> held = new HashSet<>();

    @Autowired
    JobLock(JdbcConnectionDetails connectionDetails, ObjectProvider<MeterRegistry> meterRegistry) {
        this(PostgresAdvisoryLockSession.of(connectionDetails), meterRegistry);
    }

    public JobLock(AdvisoryLockSession session, ObjectProvider<MeterRegistry> meterRegistry) {
        this.session = session;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 작업의 락을 잡는다. 다른 실행이 쥐고 있으면 빈 값이다.
     *
     * @throws JobLockException 락을 시도하지 못했을 때(DB 연결 불가, SQL 오류). 작업을 락 없이 실행하지 않는다
     */
    public synchronized Optional<Lease> tryAcquire(JobLockKey job) {
        if (held.contains(job.lockKey())) {
            return skip(job);
        }
        boolean locked;
        try {
            locked = session.tryLock(job.lockKey());
        } catch (SQLException | RuntimeException e) {
            // 로그는 예외를 받아 처리하는 쪽이 남긴다(다시 던지는 곳에서 찍지 않는다)
            countSkip(job, SkipReason.ERROR);
            throw new JobLockException(job, e);
        }
        if (!locked) {
            return skip(job);
        }
        held.add(job.lockKey());
        return Optional.of(new Lease(job));
    }

    @PreDestroy
    synchronized void close() {
        held.clear();
        session.close();
    }

    private Optional<Lease> skip(JobLockKey job) {
        countSkip(job, SkipReason.HELD);
        // 건너뜀·거절을 어떻게 처리했는지는 부른 쪽이 남긴다. 여기서는 지표만 센다
        log.debug("다른 실행이 락을 쥐고 있다 job={}", job.tag());
        return Optional.empty();
    }

    private void countSkip(JobLockKey job, SkipReason reason) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            Counter.builder(SKIPPED_METRIC)
                    .description("락을 잡지 못해 건너뛰거나 거절한 작업 실행 수")
                    .tag("job", job.tag())
                    .tag("reason", reason.name().toLowerCase())
                    .register(registry)
                    .increment();
        }
    }

    private synchronized void release(JobLockKey job) {
        held.remove(job.lockKey());
        try {
            if (!session.unlock(job.lockKey())) {
                log.warn("배치 락 연결이 끊겨 락이 이미 풀려 있었다 job={}", job.tag());
            }
        } catch (SQLException | RuntimeException e) {
            // 연결 문제면 그 연결의 락은 이미 풀렸고, 다음 시도에서 다시 연결한다
            log.warn("배치 락을 풀지 못했다 job={}", job.tag(), e);
        }
    }

    /** 잡은 락. 닫으면 푼다(여러 번 닫아도 한 번만 푼다). 다른 스레드에서 닫아도 된다 */
    public final class Lease implements AutoCloseable {

        private final JobLockKey job;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Lease(JobLockKey job) {
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

        JobLockException(JobLockKey job, Throwable cause) {
            super("배치 락을 시도하지 못했습니다 job=" + job.tag(), cause);
        }
    }
}

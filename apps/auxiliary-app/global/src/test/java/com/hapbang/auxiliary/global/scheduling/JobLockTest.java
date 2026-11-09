package com.hapbang.auxiliary.global.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 배치·Backfill 실행 락의 규칙 (지갑 #598 테스트를 가져옴, #715). 두 pod는 가짜 DB를 함께 쓰는 JobLock 두 개로 흉내 낸다.
class JobLockTest {

    private final FakeAdvisoryLocks db = new FakeAdvisoryLocks();

    @Test
    @DisplayName("락 호출은 한 모니터에서 줄을 서므로 호출당 대기 상한이 짧다(락 시도 약 7초, 해제 약 4초)")
    void waitUpperBound() {
        assertThat(PostgresAdvisoryLockSession.VALID_TIMEOUT_SECONDS
                + PostgresAdvisoryLockSession.CONNECT_TIMEOUT_SECONDS
                + PostgresAdvisoryLockSession.SOCKET_TIMEOUT_SECONDS).isLessThanOrEqualTo(7);
        assertThat(PostgresAdvisoryLockSession.VALID_TIMEOUT_SECONDS
                + PostgresAdvisoryLockSession.SOCKET_TIMEOUT_SECONDS).isLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName("한 pod가 쥐고 있으면 다른 pod는 잡지 못하고, 풀면 잡는다")
    void otherPodWaitsForRelease() {
        JobLock podA = db.pod();
        JobLock podB = db.pod();

        Optional<JobLock.Lease> lease = podA.tryAcquire(TestJob.A);
        assertThat(lease).isPresent();
        assertThat(podB.tryAcquire(TestJob.A)).isEmpty();
        // 다른 작업은 따로 잡힌다
        assertThat(podB.tryAcquire(TestJob.B)).isPresent();

        lease.get().close();
        assertThat(podB.tryAcquire(TestJob.A)).isPresent();
    }

    @Test
    @DisplayName("같은 pod에서 쥐고 있는 작업을 다시 잡으면 실패한다(advisory lock 재진입을 메모리로 막음)")
    void samePodCannotReenter() {
        JobLock pod = db.pod();

        JobLock.Lease lease = pod.tryAcquire(TestJob.C).orElseThrow();
        assertThat(pod.tryAcquire(TestJob.C)).isEmpty();

        lease.close();
        assertThat(pod.tryAcquire(TestJob.C)).isPresent();
    }

    @Test
    @DisplayName("Lease를 여러 번 닫아도 한 번만 푼다")
    void leaseClosesOnce() {
        JobLock podA = db.pod();
        JobLock podB = db.pod();

        JobLock.Lease lease = podA.tryAcquire(TestJob.D).orElseThrow();
        lease.close();
        JobLock.Lease other = podB.tryAcquire(TestJob.D).orElseThrow();
        lease.close();

        assertThat(db.isLocked(TestJob.D)).isTrue();
        other.close();
        assertThat(db.isLocked(TestJob.D)).isFalse();
    }

    @Test
    @DisplayName("runOrSkip: 잡으면 실행하고 풀며(true), 다른 pod가 쥐고 있으면 실행하지 않는다(false)")
    void runOrSkip() {
        JobLock podA = db.pod();
        JobLock podB = db.pod();
        AtomicInteger runs = new AtomicInteger();

        boolean ran = podA.runOrSkip(TestJob.E, () -> {
            assertThat(db.isLocked(TestJob.E)).isTrue();
            assertThat(podB.runOrSkip(TestJob.E, runs::incrementAndGet)).isFalse();
            runs.addAndGet(10);
        });

        assertThat(ran).isTrue();
        assertThat(runs).hasValue(10);
        assertThat(db.isLocked(TestJob.E)).isFalse();
    }

    @Test
    @DisplayName("작업 중 예외가 나도 락을 풀어 다음 실행이 돈다")
    void releasesOnException() {
        JobLock pod = db.pod();
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> pod.runOrSkip(TestJob.A, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(db.isLocked(TestJob.A)).isFalse();

        db.pod().runOrSkip(TestJob.A, runs::incrementAndGet);
        pod.runOrSkip(TestJob.A, runs::incrementAndGet);
        assertThat(runs).hasValue(2);
    }

    @Test
    @DisplayName("락을 시도하지 못하면 tryAcquire는 JobLockException이고, runOrSkip은 작업을 실행하지 않는다")
    void lockErrorSkips() {
        JobLock pod = db.pod();
        AtomicInteger runs = new AtomicInteger();
        db.failing(true);

        assertThatThrownBy(() -> pod.tryAcquire(TestJob.C))
                .isInstanceOf(JobLock.JobLockException.class);
        pod.runOrSkip(TestJob.B, runs::incrementAndGet);
        assertThat(runs).hasValue(0);

        // DB가 돌아오면 다시 잡힌다
        db.failing(false);
        pod.runOrSkip(TestJob.B, runs::incrementAndGet);
        assertThat(runs).hasValue(1);
    }

    @Test
    @DisplayName("락을 푸는 데 실패해도 예외를 던지지 않고, 이 pod는 다시 잡을 수 있다")
    void unlockErrorIsSwallowed() {
        JobLock pod = db.pod();

        JobLock.Lease lease = pod.tryAcquire(TestJob.F).orElseThrow();
        db.failing(true);
        lease.close();
        db.failing(false);

        assertThat(pod.tryAcquire(TestJob.F)).isPresent();
    }

    @Test
    @DisplayName("건너뛴 횟수를 작업·이유별 지표로 낸다")
    void skippedMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FakeAdvisoryLocks metered = new FakeAdvisoryLocks(registry);
        JobLock podA = metered.pod();
        JobLock podB = metered.pod();

        try (JobLock.Lease ignored = podA.tryAcquire(TestJob.A).orElseThrow()) {
            podB.runOrSkip(TestJob.A, () -> { });
            podB.runOrSkip(TestJob.A, () -> { });
        }
        metered.failing(true);
        podB.runOrSkip(TestJob.A, () -> { });

        assertThat(count(registry, "test-a", "held")).isEqualTo(2);
        assertThat(count(registry, "test-a", "error")).isEqualTo(1);
        // 건너뛴 적 없는 작업은 지표를 만들지 않는다
        assertThat(registry.find(JobLock.SKIPPED_METRIC).tag("job", "test-b").counter()).isNull();
    }

    private static double count(SimpleMeterRegistry registry, String job, String reason) {
        return registry.get(JobLock.SKIPPED_METRIC).tag("job", job).tag("reason", reason).counter().count();
    }
}

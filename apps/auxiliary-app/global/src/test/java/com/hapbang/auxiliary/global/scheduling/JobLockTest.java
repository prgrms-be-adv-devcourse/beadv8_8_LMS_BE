package com.hapbang.auxiliary.global.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 배치·관리자 실행 락의 규칙 (#716, 지갑 #598에서 옮김). 두 pod는 가짜 DB를 함께 쓰는 JobLock 두 개로 흉내 낸다 */
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
    @DisplayName("한 pod가 쥐고 있으면 다른 pod는 잡지 못하고, 풀면 잡는다. 다른 작업은 따로 잡힌다")
    void otherPodWaitsForRelease() {
        JobLock podA = db.pod();
        JobLock podB = db.pod();

        Optional<JobLock.Lease> lease = podA.tryAcquire(TestJob.FIRST);
        assertThat(lease).isPresent();
        assertThat(podB.tryAcquire(TestJob.FIRST)).isEmpty();
        assertThat(podB.tryAcquire(TestJob.SECOND)).isPresent();

        lease.get().close();
        assertThat(podB.tryAcquire(TestJob.FIRST)).isPresent();
    }

    @Test
    @DisplayName("같은 pod에서 쥐고 있는 작업을 다시 잡으면 실패한다(advisory lock 재진입을 메모리로 막음)")
    void samePodCannotReenter() {
        JobLock pod = db.pod();

        JobLock.Lease lease = pod.tryAcquire(TestJob.FIRST).orElseThrow();
        assertThat(pod.tryAcquire(TestJob.FIRST)).isEmpty();

        lease.close();
        assertThat(pod.tryAcquire(TestJob.FIRST)).isPresent();
    }

    @Test
    @DisplayName("Lease를 여러 번 닫아도 한 번만 푼다")
    void leaseClosesOnce() {
        JobLock podA = db.pod();
        JobLock podB = db.pod();

        JobLock.Lease lease = podA.tryAcquire(TestJob.FIRST).orElseThrow();
        lease.close();
        JobLock.Lease other = podB.tryAcquire(TestJob.FIRST).orElseThrow();
        lease.close();

        assertThat(db.isLocked(TestJob.FIRST)).isTrue();
        other.close();
        assertThat(db.isLocked(TestJob.FIRST)).isFalse();
    }

    @Test
    @DisplayName("요청 스레드에서 잡은 락을 다른 스레드에서 풀 수 있다(관리자 실행)")
    void releaseFromOtherThread() {
        JobLock pod = db.pod();

        JobLock.Lease lease = pod.tryAcquire(TestJob.FIRST).orElseThrow();
        CompletableFuture.runAsync(lease::close).join();

        assertThat(db.isLocked(TestJob.FIRST)).isFalse();
        assertThat(pod.tryAcquire(TestJob.FIRST)).isPresent();
    }

    @Test
    @DisplayName("락을 시도하지 못하면 JobLockException이고, DB가 돌아오면 다시 잡힌다")
    void lockError() {
        JobLock pod = db.pod();
        db.failing(true);

        assertThatThrownBy(() -> pod.tryAcquire(TestJob.FIRST)).isInstanceOf(JobLock.JobLockException.class);

        db.failing(false);
        assertThat(pod.tryAcquire(TestJob.FIRST)).isPresent();
    }

    @Test
    @DisplayName("락을 푸는 데 실패해도 예외를 던지지 않고, 이 pod는 다시 잡을 수 있다")
    void unlockErrorIsSwallowed() {
        JobLock pod = db.pod();

        JobLock.Lease lease = pod.tryAcquire(TestJob.FIRST).orElseThrow();
        db.failing(true);
        lease.close();
        db.failing(false);

        assertThat(pod.tryAcquire(TestJob.FIRST)).isPresent();
    }

    @Test
    @DisplayName("건너뛴 횟수를 작업·이유별 지표로 낸다. 지표 registry가 없어도 동작한다")
    void skippedMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JobLock podA = db.pod();
        JobLock podB = db.pod(registry);

        try (JobLock.Lease ignored = podA.tryAcquire(TestJob.FIRST).orElseThrow()) {
            assertThat(podB.tryAcquire(TestJob.FIRST)).isEmpty();
            assertThat(podB.tryAcquire(TestJob.FIRST)).isEmpty();
            // registry 없는 pod도 건너뛰기만 한다
            assertThat(podA.tryAcquire(TestJob.FIRST)).isEmpty();
        }
        db.failing(true);
        assertThatThrownBy(() -> podB.tryAcquire(TestJob.FIRST)).isInstanceOf(JobLock.JobLockException.class);

        assertThat(count(registry, "test-first", "held")).isEqualTo(2);
        assertThat(count(registry, "test-first", "error")).isEqualTo(1);
        assertThat(registry.find(JobLock.SKIPPED_METRIC).tag("job", "test-second").counter()).isNull();
    }

    private static double count(SimpleMeterRegistry registry, String job, String reason) {
        return registry.get(JobLock.SKIPPED_METRIC).tag("job", job).tag("reason", reason).counter().count();
    }
}

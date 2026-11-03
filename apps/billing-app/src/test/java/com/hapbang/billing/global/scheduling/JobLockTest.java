package com.hapbang.billing.global.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 배치·Backfill 실행 락의 규칙 (#598, BD-138). 두 pod는 가짜 DB를 함께 쓰는 JobLock 두 개로 흉내 낸다.
class JobLockTest {

    private final FakeAdvisoryLocks db = new FakeAdvisoryLocks();

    @Test
    @DisplayName("작업마다 락 번호가 다르다")
    void lockKeysAreUnique() {
        assertThat(Arrays.stream(BillingJob.values()).map(BillingJob::lockKey).distinct().count())
                .isEqualTo(BillingJob.values().length);
        assertThat(Arrays.stream(BillingJob.values()).map(BillingJob::tag).distinct().count())
                .isEqualTo(BillingJob.values().length);
    }

    @Test
    @DisplayName("한 pod가 쥐고 있으면 다른 pod는 잡지 못하고, 풀면 잡는다")
    void otherPodWaitsForRelease() {
        JobLock podA = db.pod();
        JobLock podB = db.pod();

        Optional<JobLock.Lease> lease = podA.tryAcquire(BillingJob.CHARGE_RECOVERY);
        assertThat(lease).isPresent();
        assertThat(podB.tryAcquire(BillingJob.CHARGE_RECOVERY)).isEmpty();
        // 다른 작업은 따로 잡힌다
        assertThat(podB.tryAcquire(BillingJob.REFUND_RECOVERY)).isPresent();

        lease.get().close();
        assertThat(podB.tryAcquire(BillingJob.CHARGE_RECOVERY)).isPresent();
    }

    @Test
    @DisplayName("같은 pod에서 쥐고 있는 작업을 다시 잡으면 실패한다(advisory lock 재진입을 메모리로 막음)")
    void samePodCannotReenter() {
        JobLock pod = db.pod();

        JobLock.Lease lease = pod.tryAcquire(BillingJob.MEMBER_BACKFILL).orElseThrow();
        assertThat(pod.tryAcquire(BillingJob.MEMBER_BACKFILL)).isEmpty();

        lease.close();
        assertThat(pod.tryAcquire(BillingJob.MEMBER_BACKFILL)).isPresent();
    }

    @Test
    @DisplayName("Lease를 여러 번 닫아도 한 번만 푼다")
    void leaseClosesOnce() {
        JobLock podA = db.pod();
        JobLock podB = db.pod();

        JobLock.Lease lease = podA.tryAcquire(BillingJob.EVENT_REPUBLISH).orElseThrow();
        lease.close();
        JobLock.Lease other = podB.tryAcquire(BillingJob.EVENT_REPUBLISH).orElseThrow();
        lease.close();

        assertThat(db.isLocked(BillingJob.EVENT_REPUBLISH)).isTrue();
        other.close();
        assertThat(db.isLocked(BillingJob.EVENT_REPUBLISH)).isFalse();
    }

    @Test
    @DisplayName("runOrSkip: 잡으면 실행하고 풀며, 다른 pod가 쥐고 있으면 실행하지 않는다")
    void runOrSkip() {
        JobLock podA = db.pod();
        JobLock podB = db.pod();
        AtomicInteger runs = new AtomicInteger();

        podA.runOrSkip(BillingJob.EXCHANGE_SETTLEMENT, () -> {
            assertThat(db.isLocked(BillingJob.EXCHANGE_SETTLEMENT)).isTrue();
            podB.runOrSkip(BillingJob.EXCHANGE_SETTLEMENT, runs::incrementAndGet);
            runs.addAndGet(10);
        });

        assertThat(runs).hasValue(10);
        assertThat(db.isLocked(BillingJob.EXCHANGE_SETTLEMENT)).isFalse();
    }

    @Test
    @DisplayName("작업 중 예외가 나도 락을 풀어 다음 실행이 돈다")
    void releasesOnException() {
        JobLock pod = db.pod();
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> pod.runOrSkip(BillingJob.CHARGE_RECOVERY, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(db.isLocked(BillingJob.CHARGE_RECOVERY)).isFalse();

        db.pod().runOrSkip(BillingJob.CHARGE_RECOVERY, runs::incrementAndGet);
        pod.runOrSkip(BillingJob.CHARGE_RECOVERY, runs::incrementAndGet);
        assertThat(runs).hasValue(2);
    }

    @Test
    @DisplayName("락을 시도하지 못하면 tryAcquire는 JobLockException이고, runOrSkip은 작업을 실행하지 않는다")
    void lockErrorSkips() {
        JobLock pod = db.pod();
        AtomicInteger runs = new AtomicInteger();
        db.failing(true);

        assertThatThrownBy(() -> pod.tryAcquire(BillingJob.MEMBER_BACKFILL))
                .isInstanceOf(JobLock.JobLockException.class);
        pod.runOrSkip(BillingJob.REFUND_RECOVERY, runs::incrementAndGet);
        assertThat(runs).hasValue(0);

        // DB가 돌아오면 다시 잡힌다
        db.failing(false);
        pod.runOrSkip(BillingJob.REFUND_RECOVERY, runs::incrementAndGet);
        assertThat(runs).hasValue(1);
    }

    @Test
    @DisplayName("락을 푸는 데 실패해도 예외를 던지지 않고, 이 pod는 다시 잡을 수 있다")
    void unlockErrorIsSwallowed() {
        JobLock pod = db.pod();

        JobLock.Lease lease = pod.tryAcquire(BillingJob.EXCHANGE_PAYOUT_RECOVERY).orElseThrow();
        db.failing(true);
        lease.close();
        db.failing(false);

        assertThat(pod.tryAcquire(BillingJob.EXCHANGE_PAYOUT_RECOVERY)).isPresent();
    }

    @Test
    @DisplayName("건너뛴 횟수를 작업·이유별 지표로 낸다")
    void skippedMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JobLock podA = db.pod();
        JobLock podB = db.pod();
        podB.bindTo(registry);

        try (JobLock.Lease ignored = podA.tryAcquire(BillingJob.CHARGE_RECOVERY).orElseThrow()) {
            podB.runOrSkip(BillingJob.CHARGE_RECOVERY, () -> { });
            podB.runOrSkip(BillingJob.CHARGE_RECOVERY, () -> { });
        }
        db.failing(true);
        podB.runOrSkip(BillingJob.CHARGE_RECOVERY, () -> { });

        assertThat(count(registry, "charge-recovery", "held")).isEqualTo(2);
        assertThat(count(registry, "charge-recovery", "error")).isEqualTo(1);
        assertThat(count(registry, "refund-recovery", "held")).isZero();
    }

    private static double count(SimpleMeterRegistry registry, String job, String reason) {
        return registry.get(JobLock.SKIPPED_METRIC).tag("job", job).tag("reason", reason).functionCounter().count();
    }
}

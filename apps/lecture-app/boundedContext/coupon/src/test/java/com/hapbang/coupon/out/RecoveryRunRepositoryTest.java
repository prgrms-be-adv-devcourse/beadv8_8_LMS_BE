package com.hapbang.coupon.out;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.hapbang.coupon.domain.RecoveryBoundary;
import com.hapbang.coupon.domain.RecoveryRedisState;
import com.hapbang.coupon.domain.RecoveryRun;
import com.hapbang.coupon.domain.RecoveryStep;
import com.hapbang.coupon.domain.RecoveryUnresolved;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class RecoveryRunRepositoryTest {

    private static final long COUPON_ID = 42L;

    @Autowired
    private RecoveryRunRepository recoveryRunRepository;

    @Autowired
    private EntityManager entityManager;

    private RecoveryRun resumed(long couponId) {
        RecoveryRun run = RecoveryRun.start(couponId, 1L, ADMIN_ID, true, NOW);
        run.recordBoundary(new RecoveryBoundary(2, true, "0:5,1:0,2:0", ""), NOW);
        run.recordResults(RecoveryUnresolved.none(), NOW);
        run.markRebuilt(run.claimRebuild(NOW, Duration.ofMinutes(2)), NOW);
        run.markResumed(run.claimResume("확인함", NOW, Duration.ofMinutes(2)), "확인함", "quantity=10", NOW);
        return recoveryRunRepository.saveAndFlush(run);
    }

    @Test
    void 쿠폰마다_끝나지_않은_복구는_하나이고_끝난_복구와_다른_쿠폰은_막지_않는다() {
        RecoveryRun done = resumed(COUPON_ID);
        RecoveryRun active = recoveryRunRepository.saveAndFlush(RecoveryRun.start(COUPON_ID, 2L, ADMIN_ID, false, NOW));
        recoveryRunRepository.saveAndFlush(RecoveryRun.start(COUPON_ID + 1, 1L, ADMIN_ID, true, NOW));
        entityManager.clear();

        RecoveryRun found = recoveryRunRepository.findFirstByCouponIdAndStepNotIn(COUPON_ID, RecoveryStep.finished())
                .orElseThrow();
        assertThat(found.getId()).isEqualTo(active.getId());
        assertThat(found.getNewEpoch()).isEqualTo(3L);
        assertThat(recoveryRunRepository.findByCouponIdOrderByIdDesc(COUPON_ID))
                .extracting(RecoveryRun::getId).containsExactly(active.getId(), done.getId());
        assertThat(recoveryRunRepository.findByIdAndCouponId(done.getId(), COUPON_ID + 1)).isEmpty();
        RecoveryRun reloaded = recoveryRunRepository.findById(done.getId()).orElseThrow();
        assertThat(reloaded.getStep()).isEqualTo(RecoveryStep.RESUMED);
        assertThat(reloaded.getKafkaBoundary()).isEqualTo("0:5,1:0,2:0");
        assertThat(reloaded.getUnresolvedCount()).isZero();
        // 유실 의심 복구 조회는 단계와 무관하게 가장 최근 것이다(재개한 복구도 UNKNOWN 창 판정에 쓴다)
        assertThat(recoveryRunRepository.findFirstByCouponIdAndRequestsLostTrueOrderByIdDesc(COUPON_ID))
                .get().extracting(RecoveryRun::getId).isEqualTo(done.getId());
        assertThat(recoveryRunRepository.findFirstByCouponIdOrderByIdDesc(COUPON_ID))
                .get().extracting(RecoveryRun::getId).isEqualTo(active.getId());

        // 같은 쿠폰의 두 번째 끝나지 않은 복구는 부분 유일 인덱스에 걸린다(트랜잭션이 깨지므로 마지막에 본다)
        assertThatThrownBy(() -> recoveryRunRepository.saveAndFlush(RecoveryRun.start(COUPON_ID, 2L, ADMIN_ID, false, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_recovery_run_active");
    }

    @Test
    void 취소한_복구는_끝난_복구라_같은_쿠폰의_새_복구를_막지_않고_취소_기록이_남는다() {
        RecoveryRun aborted = RecoveryRun.start(COUPON_ID, 1L, ADMIN_ID, true, NOW);
        aborted.abort(ADMIN_ID, NOW);
        recoveryRunRepository.saveAndFlush(aborted);

        RecoveryRun next = recoveryRunRepository.saveAndFlush(RecoveryRun.start(COUPON_ID, 1L, ADMIN_ID, true, NOW));
        entityManager.clear();

        assertThat(recoveryRunRepository.findFirstByCouponIdAndStepNotIn(COUPON_ID, RecoveryStep.finished()))
                .get().extracting(RecoveryRun::getId).isEqualTo(next.getId());
        RecoveryRun reloaded = recoveryRunRepository.findById(aborted.getId()).orElseThrow();
        assertThat(reloaded.getStep()).isEqualTo(RecoveryStep.ABORTED);
        assertThat(reloaded.getAbortedBy()).isEqualTo(ADMIN_ID);
        assertThat(reloaded.getAbortedAt()).isEqualTo(NOW);
    }

    @Test
    void 재개_중_유실_취소는_사유와_함께_끝난_복구로_저장된다() {
        RecoveryRun run = RecoveryRun.start(COUPON_ID, 1L, ADMIN_ID, false, NOW);
        run.recordBoundary(new RecoveryBoundary(0, false, "0:1", ""), NOW);
        run.recordResults(RecoveryUnresolved.none(), NOW);
        run.markRebuilt(run.claimRebuild(NOW, Duration.ofMinutes(2)), NOW);
        run.abandonClaim(run.claimResume("확인함", NOW, Duration.ofMinutes(2)), NOW);
        run.abortLostResume(ADMIN_ID, RecoveryRedisState.PROCESS_CHANGED, NOW, Duration.ofMinutes(2));
        RecoveryRun saved = recoveryRunRepository.saveAndFlush(run);
        entityManager.clear();

        RecoveryRun reloaded = recoveryRunRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStep()).isEqualTo(RecoveryStep.ABORTED);
        assertThat(reloaded.getAbortReason()).isEqualTo("RESUME_UNKNOWN_PROCESS_CHANGED");
        assertThat(reloaded.isRequestsLost()).isTrue();
        assertThat(recoveryRunRepository.findFirstByCouponIdAndStepNotIn(COUPON_ID, RecoveryStep.finished())).isEmpty();
    }

    @Test
    void 재개_선점_단계와_선점_시각이_저장되고_끝나지_않은_복구로_센다() {
        RecoveryRun run = RecoveryRun.start(COUPON_ID, 1L, ADMIN_ID, false, NOW);
        run.recordBoundary(new RecoveryBoundary(0, false, "0:1", ""), NOW);
        run.recordResults(RecoveryUnresolved.none(), NOW);
        run.markRebuilt(run.claimRebuild(NOW, Duration.ofMinutes(2)), NOW);
        run.claimResume("확인함", NOW.plusSeconds(20), Duration.ofMinutes(2));
        recoveryRunRepository.saveAndFlush(run);
        entityManager.clear();

        RecoveryRun reloaded = recoveryRunRepository.findFirstByCouponIdAndStepNotIn(COUPON_ID, RecoveryStep.finished())
                .orElseThrow();
        assertThat(reloaded.getStep()).isEqualTo(RecoveryStep.RESUMING);
        assertThat(reloaded.getClaimedAt()).isEqualTo(NOW.plusSeconds(20));
    }

    @Test
    void 재구축_선점_단계와_차단_시각_선점_시각이_저장되고_끝나지_않은_복구로_센다() {
        RecoveryRun run = RecoveryRun.start(COUPON_ID, 1L, ADMIN_ID, false, NOW);
        run.markBlocked(NOW);
        run.recordBoundary(new RecoveryBoundary(0, false, "0:1", ""), NOW);
        run.recordResults(RecoveryUnresolved.none(), NOW);
        run.claimRebuild(NOW.plusSeconds(10), Duration.ofMinutes(2));
        recoveryRunRepository.saveAndFlush(run);
        entityManager.clear();

        RecoveryRun reloaded = recoveryRunRepository.findFirstByCouponIdAndStepNotIn(COUPON_ID, RecoveryStep.finished())
                .orElseThrow();
        assertThat(reloaded.getStep()).isEqualTo(RecoveryStep.REBUILDING);
        assertThat(reloaded.getBlockedAt()).isEqualTo(NOW);
        assertThat(reloaded.getClaimedAt()).isEqualTo(NOW.plusSeconds(10));
    }
}

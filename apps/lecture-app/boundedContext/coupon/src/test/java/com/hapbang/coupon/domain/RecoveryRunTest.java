package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// 복구 단계 전이: 순서대로만 나아가고, 미처리가 남으면 재구축하지 않으며, 재개는 근거가 있어야 하고, 취소는 재구축 전에만 한다
class RecoveryRunTest {

    private static final long COUPON_ID = 1L;
    private static final Instant LATER = NOW.plusSeconds(60);
    private static final RecoveryBoundary BOUNDARY = new RecoveryBoundary(3, false, "0:10,1:0,2:4", "0:0,1:0,2:0");
    private static final Duration LEASE = Duration.ofMinutes(2);

    /** 재구축 선점부터 완료까지 */
    private static void rebuild(RecoveryRun run, Instant now) {
        run.markRebuilt(run.claimRebuild(now, LEASE), now);
    }

    /** 재개 선점부터 완료까지 */
    private static void resume(RecoveryRun run, String basis, String evidence, Instant now) {
        run.markResumed(run.claimResume(basis, now, LEASE), basis, evidence, now);
    }

    private static RecoveryRun started() {
        return RecoveryRun.start(COUPON_ID, 1L, ADMIN_ID, false, NOW);
    }

    private static RecoveryRun resultsChecked(RecoveryUnresolved unresolved) {
        RecoveryRun run = started();
        run.recordBoundary(BOUNDARY, NOW);
        run.recordResults(unresolved, NOW);
        return run;
    }

    @Test
    void 시작하면_차단_단계이고_새_epoch_후보는_지금_epoch의_다음_값이다() {
        RecoveryRun run = started();

        assertThat(run.getStep()).isEqualTo(RecoveryStep.BLOCKED);
        assertThat(run.getPreviousEpoch()).isEqualTo(1L);
        assertThat(run.getNewEpoch()).isEqualTo(2L);
        assertThat(run.isActive()).isTrue();
    }

    @Test
    void 경계_확정은_재발행_건수와_경계를_기록하고_다시_실행하면_이전_미처리_기록을_지운다() {
        RecoveryRun run = started();
        run.recordBoundary(BOUNDARY, NOW);
        assertThat(run.getStep()).isEqualTo(RecoveryStep.BOUNDARY_FIXED);
        assertThat(run.getRepublishedCount()).isEqualTo(3);
        assertThat(run.getRepublishedAt()).isEqualTo(NOW);
        assertThat(run.getKafkaBoundary()).isEqualTo("0:10,1:0,2:4");

        run.recordBoundary(new RecoveryBoundary(0, true, "0:12", ""), LATER);

        assertThat(run.getRepublishedCount()).isZero();
        assertThat(run.getKafkaBoundary()).isEqualTo("0:12");
        assertThat(run.isRequestsLost()).isTrue();
        assertThat(run.getUnresolvedCount()).isNull();
    }

    @Test
    void 경계_확정_전에는_결과를_확정하지_못하고_결과_확정_뒤에는_경계를_다시_정하지_못한다() {
        RecoveryRun run = started();
        assertThatThrownBy(() -> run.recordResults(RecoveryUnresolved.none(), NOW)).isInstanceOf(CouponDomainException.class);

        RecoveryRun checked = resultsChecked(RecoveryUnresolved.none());
        assertThatThrownBy(() -> checked.recordBoundary(BOUNDARY, NOW)).isInstanceOf(CouponDomainException.class);
    }

    @Test
    void 미처리가_남으면_재구축하지_않고_0이_되도록_다시_확인하면_재구축한다() {
        RecoveryRun run = resultsChecked(new RecoveryUnresolved(1, 2, 0));
        assertThat(run.getUnresolvedCount()).isEqualTo(3L);
        assertThat(run.getUnresolvedDetail()).isEqualTo("pendingRequests=1,kafkaLag=2,dltUnresolved=0");

        assertThatThrownBy(() -> run.claimRebuild(NOW, LEASE))
                .isInstanceOf(CouponDomainException.class)
                .hasMessageContaining("미처리");
        assertThat(run.getStep()).isEqualTo(RecoveryStep.RESULTS_CHECKED);

        run.recordResults(new RecoveryUnresolved(0, 0, 1), NOW);
        assertThatThrownBy(() -> run.claimRebuild(NOW, LEASE)).isInstanceOf(CouponDomainException.class);

        run.recordResults(RecoveryUnresolved.none(), LATER);
        rebuild(run, LATER);
        assertThat(run.getStep()).isEqualTo(RecoveryStep.REBUILT);
    }

    @Test
    void 결과_확정_전에는_재구축하지_못한다() {
        RecoveryRun run = started();
        assertThatThrownBy(() -> run.claimRebuild(NOW, LEASE)).isInstanceOf(CouponDomainException.class);
        run.recordBoundary(BOUNDARY, NOW);
        assertThatThrownBy(() -> run.claimRebuild(NOW, LEASE)).isInstanceOf(CouponDomainException.class);
    }

    @Test
    void 재구축은_다시_실행할_수_있고_재구축_뒤에는_결과를_다시_확정하지_않아_취소_금지를_우회하지_못한다() {
        RecoveryRun run = resultsChecked(RecoveryUnresolved.none());
        rebuild(run, NOW);
        rebuild(run, LATER);
        assertThat(run.getStep()).isEqualTo(RecoveryStep.REBUILT);

        assertThatThrownBy(() -> run.recordResults(new RecoveryUnresolved(1, 0, 0), LATER))
                .isInstanceOf(CouponDomainException.class);
        assertThatThrownBy(() -> run.abort(ADMIN_ID, LATER)).isInstanceOf(CouponDomainException.class);

        assertThat(run.getStep()).isEqualTo(RecoveryStep.REBUILT);
        assertThat(run.getAbortedAt()).isNull();
    }

    @Test
    void 재구축_선점은_Redis를_바꾸기_전의_단계이고_진행_중인_선점이_있으면_다시_선점하지_못한다() {
        RecoveryRun run = resultsChecked(RecoveryUnresolved.none());

        RecoveryRun.StepClaim claim = run.claimRebuild(NOW, LEASE);

        assertThat(claim).isEqualTo(new RecoveryRun.StepClaim(RecoveryStep.REBUILDING, NOW, RecoveryStep.RESULTS_CHECKED));
        assertThat(run.getStep()).isEqualTo(RecoveryStep.REBUILDING);
        assertThat(run.isActive()).isTrue();
        assertThatThrownBy(() -> run.claimRebuild(NOW.plusSeconds(1), LEASE))
                .isInstanceOf(CouponDomainException.class)
                .hasMessageContaining("진행 중");
        // 선점 중에는 결과 확정·취소·재개를 하지 않는다
        assertThatThrownBy(() -> run.recordResults(RecoveryUnresolved.none(), NOW)).isInstanceOf(CouponDomainException.class);
        assertThatThrownBy(() -> run.abort(ADMIN_ID, NOW)).isInstanceOf(CouponDomainException.class);
        assertThatThrownBy(() -> run.claimResume("확인함", NOW, LEASE)).isInstanceOf(CouponDomainException.class);
    }

    @Test
    void lease가_지난_선점은_버려진_것으로_보고_새로_선점하며_옛_선점으로는_완료하지_못한다() {
        RecoveryRun run = resultsChecked(RecoveryUnresolved.none());
        RecoveryRun.StepClaim abandoned = run.claimRebuild(NOW, LEASE);

        RecoveryRun.StepClaim fresh = run.claimRebuild(NOW.plus(LEASE), LEASE);

        assertThat(fresh.from()).isEqualTo(RecoveryStep.REBUILDING);
        assertThatThrownBy(() -> run.markRebuilt(abandoned, LATER)).isInstanceOf(CouponDomainException.class);
        run.markRebuilt(fresh, LATER);
        assertThat(run.getStep()).isEqualTo(RecoveryStep.REBUILT);
        assertThat(run.getClaimedAt()).isNull();
    }

    @Test
    void Redis가_쓰지_않은_재구축_실패는_선점_전_단계로_되돌리고_결과를_모르는_실패는_재구축_단계에_남는다() {
        RecoveryRun released = resultsChecked(RecoveryUnresolved.none());
        released.releaseClaim(released.claimRebuild(NOW, LEASE), NOW);
        assertThat(released.getStep()).isEqualTo(RecoveryStep.RESULTS_CHECKED);
        released.abort(ADMIN_ID, LATER);
        assertThat(released.getStep()).isEqualTo(RecoveryStep.ABORTED);

        RecoveryRun rebuiltBefore = resultsChecked(RecoveryUnresolved.none());
        rebuild(rebuiltBefore, NOW);
        rebuiltBefore.releaseClaim(rebuiltBefore.claimRebuild(LATER, LEASE), LATER);
        assertThat(rebuiltBefore.getStep()).isEqualTo(RecoveryStep.REBUILT);

        RecoveryRun unknown = resultsChecked(RecoveryUnresolved.none());
        unknown.abandonClaim(unknown.claimRebuild(NOW, LEASE), NOW);
        assertThat(unknown.getStep()).isEqualTo(RecoveryStep.REBUILDING);
        assertThat(unknown.getClaimedAt()).isNull();
        assertThatThrownBy(() -> unknown.abort(ADMIN_ID, LATER)).isInstanceOf(CouponDomainException.class);
        // 선점이 풀렸으므로 lease를 기다리지 않고 바로 다시 재구축한다
        rebuild(unknown, NOW.plusSeconds(1));
        assertThat(unknown.getStep()).isEqualTo(RecoveryStep.REBUILT);
    }

    @Test
    void 재개_선점과_재구축_선점은_한_복구에서_하나만_잡히고_재개를_선점한_뒤에는_재구축_취소를_하지_않는다() {
        RecoveryRun resumeFirst = atStep(RecoveryStep.REBUILT);
        RecoveryRun.StepClaim resumeClaim = resumeFirst.claimResume("확인함", NOW, LEASE);
        assertThat(resumeClaim).isEqualTo(new RecoveryRun.StepClaim(RecoveryStep.RESUMING, NOW, RecoveryStep.REBUILT));
        assertThat(resumeFirst.getStep()).isEqualTo(RecoveryStep.RESUMING);
        assertThat(resumeFirst.isActive()).isTrue();
        assertThatThrownBy(() -> resumeFirst.claimRebuild(NOW.plusSeconds(1), LEASE)).isInstanceOf(CouponDomainException.class);
        assertThatThrownBy(() -> resumeFirst.claimResume("확인함", NOW.plusSeconds(1), LEASE))
                .isInstanceOf(CouponDomainException.class)
                .hasMessageContaining("진행 중");
        assertThatThrownBy(() -> resumeFirst.abort(ADMIN_ID, NOW)).isInstanceOf(CouponDomainException.class);
        assertThatThrownBy(() -> resumeFirst.recordResults(RecoveryUnresolved.none(), NOW))
                .isInstanceOf(CouponDomainException.class);

        RecoveryRun rebuildFirst = atStep(RecoveryStep.REBUILT);
        rebuildFirst.claimRebuild(NOW, LEASE);
        assertThatThrownBy(() -> rebuildFirst.claimResume("확인함", NOW.plusSeconds(1), LEASE))
                .isInstanceOf(CouponDomainException.class);
        assertThat(rebuildFirst.getStep()).isEqualTo(RecoveryStep.REBUILDING);
    }

    @Test
    void Redis가_쓰지_않은_재개_실패는_재구축_뒤로_되돌리고_결과를_모르는_실패는_재개_단계에_남아_재개만_다시_한다() {
        RecoveryRun released = atStep(RecoveryStep.REBUILT);
        released.releaseClaim(released.claimResume("확인함", NOW, LEASE), NOW);
        assertThat(released.getStep()).isEqualTo(RecoveryStep.REBUILT);
        assertThat(released.getClaimedAt()).isNull();
        // 되돌린 뒤에는 재구축을 다시 할 수 있다
        rebuild(released, LATER);
        assertThat(released.getStep()).isEqualTo(RecoveryStep.REBUILT);

        RecoveryRun unknown = atStep(RecoveryStep.REBUILT);
        unknown.abandonClaim(unknown.claimResume("확인함", NOW, LEASE), NOW);
        assertThat(unknown.getStep()).isEqualTo(RecoveryStep.RESUMING);
        assertThat(unknown.getClaimedAt()).isNull();
        assertThatThrownBy(() -> unknown.claimRebuild(LATER, LEASE)).isInstanceOf(CouponDomainException.class);
        assertThatThrownBy(() -> unknown.abort(ADMIN_ID, LATER)).isInstanceOf(CouponDomainException.class);
        // 앞선 결과를 모르는 채 다시 선점하면 되돌릴 곳도 RESUMING이다(쓰지 않은 재시도도 재구축으로 돌아가지 않는다)
        RecoveryRun.StepClaim retry = unknown.claimResume("확인함", NOW.plusSeconds(1), LEASE);
        assertThat(retry.from()).isEqualTo(RecoveryStep.RESUMING);
        unknown.releaseClaim(retry, NOW.plusSeconds(1));
        assertThat(unknown.getStep()).isEqualTo(RecoveryStep.RESUMING);
        resume(unknown, "확인함", "quantity=10", LATER);
        assertThat(unknown.getStep()).isEqualTo(RecoveryStep.RESUMED);
        assertThat(unknown.getClaimedAt()).isNull();
    }

    @Test
    void lease가_지난_재개_선점은_다시_선점하고_옛_선점으로는_완료하지_못한다() {
        RecoveryRun run = atStep(RecoveryStep.REBUILT);
        RecoveryRun.StepClaim abandoned = run.claimResume("확인함", NOW, LEASE);

        RecoveryRun.StepClaim fresh = run.claimResume("확인함", NOW.plus(LEASE), LEASE);

        assertThat(fresh.from()).isEqualTo(RecoveryStep.RESUMING);
        assertThatThrownBy(() -> run.markResumed(abandoned, "확인함", "e", LATER)).isInstanceOf(CouponDomainException.class);
        assertThatThrownBy(() -> run.releaseClaim(abandoned, LATER)).isInstanceOf(CouponDomainException.class);
        run.markResumed(fresh, "확인함", "quantity=10", LATER);
        assertThat(run.getStep()).isEqualTo(RecoveryStep.RESUMED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONTROL_MISSING", "PROCESS_CHANGED"})
    void 재개_결과를_모르는_복구는_선점이_없고_Redis_유실이_확인되면_사유와_유실_의심을_남기고_취소한다(RecoveryRedisState redisState) {
        RecoveryRun run = atStep(RecoveryStep.REBUILT);
        run.abandonClaim(run.claimResume("확인함", NOW, LEASE), NOW);

        run.abortLostResume(ADMIN_ID, redisState, LATER, LEASE);

        assertThat(run.getStep()).isEqualTo(RecoveryStep.ABORTED);
        assertThat(run.isActive()).isFalse();
        assertThat(run.isRequestsLost()).isTrue();
        assertThat(run.getAbortReason()).isEqualTo("RESUME_UNKNOWN_" + redisState);
        assertThat(run.getAbortedBy()).isEqualTo(ADMIN_ID);
        assertThat(run.getAbortedAt()).isEqualTo(LATER);
        assertThat(run.getClaimedAt()).isNull();
    }

    @Test
    void 재개_중_유실_취소는_선점이_살아_있거나_Redis가_그대로이거나_RESUMING이_아니면_하지_않는다() {
        RecoveryRun claimed = atStep(RecoveryStep.REBUILT);
        claimed.claimResume("확인함", NOW, LEASE);
        assertThatThrownBy(() -> claimed.abortLostResume(ADMIN_ID, RecoveryRedisState.CONTROL_MISSING, NOW.plusSeconds(1), LEASE))
                .isInstanceOf(CouponDomainException.class)
                .hasMessageContaining("진행 중");
        assertThat(claimed.getStep()).isEqualTo(RecoveryStep.RESUMING);
        // lease가 지난 선점은 버려진 것이라 취소할 수 있다
        claimed.abortLostResume(ADMIN_ID, RecoveryRedisState.PROCESS_CHANGED, NOW.plus(LEASE), LEASE);
        assertThat(claimed.getStep()).isEqualTo(RecoveryStep.ABORTED);

        RecoveryRun intact = atStep(RecoveryStep.REBUILT);
        intact.abandonClaim(intact.claimResume("확인함", NOW, LEASE), NOW);
        assertThatThrownBy(() -> intact.abortLostResume(ADMIN_ID, RecoveryRedisState.INTACT, LATER, LEASE))
                .isInstanceOf(CouponDomainException.class)
                .hasMessageContaining("재개를 다시");
        assertThat(intact.getStep()).isEqualTo(RecoveryStep.RESUMING);
        assertThat(intact.getAbortReason()).isNull();
        // 일반 취소로는 RESUMING을 끝내지 못한다
        assertThatThrownBy(() -> intact.abort(ADMIN_ID, LATER)).isInstanceOf(CouponDomainException.class);

        RecoveryRun rebuilt = atStep(RecoveryStep.REBUILT);
        assertThatThrownBy(() -> rebuilt.abortLostResume(ADMIN_ID, RecoveryRedisState.CONTROL_MISSING, LATER, LEASE))
                .isInstanceOf(CouponDomainException.class);
        assertThat(rebuilt.getStep()).isEqualTo(RecoveryStep.REBUILT);
    }

    @Test
    void 재구축_선점으로는_재개를_완료하지_못한다() {
        RecoveryRun run = resultsChecked(RecoveryUnresolved.none());
        RecoveryRun.StepClaim rebuildClaim = run.claimRebuild(NOW, LEASE);

        assertThatThrownBy(() -> run.markResumed(rebuildClaim, "확인함", "e", NOW)).isInstanceOf(CouponDomainException.class);
        assertThat(run.getStep()).isEqualTo(RecoveryStep.REBUILDING);
    }

    @Test
    void 경계_확정은_차단_시각에서_생산자_전송_최대_시간이_지나야_한다() {
        Duration settle = Duration.ofSeconds(8);
        RecoveryRun run = started();
        assertThatThrownBy(() -> run.requireProducersSettled(LATER, settle))
                .isInstanceOf(CouponDomainException.class)
                .hasMessageContaining("시작을 다시");

        run.markBlocked(NOW);

        assertThatThrownBy(() -> run.requireProducersSettled(NOW.plusSeconds(5), settle))
                .isInstanceOf(CouponDomainException.class)
                .hasMessageContaining("3000ms");
        assertThatCode(() -> run.requireProducersSettled(NOW.plus(settle), settle)).doesNotThrowAnyException();
        assertThat(run.getBlockedAt()).isEqualTo(NOW);
    }

    @Test
    void 재개는_재구축_뒤에만_근거와_함께_하고_끝난_복구는_더_진행하지_않는다() {
        RecoveryRun beforeRebuild = resultsChecked(RecoveryUnresolved.none());
        assertThatThrownBy(() -> beforeRebuild.claimResume("확인함", NOW, LEASE))
                .isInstanceOf(CouponDomainException.class);

        RecoveryRun run = resultsChecked(RecoveryUnresolved.none());
        rebuild(run, NOW);
        assertThatThrownBy(() -> run.claimResume(" ", NOW, LEASE))
                .isInstanceOf(CouponDomainException.class)
                .hasMessageContaining("근거");

        resume(run, "유실 접수 없음을 확인함", "quantity=10", LATER);

        assertThat(run.getStep()).isEqualTo(RecoveryStep.RESUMED);
        assertThat(run.isActive()).isFalse();
        assertThat(run.getResumeBasis()).isEqualTo("유실 접수 없음을 확인함");
        assertThat(run.getResumeEvidence()).isEqualTo("quantity=10");
        assertThat(run.getResumedAt()).isEqualTo(LATER);
        assertThatThrownBy(() -> run.recordResults(RecoveryUnresolved.none(), LATER))
                .isInstanceOf(CouponDomainException.class);
        assertThatThrownBy(() -> run.markRequestsLost(LATER)).isInstanceOf(CouponDomainException.class);
    }

    @Test
    void 유실_의심은_한_번_기록되면_경계를_다시_정해도_지워지지_않는다() {
        RecoveryRun run = started();
        run.markRequestsLost(NOW);

        run.recordBoundary(BOUNDARY, LATER);

        assertThat(run.isRequestsLost()).isTrue();
    }

    private static RecoveryRun atStep(RecoveryStep step) {
        RecoveryRun run = started();
        if (step == RecoveryStep.BLOCKED) {
            return run;
        }
        run.recordBoundary(BOUNDARY, NOW);
        if (step == RecoveryStep.BOUNDARY_FIXED) {
            return run;
        }
        run.recordResults(RecoveryUnresolved.none(), NOW);
        if (step == RecoveryStep.RESULTS_CHECKED) {
            return run;
        }
        rebuild(run, NOW);
        if (step == RecoveryStep.REBUILT) {
            return run;
        }
        resume(run, "확인함", "quantity=10", NOW);
        return run;
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOCKED", "BOUNDARY_FIXED", "RESULTS_CHECKED"})
    void 재구축_전에는_취소할_수_있고_취소한_복구는_끝난_복구다(RecoveryStep step) {
        RecoveryRun run = atStep(step);

        run.abort(ADMIN_ID, LATER);

        assertThat(run.getStep()).isEqualTo(RecoveryStep.ABORTED);
        assertThat(run.isActive()).isFalse();
        assertThat(run.getAbortedBy()).isEqualTo(ADMIN_ID);
        assertThat(run.getAbortedAt()).isEqualTo(LATER);
        assertThat(run.getUpdatedAt()).isEqualTo(LATER);
    }

    @Test
    void 재구축_뒤에는_취소하지_못하고_재개로만_끝낸다() {
        RecoveryRun run = atStep(RecoveryStep.REBUILT);

        assertThatThrownBy(() -> run.abort(ADMIN_ID, LATER))
                .isInstanceOf(CouponDomainException.class)
                .hasMessageContaining("재개");
        assertThat(run.getStep()).isEqualTo(RecoveryStep.REBUILT);
        assertThat(run.getAbortedAt()).isNull();
    }

    @Test
    void 재개한_복구는_취소하지_못하고_취소를_다시_하면_첫_취소_기록을_그대로_둔다() {
        RecoveryRun resumed = atStep(RecoveryStep.RESUMED);
        assertThatThrownBy(() -> resumed.abort(ADMIN_ID, LATER)).isInstanceOf(CouponDomainException.class);

        RecoveryRun aborted = started();
        aborted.abort(ADMIN_ID, NOW);
        aborted.abort(ADMIN_ID + 1, LATER);
        assertThat(aborted.getStep()).isEqualTo(RecoveryStep.ABORTED);
        assertThat(aborted.getAbortedBy()).isEqualTo(ADMIN_ID);
        assertThat(aborted.getUpdatedAt()).isEqualTo(NOW);
        // 취소한 복구는 더 진행하지 않는다
        assertThatThrownBy(() -> aborted.recordBoundary(BOUNDARY, LATER)).isInstanceOf(CouponDomainException.class);
        assertThatThrownBy(() -> aborted.markRequestsLost(LATER)).isInstanceOf(CouponDomainException.class);
        assertThat(aborted.getAbortedAt()).isEqualTo(NOW);
    }

    @Test
    void 끝난_단계는_재개와_취소다() {
        assertThat(RecoveryStep.finished()).containsExactlyInAnyOrder(RecoveryStep.RESUMED, RecoveryStep.ABORTED);
    }

    @Test
    void 유실_의심이_없는_복구는_어느_단계든_UNKNOWN을_남기지_않는다() {
        Instant retainedUntil = NOW.plus(Duration.ofDays(7));

        assertThat(started().leavesUnknownAt(NOW, retainedUntil)).isFalse();
        assertThat(atStep(RecoveryStep.RESUMED).leavesUnknownAt(NOW, retainedUntil)).isFalse();
    }

    @Test
    void 유실_의심_복구는_재개_전이나_취소됐으면_UNKNOWN을_남기고_재개_뒤에는_Redis_기록_보관_기간_안에서만_남긴다() {
        Instant retainedUntil = LATER.plus(Duration.ofDays(7));
        RecoveryRun active = RecoveryRun.start(COUPON_ID, 1L, ADMIN_ID, true, NOW);
        assertThat(active.leavesUnknownAt(retainedUntil.plus(Duration.ofDays(30)), retainedUntil)).isTrue();

        RecoveryRun aborted = RecoveryRun.start(COUPON_ID, 1L, ADMIN_ID, true, NOW);
        aborted.abort(ADMIN_ID, NOW);
        assertThat(aborted.leavesUnknownAt(retainedUntil.plus(Duration.ofDays(30)), retainedUntil)).isTrue();

        RecoveryRun resumed = RecoveryRun.start(COUPON_ID, 1L, ADMIN_ID, true, NOW);
        resumed.recordBoundary(BOUNDARY, NOW);
        resumed.recordResults(RecoveryUnresolved.none(), NOW);
        rebuild(resumed, NOW);
        resume(resumed, "확인함", "quantity=10", LATER);
        assertThat(resumed.leavesUnknownAt(retainedUntil.minusMillis(1), retainedUntil)).isTrue();
        assertThat(resumed.leavesUnknownAt(retainedUntil, retainedUntil)).isFalse();
        // 보관 기간을 모르면 단정하지 않는다
        assertThat(resumed.leavesUnknownAt(retainedUntil.plus(Duration.ofDays(30)), null)).isTrue();
    }

    @Test
    void Redis_기록_보관_기간은_issue_control의_key_만료_시각이고_없으면_발급_종료와_여유로_계산한다() {
        Instant issueEndAt = NOW.plus(Duration.ofDays(1));
        Instant extended = issueEndAt.plus(Duration.ofDays(20));
        Duration margin = Duration.ofDays(7);

        assertThat(RecoveryRun.recordsRetainedUntil(extended, issueEndAt, margin)).isEqualTo(extended);
        assertThat(RecoveryRun.recordsRetainedUntil(null, issueEndAt, margin)).isEqualTo(issueEndAt.plus(margin));
        assertThat(RecoveryRun.recordsRetainedUntil(null, null, margin)).isNull();
    }
}

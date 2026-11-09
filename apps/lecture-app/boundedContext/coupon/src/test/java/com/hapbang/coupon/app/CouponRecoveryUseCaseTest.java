package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.*;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.hapbang.coupon.app.port.CouponIssueBoundaryProbe;
import com.hapbang.coupon.app.port.CouponIssueEventPublisher;
import com.hapbang.coupon.app.port.CouponIssueEventPublisher.PublishResult;
import com.hapbang.coupon.app.port.CouponRecoveryStore;
import com.hapbang.coupon.app.port.CouponRecoveryStore.ActivateResult;
import com.hapbang.coupon.app.port.CouponRecoveryStore.BlockResult;
import com.hapbang.coupon.app.port.CouponRecoveryStore.RebuildControl;
import com.hapbang.coupon.app.port.CouponRecoveryStore.Snapshot;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.app.port.CouponRecoveryStore.RebuildResult;
import com.hapbang.coupon.config.CouponIssueDeliveryProperties;
import com.hapbang.coupon.config.CouponRecoveryProperties;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponIssueResult;
import com.hapbang.coupon.domain.IssueRequestRecord;
import com.hapbang.coupon.domain.RecoveryBoundary;
import com.hapbang.coupon.domain.RecoveryIssueResult;
import com.hapbang.coupon.domain.RecoveryOffsets;
import com.hapbang.coupon.domain.RecoveryRebuildPlan;
import com.hapbang.coupon.domain.RecoveryRedisState;
import com.hapbang.coupon.domain.RecoveryRun;
import com.hapbang.coupon.domain.RecoveryStep;
import com.hapbang.coupon.domain.RecoveryUnresolved;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.CouponDltLogRepository;
import com.hapbang.coupon.out.RecoveryLookupRepository;
import com.hapbang.coupon.out.RecoveryRunRepository;
import com.hapbang.coupon.out.redis.CouponRedisProperties;
import com.hapbang.shared.coupon.event.CouponIssueRequested;

// 복구 UseCase의 단계별 전제 조건과 저장소 호출. 단계 순서 규칙 자체는 RecoveryRunTest가 본다
class CouponRecoveryUseCaseTest {

    private static final long RECOVERY_ID = 5L;
    private static final String KAFKA_BOUNDARY = "0:10,1:0,2:4";

    private final CouponSupport couponSupport = mock();
    private final RecoveryRunRepository runRepository = mock();
    private final RecoveryLookupRepository lookupRepository = mock();
    private final CouponDltLogRepository dltLogRepository = mock();
    private final CouponRecoveryStore recoveryStore = mock();
    private final CouponIssueEventPublisher publisher = mock();
    private final CouponIssueBoundaryProbe probe = mock();
    private final PlatformTransactionManager transactionManager = mock();
    private final CouponRecoveryUseCase useCase = new CouponRecoveryUseCase(couponSupport, runRepository,
            lookupRepository, dltLogRepository, recoveryStore, publisher, probe, new CouponRedisProperties(null),
            CouponRecoveryProperties.defaults(), CouponIssueDeliveryProperties.defaults(), transactionManager, CLOCK);
    /** 기본값의 경계 대기: 전송 준비 1초 + max(전송 수명 5초, 재발행 ACK 3초) + 여유 2초 */
    private static final Duration SETTLE = Duration.ofSeconds(8);
    /** 재구축·재개 선점 lease 기본값 */
    private static final Duration LEASE = Duration.ofMinutes(2);

    private Coupon coupon;
    /** 새로 저장한 복구. 저장 뒤의 트랜잭션이 다시 읽는다 */
    private RecoveryRun savedRun;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        coupon = openSellerCoupon();
        when(couponSupport.findCoupon(COUPON_ID)).thenReturn(coupon);
        when(couponSupport.findCouponIncrementingVersion(COUPON_ID)).thenReturn(coupon);
        when(runRepository.saveAndFlush(any())).thenAnswer(invocation -> savedRun = withRunId(invocation.getArgument(0)));
        when(runRepository.findById(RECOVERY_ID)).thenAnswer(invocation -> Optional.ofNullable(savedRun));
        when(probe.eventEndOffsets()).thenReturn(RecoveryOffsets.parse(KAFKA_BOUNDARY));
        when(probe.dltEndOffsets()).thenReturn(RecoveryOffsets.empty());
        when(probe.committedOffsets()).thenReturn(RecoveryOffsets.parse(KAFKA_BOUNDARY));
    }

    private static RecoveryRun withRunId(RecoveryRun run) {
        ReflectionTestUtils.setField(run, "id", RECOVERY_ID);
        return run;
    }

    private RecoveryRun givenRun(RecoveryRun run) {
        withRunId(run);
        when(runRepository.findByIdAndCouponId(RECOVERY_ID, COUPON_ID)).thenReturn(Optional.of(run));
        when(runRepository.findById(RECOVERY_ID)).thenReturn(Optional.of(run));
        return run;
    }

    /** 차단한 지 경계 대기가 지난 복구 */
    private RecoveryRun blockedRun() {
        RecoveryRun run = RecoveryRun.start(COUPON_ID, coupon.getEpoch(), ADMIN_ID, false, NOW.minus(SETTLE));
        run.markBlocked(NOW.minus(SETTLE));
        return givenRun(run);
    }

    private static void rebuilt(RecoveryRun run) {
        run.markRebuilt(run.claimRebuild(NOW, LEASE), NOW);
    }

    private static void resumed(RecoveryRun run) {
        run.markResumed(run.claimResume("확인함", NOW, LEASE), "확인함", "evidence", NOW);
    }

    /** 재구축까지 마친 복구 */
    private RecoveryRun rebuiltRun() {
        RecoveryRun run = resultsCheckedRun();
        rebuilt(run);
        return run;
    }

    private RecoveryRun resultsCheckedRun() {
        RecoveryRun run = blockedRun();
        run.recordBoundary(new RecoveryBoundary(0, false, KAFKA_BOUNDARY, ""), NOW);
        run.recordResults(RecoveryUnresolved.none(), NOW);
        return run;
    }

    private static String record(long memberId, boolean compensated) {
        return new IssueRequestRecord(memberId, NOW, NOW.plus(Duration.ofDays(30)), true, compensated).format();
    }

    private void givenSnapshot(Long stock, Map<String, String> requests) {
        when(recoveryStore.snapshot(COUPON_ID)).thenReturn(new Snapshot(true, false, stock, requests));
    }

    @Test
    void 시작하면_복구를_기록한_뒤_접수를_막고_issue_control이_없으면_유실_의심으로_남긴다() {
        when(runRepository.findFirstByCouponIdAndStepNotIn(COUPON_ID, RecoveryStep.finished())).thenReturn(Optional.empty());
        when(recoveryStore.block(eq(COUPON_ID), anyLong())).thenReturn(new BlockResult(false, false, false));
        when(runRepository.findById(RECOVERY_ID)).thenAnswer(invocation -> Optional.of(
                withRunId(RecoveryRun.start(COUPON_ID, 1L, ADMIN_ID, false, NOW))));

        var view = useCase.start(ADMIN_ID, COUPON_ID);

        assertThat(view.step()).isEqualTo(RecoveryStep.BLOCKED);
        assertThat(view.newEpoch()).isEqualTo(2L);
        assertThat(view.requestsLost()).isTrue();
        assertThat(view.blockedAt()).isEqualTo(NOW);
        var order = inOrder(runRepository, recoveryStore);
        order.verify(runRepository).saveAndFlush(any());
        order.verify(recoveryStore).block(COUPON_ID, RECOVERY_ID);
    }

    @Test
    void 시작_단계에_머문_복구가_있으면_차단만_다시_하고_그_뒤_단계면_새로_시작하지_않는다() {
        RecoveryRun blocked = blockedRun();
        when(runRepository.findFirstByCouponIdAndStepNotIn(COUPON_ID, RecoveryStep.finished())).thenReturn(Optional.of(blocked));
        when(recoveryStore.block(eq(COUPON_ID), anyLong())).thenReturn(new BlockResult(true, false, false));

        assertThat(useCase.start(ADMIN_ID, COUPON_ID).recoveryId()).isEqualTo(RECOVERY_ID);
        verify(runRepository, never()).saveAndFlush(any());
        verify(recoveryStore).block(COUPON_ID, RECOVERY_ID);

        blocked.recordBoundary(new RecoveryBoundary(0, false, "", ""), NOW);
        assertThatThrownBy(() -> useCase.start(ADMIN_ID, COUPON_ID))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_IN_PROGRESS));
        verify(recoveryStore, times(1)).block(COUPON_ID, RECOVERY_ID);
    }

    @Test
    void 발급을_연_적이_없는_쿠폰은_복구하지_않는다() {
        when(couponSupport.findCoupon(COUPON_ID)).thenReturn(reviewSellerCoupon());

        assertThatThrownBy(() -> useCase.start(ADMIN_ID, COUPON_ID))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.INVALID_COUPON_STATUS));
        verifyNoInteractions(recoveryStore);
    }

    @Test
    void 경계_확정은_DB_결과가_없는_요청만_이전_epoch로_재발행하고_보상_완료와_처리되지_않은_DLT는_건너뛴다() {
        RecoveryRun run = blockedRun();
        when(recoveryStore.block(eq(COUPON_ID), anyLong())).thenReturn(new BlockResult(true, false, false));
        givenSnapshot(7L, Map.of("req-done", record(1L, false), "req-pending", record(2L, false),
                "req-compensated", record(3L, true), "req-dlt", record(4L, false)));
        when(lookupRepository.findExistingRequestIds(eq(COUPON_ID), anyCollection())).thenReturn(List.of("req-done"));
        when(dltLogRepository.findUnresolvedRequestIds(COUPON_ID)).thenReturn(List.of("req-dlt"));
        when(publisher.publish(any(), any())).thenReturn(PublishResult.ACKED);

        var view = useCase.fixBoundary(ADMIN_ID, COUPON_ID, RECOVERY_ID);

        ArgumentCaptor<CouponIssueRequested> event = ArgumentCaptor.forClass(CouponIssueRequested.class);
        verify(publisher).publish(event.capture(), eq(Duration.ofSeconds(3)));
        assertThat(event.getValue()).isEqualTo(new CouponIssueRequested("req-pending", COUPON_ID, 2L, coupon.getEpoch(),
                NOW, NOW.plus(Duration.ofDays(30))));
        assertThat(view.step()).isEqualTo(RecoveryStep.BOUNDARY_FIXED);
        assertThat(view.republishedCount()).isEqualTo(1);
        assertThat(view.kafkaBoundary()).isEqualTo(KAFKA_BOUNDARY);
        assertThat(run.isRequestsLost()).isFalse();
    }

    @Test
    void 경계_재발행은_접수_기록의_epoch를_싣는다() {
        blockedRun();
        when(recoveryStore.block(eq(COUPON_ID), anyLong())).thenReturn(new BlockResult(true, false, false));
        givenSnapshot(9L, Map.of("req-new", IssueRequestRecord.accepted(2L, NOW, NOW.plus(Duration.ofDays(30)), 3L)
                .format()));
        when(publisher.publish(any(), any())).thenReturn(PublishResult.ACKED);

        useCase.fixBoundary(ADMIN_ID, COUPON_ID, RECOVERY_ID);

        ArgumentCaptor<CouponIssueRequested> event = ArgumentCaptor.forClass(CouponIssueRequested.class);
        verify(publisher).publish(event.capture(), any());
        assertThat(event.getValue().epoch()).isEqualTo(3L);
    }

    @Test
    void 재발행이_한_건이라도_실패하면_경계를_기록하지_않는다() {
        RecoveryRun run = blockedRun();
        when(recoveryStore.block(eq(COUPON_ID), anyLong())).thenReturn(new BlockResult(true, false, false));
        givenSnapshot(8L, Map.of("req-a", record(1L, false), "req-b", record(2L, false)));
        when(publisher.publish(any(), any())).thenReturn(PublishResult.ACKED, PublishResult.TIMED_OUT);

        assertThatThrownBy(() -> useCase.fixBoundary(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_EVENT_PUBLISH_FAILED));
        assertThat(run.getStep()).isEqualTo(RecoveryStep.BLOCKED);
        // 시간 초과한 재발행이 아직 전송 중일 수 있어 차단 시각을 지금으로 다시 잡는다
        assertThat(run.getBlockedAt()).isEqualTo(NOW);
        verifyNoInteractions(probe);
    }

    @Test
    void 경계_확정은_차단_뒤_생산자_전송_최대_시간과_여유가_지나기_전에는_409와_남은_시간으로_거절한다() {
        RecoveryRun run = givenRun(RecoveryRun.start(COUPON_ID, coupon.getEpoch(), ADMIN_ID, false, NOW));
        run.markBlocked(NOW.minus(SETTLE).plusSeconds(3));
        when(recoveryStore.block(eq(COUPON_ID), anyLong())).thenReturn(new BlockResult(true, false, false));

        assertThatThrownBy(() -> useCase.fixBoundary(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED))
                .hasMessageContaining("3000ms");
        assertThat(run.getStep()).isEqualTo(RecoveryStep.BLOCKED);
        verifyNoInteractions(publisher, probe);
    }

    @Test
    void 경계_확정_때_접수가_다시_열려_있었으면_차단_시각을_지금으로_다시_잡고_거절한다() {
        RecoveryRun run = blockedRun();
        when(recoveryStore.block(eq(COUPON_ID), anyLong())).thenReturn(new BlockResult(true, false, true));

        assertThatThrownBy(() -> useCase.fixBoundary(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED));
        assertThat(run.getBlockedAt()).isEqualTo(NOW);
        verifyNoInteractions(publisher, probe);
    }

    @Test
    void 이미_막혀_있던_시작_단계_복구를_다시_시작하면_처음_차단_시각을_그대로_둔다() {
        RecoveryRun blocked = blockedRun();
        when(runRepository.findFirstByCouponIdAndStepNotIn(COUPON_ID, RecoveryStep.finished())).thenReturn(Optional.of(blocked));
        when(recoveryStore.block(eq(COUPON_ID), anyLong())).thenReturn(new BlockResult(true, false, false));

        useCase.start(ADMIN_ID, COUPON_ID);

        assertThat(blocked.getBlockedAt()).isEqualTo(NOW.minus(SETTLE));
    }

    @Test
    void 결과_확정은_재발행_대기와_경계까지의_Kafka_lag와_처리되지_않은_DLT를_센다() {
        RecoveryRun run = blockedRun();
        run.recordBoundary(new RecoveryBoundary(0, false, KAFKA_BOUNDARY, ""), NOW);
        givenSnapshot(8L, Map.of("req-a", record(1L, false)));
        when(probe.committedOffsets()).thenReturn(RecoveryOffsets.parse("0:8,1:0,2:4"));
        when(dltLogRepository.findUnresolvedRequestIds(COUPON_ID)).thenReturn(List.of("req-x"));

        var view = useCase.confirmResults(ADMIN_ID, COUPON_ID, RECOVERY_ID);

        assertThat(view.step()).isEqualTo(RecoveryStep.RESULTS_CHECKED);
        assertThat(view.unresolvedCount()).isEqualTo(4L);
        assertThat(view.unresolvedDetail()).isEqualTo("pendingRequests=1,kafkaLag=2,dltUnresolved=1");
    }

    @Test
    void 재구축_때_다시_센_미처리가_남으면_기록하고_Redis를_바꾸지_않는다() {
        RecoveryRun run = resultsCheckedRun();
        givenSnapshot(null, Map.of("req-a", record(1L, false)));

        assertThatThrownBy(() -> useCase.rebuild(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED));
        assertThat(run.getUnresolvedCount()).isEqualTo(1L);
        verify(recoveryStore, never()).rebuild(anyLong(), anyLong(), any(), any());
    }

    @Test
    void 같은_복구의_재구축이_진행_중이면_409이고_Redis를_바꾸지_않는다() {
        RecoveryRun run = resultsCheckedRun();
        run.claimRebuild(NOW.minusSeconds(1), Duration.ofMinutes(2));
        givenSnapshot(null, Map.of());

        assertThatThrownBy(() -> useCase.rebuild(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED))
                .hasMessageContaining("진행 중");
        verify(recoveryStore, never()).rebuild(anyLong(), anyLong(), any(), any());
    }

    @Test
    void 재구축_토큰이_맞지_않아_Redis가_거절하면_선점_전_단계로_되돌리고_409다() {
        RecoveryRun run = resultsCheckedRun();
        givenSnapshot(null, Map.of());
        when(recoveryStore.rebuild(anyLong(), anyLong(), any(), any())).thenReturn(RebuildResult.FENCED);

        assertThatThrownBy(() -> useCase.rebuild(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED));
        assertThat(run.getStep()).isEqualTo(RecoveryStep.RESULTS_CHECKED);
        assertThat(run.getClaimedAt()).isNull();
    }

    @Test
    void 재구축_결과를_알_수_없으면_재구축_단계에_남겨_취소하지_못하고_재구축만_다시_한다() {
        RecoveryRun run = resultsCheckedRun();
        givenSnapshot(null, Map.of());
        when(recoveryStore.rebuild(anyLong(), anyLong(), any(), any()))
                .thenThrow(new CouponIssueStoreUnavailableException("응답 없음", null))
                .thenReturn(RebuildResult.APPLIED);

        assertThatThrownBy(() -> useCase.rebuild(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOf(CouponIssueStoreUnavailableException.class);
        assertThat(run.getStep()).isEqualTo(RecoveryStep.REBUILDING);
        assertThatThrownBy(() -> useCase.abort(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED));

        assertThat(useCase.rebuild(ADMIN_ID, COUPON_ID, RECOVERY_ID).step()).isEqualTo(RecoveryStep.REBUILT);
    }

    @Test
    void 재구축_뒤_결과_확정을_요청해도_단계가_돌아가지_않아_취소할_수_없다() {
        RecoveryRun run = resultsCheckedRun();
        rebuilt(run);

        assertThatThrownBy(() -> useCase.confirmResults(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED));
        assertThatThrownBy(() -> useCase.abort(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED));
        assertThat(run.getStep()).isEqualTo(RecoveryStep.REBUILT);
    }

    @Test
    void 결과_확정_전에는_재구축하지_않는다() {
        blockedRun();

        assertThatThrownBy(() -> useCase.rebuild(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED));
        verifyNoInteractions(recoveryStore);
    }

    @Test
    void 미처리가_없으면_DB_확정_결과로_새_epoch를_재구축한다() {
        RecoveryRun run = resultsCheckedRun();
        ReflectionTestUtils.setField(coupon, "issuedCount", 1);
        givenSnapshot(null, Map.of());
        when(lookupRepository.findResults(COUPON_ID)).thenReturn(List.of(
                new RecoveryIssueResult("req-a", MEMBER_ID, CouponIssueResult.ISSUED, NOW)));
        when(recoveryStore.rebuild(anyLong(), anyLong(), any(), any())).thenAnswer(invocation -> {
            // Redis를 바꾸기 전에 선점이 커밋돼 있다
            assertThat(run.getStep()).isEqualTo(RecoveryStep.REBUILDING);
            return RebuildResult.APPLIED;
        });

        var view = useCase.rebuild(ADMIN_ID, COUPON_ID, RECOVERY_ID);

        ArgumentCaptor<RecoveryRebuildPlan> plan = ArgumentCaptor.forClass(RecoveryRebuildPlan.class);
        ArgumentCaptor<RebuildControl> control = ArgumentCaptor.forClass(RebuildControl.class);
        verify(recoveryStore).rebuild(eq(COUPON_ID), eq(RECOVERY_ID), plan.capture(), control.capture());
        assertThat(plan.getValue().newEpoch()).isEqualTo(run.getNewEpoch());
        assertThat(plan.getValue().stock()).isEqualTo(coupon.getQuantity() - 1L);
        assertThat(plan.getValue().memberIds()).containsExactly(MEMBER_ID);
        assertThat(control.getValue().status()).isEqualTo("OPEN");
        assertThat(control.getValue().fence()).isEqualTo(coupon.getVersion());
        assertThat(view.step()).isEqualTo(RecoveryStep.REBUILT);
    }

    @Test
    void 재개_전_수량_대사가_맞지_않으면_epoch를_올리지_않는다() {
        RecoveryRun run = resultsCheckedRun();
        rebuilt(run);
        givenSnapshot(coupon.getQuantity() - 1L, Map.of());

        assertThatThrownBy(() -> useCase.resume(ADMIN_ID, COUPON_ID, RECOVERY_ID, "확인함"))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED));
        assertThat(coupon.getEpoch()).isEqualTo(Coupon.INITIAL_EPOCH);
        verify(recoveryStore, never()).activate(anyLong(), anyLong(), anyLong(), any(), anyLong());
        // Redis를 쓰기 전에 거절했으므로 재개 선점을 되돌린다
        assertThat(run.getStep()).isEqualTo(RecoveryStep.REBUILT);
        assertThat(run.getClaimedAt()).isNull();
    }

    @Test
    void 재구축_뒤_Redis_프로세스가_바뀌었으면_재개하지_않는다() {
        RecoveryRun run = resultsCheckedRun();
        rebuilt(run);
        when(recoveryStore.snapshot(COUPON_ID)).thenReturn(new Snapshot(true, true, (long) coupon.getQuantity(), Map.of()));

        assertThatThrownBy(() -> useCase.resume(ADMIN_ID, COUPON_ID, RECOVERY_ID, "확인함"))
                .isInstanceOf(CouponException.class)
                .hasMessageContaining("재구축부터");
        verify(recoveryStore, never()).activate(anyLong(), anyLong(), anyLong(), any(), anyLong());
        assertThat(run.getStep()).isEqualTo(RecoveryStep.REBUILT);
    }

    @Test
    void 수량_대사가_맞으면_DB_epoch를_올리고_그_version을_fence로_접수를_연다() {
        RecoveryRun run = resultsCheckedRun();
        rebuilt(run);
        givenSnapshot((long) coupon.getQuantity(), Map.of());
        when(recoveryStore.activate(anyLong(), anyLong(), anyLong(), any(), anyLong())).thenReturn(ActivateResult.APPLIED);

        var view = useCase.resume(ADMIN_ID, COUPON_ID, RECOVERY_ID, "유실 접수 없음 확인");

        assertThat(coupon.getEpoch()).isEqualTo(2L);
        verify(recoveryStore).activate(COUPON_ID, RECOVERY_ID, 2L, "OPEN", coupon.getVersion());
        assertThat(view.step()).isEqualTo(RecoveryStep.RESUMED);
        assertThat(view.resumeBasis()).isEqualTo("유실 접수 없음 확인");
        assertThat(view.resumeEvidence()).contains("quantity=100", "redisStock=100", "inProgress=0", "activate=APPLIED");
    }

    @Test
    void 재개가_단계_검사를_통과한_뒤_재구축이_먼저_선점하면_재개는_409이고_Redis를_열지_않는다() {
        RecoveryRun run = rebuiltRun();
        givenSnapshot((long) coupon.getQuantity(), Map.of());
        // 재개 A의 단계 검사(findByIdAndCouponId) 뒤, A의 선점 트랜잭션이 다시 읽기 전에 재구축 B가 선점을 커밋한다
        when(runRepository.findById(RECOVERY_ID)).thenAnswer(invocation -> {
            if (run.getStep() == RecoveryStep.REBUILT) {
                run.claimRebuild(NOW, LEASE);
            }
            return Optional.of(run);
        });

        assertThatThrownBy(() -> useCase.resume(ADMIN_ID, COUPON_ID, RECOVERY_ID, "확인함"))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED));

        verify(recoveryStore, never()).activate(anyLong(), anyLong(), anyLong(), any(), anyLong());
        assertThat(coupon.getEpoch()).isEqualTo(Coupon.INITIAL_EPOCH);
        assertThat(run.getStep()).isEqualTo(RecoveryStep.REBUILDING);
    }

    @Test
    void 재개가_먼저_선점하면_그_사이_재구축은_409이고_Redis를_다시_만들지_않는다() {
        RecoveryRun run = rebuiltRun();
        givenSnapshot((long) coupon.getQuantity(), Map.of());
        when(recoveryStore.activate(anyLong(), anyLong(), anyLong(), any(), anyLong())).thenAnswer(invocation -> {
            // Redis를 열기 전에 재개 선점이 커밋돼 있고, 그 사이 들어온 재구축은 거절된다
            assertThat(run.getStep()).isEqualTo(RecoveryStep.RESUMING);
            assertThatThrownBy(() -> useCase.rebuild(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                    .isInstanceOfSatisfying(CouponException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED));
            return ActivateResult.APPLIED;
        });

        assertThat(useCase.resume(ADMIN_ID, COUPON_ID, RECOVERY_ID, "확인함").step()).isEqualTo(RecoveryStep.RESUMED);
        verify(recoveryStore, never()).rebuild(anyLong(), anyLong(), any(), any());
        assertThat(run.getClaimedAt()).isNull();
    }

    @Test
    void 같은_복구의_재개가_진행_중이면_409이고_Redis를_열지_않는다() {
        RecoveryRun run = rebuiltRun();
        run.claimResume("확인함", NOW.minusSeconds(1), LEASE);

        assertThatThrownBy(() -> useCase.resume(ADMIN_ID, COUPON_ID, RECOVERY_ID, "확인함"))
                .isInstanceOf(CouponException.class)
                .hasMessageContaining("진행 중");
        verify(recoveryStore, never()).activate(anyLong(), anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void lease가_지난_버려진_재개_선점은_다시_선점해_재개한다() {
        RecoveryRun run = rebuiltRun();
        run.claimResume("확인함", NOW.minus(LEASE), LEASE);
        givenSnapshot((long) coupon.getQuantity(), Map.of());
        when(recoveryStore.activate(anyLong(), anyLong(), anyLong(), any(), anyLong())).thenReturn(ActivateResult.APPLIED);

        assertThat(useCase.resume(ADMIN_ID, COUPON_ID, RECOVERY_ID, "확인함").step()).isEqualTo(RecoveryStep.RESUMED);
    }

    @Test
    void 재개_결과를_알_수_없으면_재개_단계에_남아_재구축_취소는_409이고_재개를_다시_하면_끝난다() {
        RecoveryRun run = rebuiltRun();
        givenSnapshot((long) coupon.getQuantity(), Map.of());
        when(recoveryStore.activate(anyLong(), anyLong(), anyLong(), any(), anyLong()))
                .thenThrow(new CouponIssueStoreUnavailableException("응답 없음", null))
                // 앞선 시도가 이미 열었으면 Lua는 같은 epoch·recoveryId라 연 결과로 답한다
                .thenReturn(ActivateResult.STATUS_SUPERSEDED);

        assertThatThrownBy(() -> useCase.resume(ADMIN_ID, COUPON_ID, RECOVERY_ID, "확인함"))
                .isInstanceOf(CouponIssueStoreUnavailableException.class);
        assertThat(run.getStep()).isEqualTo(RecoveryStep.RESUMING);
        assertThat(run.getClaimedAt()).isNull();
        // Redis 기록이 그대로이므로 취소도 409다
        when(recoveryStore.redisState(COUPON_ID)).thenReturn(RecoveryRedisState.INTACT);
        assertThatThrownBy(() -> useCase.rebuild(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED));
        assertThatThrownBy(() -> useCase.abort(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED));

        var view = useCase.resume(ADMIN_ID, COUPON_ID, RECOVERY_ID, "확인함");

        assertThat(view.step()).isEqualTo(RecoveryStep.RESUMED);
        assertThat(view.resumeEvidence()).contains("activate=STATUS_SUPERSEDED");
        verify(recoveryStore, never()).rebuild(anyLong(), anyLong(), any(), any());
    }

    @Test
    void Redis가_열지_않았다고_답하면_재구축_뒤로_되돌려_재구축을_다시_할_수_있다() {
        RecoveryRun run = rebuiltRun();
        givenSnapshot((long) coupon.getQuantity(), Map.of());
        when(recoveryStore.activate(anyLong(), anyLong(), anyLong(), any(), anyLong()))
                .thenReturn(ActivateResult.EPOCH_MISMATCH);

        assertThatThrownBy(() -> useCase.resume(ADMIN_ID, COUPON_ID, RECOVERY_ID, "확인함"))
                .isInstanceOf(CouponException.class)
                .hasMessageContaining("재구축부터");

        assertThat(run.getStep()).isEqualTo(RecoveryStep.REBUILT);
        assertThat(run.getClaimedAt()).isNull();
    }

    @Test
    void 다른_복구가_막은_Redis는_열지_않고_409다() {
        RecoveryRun run = rebuiltRun();
        givenSnapshot((long) coupon.getQuantity(), Map.of());
        when(recoveryStore.activate(anyLong(), anyLong(), anyLong(), any(), anyLong())).thenReturn(ActivateResult.FENCED);

        assertThatThrownBy(() -> useCase.resume(ADMIN_ID, COUPON_ID, RECOVERY_ID, "확인함"))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED))
                .hasMessageContaining("다른 복구");
        assertThat(run.getStep()).isEqualTo(RecoveryStep.REBUILT);
    }

    @Test
    void 재개_결과를_모른_채_Redis가_유실되면_취소하고_새_복구가_유실_의심을_이어받아_시작한다() {
        RecoveryRun run = rebuiltRun();
        givenSnapshot((long) coupon.getQuantity(), Map.of());
        when(recoveryStore.activate(anyLong(), anyLong(), anyLong(), any(), anyLong()))
                .thenThrow(new CouponIssueStoreUnavailableException("응답 없음", null));
        assertThatThrownBy(() -> useCase.resume(ADMIN_ID, COUPON_ID, RECOVERY_ID, "확인함"))
                .isInstanceOf(CouponIssueStoreUnavailableException.class);
        assertThat(run.getStep()).isEqualTo(RecoveryStep.RESUMING);

        // Redis가 그대로면 취소하지 않고 재개만 다시 한다
        when(recoveryStore.redisState(COUPON_ID)).thenReturn(RecoveryRedisState.INTACT);
        assertThatThrownBy(() -> useCase.abort(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED));
        assertThat(run.getStep()).isEqualTo(RecoveryStep.RESUMING);

        // Redis가 그대로면 접수 차단도 다시 쓰지 않는다(재개를 다시 하면 된다)
        verify(recoveryStore, never()).block(anyLong(), anyLong());

        // 그 뒤 Redis가 유실됐다(issue-control 없음). DB를 바꾸기 전에 이 복구 ID로 차단을 다시 쓴다
        when(recoveryStore.redisState(COUPON_ID)).thenReturn(RecoveryRedisState.CONTROL_MISSING);
        when(recoveryStore.block(COUPON_ID, RECOVERY_ID)).thenAnswer(invocation -> {
            assertThat(run.getStep()).isEqualTo(RecoveryStep.RESUMING);
            return new BlockResult(false, false, false);
        });
        var aborted = useCase.abort(ADMIN_ID, COUPON_ID, RECOVERY_ID);

        assertThat(aborted.step()).isEqualTo(RecoveryStep.ABORTED);
        assertThat(aborted.requestsLost()).isTrue();
        assertThat(aborted.abortReason()).isEqualTo("RESUME_UNKNOWN_CONTROL_MISSING");
        verify(recoveryStore, times(1)).block(COUPON_ID, RECOVERY_ID);

        when(runRepository.findFirstByCouponIdAndStepNotIn(COUPON_ID, RecoveryStep.finished())).thenReturn(Optional.empty());
        when(runRepository.findFirstByCouponIdOrderByIdDesc(COUPON_ID)).thenReturn(Optional.of(run));
        when(recoveryStore.block(eq(COUPON_ID), anyLong())).thenReturn(new BlockResult(false, false, false));
        // 새 복구를 저장한 뒤의 트랜잭션은 새 복구를 다시 읽는다
        when(runRepository.findById(RECOVERY_ID)).thenAnswer(invocation -> Optional.ofNullable(savedRun));
        ArgumentCaptor<RecoveryRun> saved = ArgumentCaptor.forClass(RecoveryRun.class);

        var next = useCase.start(ADMIN_ID, COUPON_ID);

        verify(runRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue()).isNotSameAs(run);
        assertThat(next.step()).isEqualTo(RecoveryStep.BLOCKED);
        assertThat(next.requestsLost()).isTrue();
    }

    /** 재개 결과를 모르는 채 RESUMING에 남은 복구 */
    private RecoveryRun resumingUnknownRun() {
        RecoveryRun run = rebuiltRun();
        run.abandonClaim(run.claimResume("확인함", NOW.minusSeconds(10), LEASE), NOW.minusSeconds(10));
        return run;
    }

    @Test
    void 재개_중_유실_취소는_차단을_확인하지_못하면_503이고_취소하지_않는다() {
        RecoveryRun run = resumingUnknownRun();
        when(recoveryStore.redisState(COUPON_ID)).thenReturn(RecoveryRedisState.PROCESS_CHANGED);
        when(recoveryStore.block(COUPON_ID, RECOVERY_ID)).thenThrow(new CouponIssueStoreUnavailableException("응답 없음", null));

        assertThatThrownBy(() -> useCase.abort(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOf(CouponIssueStoreUnavailableException.class);

        assertThat(run.getStep()).isEqualTo(RecoveryStep.RESUMING);
        assertThat(run.getAbortedAt()).isNull();
    }

    @Test
    void 재개_중_유실_취소는_차단으로_다시_본_상태로_판단하고_재개가_진행_중이면_Redis를_건드리지_않는다() {
        RecoveryRun run = resumingUnknownRun();
        // 읽을 때는 프로세스가 바뀌었다고 봤지만 차단 결과가 그대로라고 답하면(그 사이 바뀜) 취소하지 않는다
        when(recoveryStore.redisState(COUPON_ID)).thenReturn(RecoveryRedisState.PROCESS_CHANGED);
        when(recoveryStore.block(COUPON_ID, RECOVERY_ID)).thenReturn(new BlockResult(true, false, true));
        assertThatThrownBy(() -> useCase.abort(ADMIN_ID, COUPON_ID, RECOVERY_ID)).isInstanceOf(CouponException.class);
        assertThat(run.getStep()).isEqualTo(RecoveryStep.RESUMING);

        RecoveryRun claimed = rebuiltRun();
        claimed.claimResume("확인함", NOW.minusSeconds(1), LEASE);
        clearInvocations(recoveryStore);
        assertThatThrownBy(() -> useCase.abort(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOf(CouponException.class)
                .hasMessageContaining("진행 중");
        verifyNoInteractions(recoveryStore);
    }

    @Test
    void 더_새_복구가_issue_control을_가진_뒤_늦게_도착한_차단은_409이고_진행하지_않는다() {
        RecoveryRun run = blockedRun();
        when(recoveryStore.block(COUPON_ID, RECOVERY_ID)).thenReturn(new BlockResult(true, false, false, true));

        assertThatThrownBy(() -> useCase.fixBoundary(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED))
                .hasMessageContaining("더 새 복구");
        assertThat(run.getStep()).isEqualTo(RecoveryStep.BLOCKED);
        verifyNoInteractions(publisher, probe);
    }

    @Test
    void 재구축_전_취소는_복구를_ABORTED로_남기고_Redis를_건드리지_않아_접수_차단이_유지된다() {
        RecoveryRun run = resultsCheckedRun();

        var view = useCase.abort(ADMIN_ID, COUPON_ID, RECOVERY_ID);

        assertThat(view.step()).isEqualTo(RecoveryStep.ABORTED);
        assertThat(view.abortedBy()).isEqualTo(ADMIN_ID);
        assertThat(run.getAbortedAt()).isEqualTo(NOW);
        verifyNoInteractions(recoveryStore, publisher, probe);
    }

    @Test
    void 재구축_뒤_취소는_409이고_복구를_바꾸지_않는다() {
        RecoveryRun run = resultsCheckedRun();
        rebuilt(run);

        assertThatThrownBy(() -> useCase.abort(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED));
        assertThat(run.getStep()).isEqualTo(RecoveryStep.REBUILT);
    }

    @Test
    void 이미_취소한_복구를_다시_취소하면_첫_취소_기록_그대로_돌려준다() {
        RecoveryRun run = blockedRun();
        run.abort(ADMIN_ID, NOW.minusSeconds(60));

        var view = useCase.abort(ADMIN_ID + 1, COUPON_ID, RECOVERY_ID);

        assertThat(view.step()).isEqualTo(RecoveryStep.ABORTED);
        assertThat(view.abortedBy()).isEqualTo(ADMIN_ID);
        assertThat(view.abortedAt()).isEqualTo(NOW.minusSeconds(60));
        verify(runRepository, never()).findById(anyLong());
        verifyNoInteractions(transactionManager, recoveryStore);
    }

    @Test
    void 재개한_복구는_취소하지_못한다() {
        RecoveryRun run = resultsCheckedRun();
        rebuilt(run);
        resumed(run);

        assertThatThrownBy(() -> useCase.abort(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_STEP_NOT_ALLOWED));
        assertThat(run.getStep()).isEqualTo(RecoveryStep.RESUMED);
    }

    @Test
    void 없는_복구는_취소하지_못한다() {
        when(runRepository.findByIdAndCouponId(RECOVERY_ID, COUPON_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.abort(ADMIN_ID, COUPON_ID, RECOVERY_ID))
                .isInstanceOfSatisfying(CouponException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_RECOVERY_NOT_FOUND));
    }

    @Test
    void 유실_의심인_채_취소한_복구_뒤에는_새_복구를_시작하고_유실_의심을_이어받는다() {
        RecoveryRun aborted = withRunId(RecoveryRun.start(COUPON_ID, coupon.getEpoch(), ADMIN_ID, true, NOW));
        aborted.abort(ADMIN_ID, NOW);
        when(runRepository.findFirstByCouponIdAndStepNotIn(COUPON_ID, RecoveryStep.finished())).thenReturn(Optional.empty());
        when(runRepository.findFirstByCouponIdOrderByIdDesc(COUPON_ID)).thenReturn(Optional.of(aborted));
        when(recoveryStore.block(eq(COUPON_ID), anyLong())).thenReturn(new BlockResult(true, false, false));
        ArgumentCaptor<RecoveryRun> saved = ArgumentCaptor.forClass(RecoveryRun.class);

        var view = useCase.start(ADMIN_ID, COUPON_ID);

        verify(runRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue()).isNotSameAs(aborted);
        assertThat(view.step()).isEqualTo(RecoveryStep.BLOCKED);
        assertThat(view.requestsLost()).isTrue();
        verify(recoveryStore).block(COUPON_ID, RECOVERY_ID);
    }

    @Test
    void 끝까지_재개한_복구_뒤의_새_복구는_유실_의심을_이어받지_않는다() {
        RecoveryRun resumed = withRunId(RecoveryRun.start(COUPON_ID, coupon.getEpoch(), ADMIN_ID, true, NOW));
        resumed.recordBoundary(new RecoveryBoundary(0, true, KAFKA_BOUNDARY, ""), NOW);
        resumed.recordResults(RecoveryUnresolved.none(), NOW);
        rebuilt(resumed);
        resumed(resumed);
        when(runRepository.findFirstByCouponIdAndStepNotIn(COUPON_ID, RecoveryStep.finished())).thenReturn(Optional.empty());
        when(runRepository.findFirstByCouponIdOrderByIdDesc(COUPON_ID)).thenReturn(Optional.of(resumed));
        when(recoveryStore.block(eq(COUPON_ID), anyLong())).thenReturn(new BlockResult(true, false, false));

        assertThat(useCase.start(ADMIN_ID, COUPON_ID).requestsLost()).isFalse();
    }
}

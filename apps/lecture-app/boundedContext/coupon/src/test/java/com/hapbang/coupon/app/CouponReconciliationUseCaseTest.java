package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.*;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.app.port.CouponReconciliationStore;
import com.hapbang.coupon.app.port.CouponReconciliationStore.Snapshot;
import com.hapbang.coupon.config.CouponIssueDeliveryProperties;
import com.hapbang.coupon.config.CouponLimitsProperties;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponIssueResult;
import com.hapbang.coupon.domain.IssueHoldReason;
import com.hapbang.coupon.domain.IssueReconciliationRule.Mismatch;
import com.hapbang.coupon.domain.IssueRequestRecord;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.IssueCapacityReservationRepository;
import com.hapbang.coupon.out.IssueResultReadRepository;
import com.hapbang.coupon.out.IssueResultRow;
import com.hapbang.coupon.out.redis.CouponRedisProperties;

import io.micrometer.core.instrument.MeterRegistry;

// 대사 회차의 분기: 일치(정리·적체 보류·해제), 재확인 뒤 불일치(보류만), 전체 정리(용량 해제), 고아 예약, 복구 중 건너뛰기, 관리자 보류 해제,
// DB 미확정 기록이 남은 쿠폰의 key 만료 연장.
// 수량 식 자체는 도메인 테스트가 본다
class CouponReconciliationUseCaseTest {

    private static final int QUANTITY = 100;
    private static final long EPOCH = 1;

    private final IssueCapacityReservationRepository reservationRepository = mock();
    private final CouponRepository couponRepository = mock();
    private final IssueResultReadRepository resultRepository = mock();
    private final CouponReconciliationStore store = mock();
    private final CouponIssueStore issueStore = mock();
    private final CouponCapacitySupport capacitySupport = mock();
    private final PlatformTransactionManager transactionManager = mock();
    private final CouponReconciliationMetrics metrics =
            new CouponReconciliationMetrics(new DefaultListableBeanFactory().getBeanProvider(MeterRegistry.class));

    private final Map<String, String> requests = new HashMap<>();
    private final Set<Long> users = new HashSet<>();
    private CouponReconciliationUseCase useCase;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(reservationRepository.findCouponIds()).thenReturn(List.of(COUPON_ID));
        when(store.hold(anyLong(), any())).thenReturn(true);
        when(store.releaseHold(anyLong(), any())).thenReturn(true);
        when(store.cleanUp(anyLong())).thenReturn(true);
        when(capacitySupport.release(anyLong())).thenReturn(true);
        // 재발행 대기 임계치 1건
        useCase = new CouponReconciliationUseCase(reservationRepository, couponRepository, resultRepository, store,
                issueStore, new CouponRedisProperties(null), capacitySupport, metrics, new CouponLimitsProperties(null, null, 1, null, null),
                CouponIssueDeliveryProperties.defaults(), transactionManager, CLOCK);
    }

    @Test
    void 일치하면_DB_최종_결과가_확인된_요청만_지우고_재발행_대기가_임계치를_넘으면_적체_보류한다() {
        givenCoupon(openSellerCoupon());
        givenDb(new IssueResultRow("issued", 1L, CouponIssueResult.ISSUED, EPOCH));
        accepted("issued", 1, NOW);
        accepted("late-1", 2, NOW.minusSeconds(60));
        accepted("late-2", 3, NOW.minusSeconds(60));
        givenRedis(null, 97L);

        var summary = useCase.runScheduled();

        verify(store).removeRequests(COUPON_ID, List.of("issued"));
        verify(store).hold(COUPON_ID, IssueHoldReason.BACKLOG);
        verify(store, never()).hold(COUPON_ID, IssueHoldReason.RECONCILE);
        verify(capacitySupport, never()).release(anyLong());
        assertThat(summary.backlogHolds()).isEqualTo(1);
        assertThat(metrics.backlogHolds()).isEqualTo(1);
    }

    @Test
    void 적체_보류_중_재발행_대기가_해제_기준_이하로_줄면_보류를_푼다() {
        // 임계치 1건 × 해제 비율 0.8 = 해제 기준 0건
        givenCoupon(openSellerCoupon());
        givenDb();
        accepted("fresh", 2, NOW);
        givenRedis(IssueHoldReason.BACKLOG, 99L);

        useCase.runScheduled();

        verify(store).releaseHold(COUPON_ID, IssueHoldReason.BACKLOG);
        assertThat(metrics.backlogHolds()).isZero();
    }

    @Test
    void 적체_보류_중_재발행_대기가_임계치_이하여도_해제_기준보다_많으면_보류를_유지한다() {
        givenCoupon(openSellerCoupon());
        givenDb();
        accepted("late", 2, NOW.minusSeconds(60));
        givenRedis(IssueHoldReason.BACKLOG, 99L);

        var summary = useCase.runScheduled();

        verify(store, never()).releaseHold(anyLong(), any());
        assertThat(summary.backlogHolds()).isEqualTo(1);
    }

    @Test
    void 접수_중인_쿠폰은_이전_epoch_기록도_ISSUED만_지우고_REJECTED와_결과가_없는_기록은_남긴다() {
        givenCoupon(openSellerCoupon());
        givenDb(new IssueResultRow("old-issued", 1L, CouponIssueResult.ISSUED, EPOCH),
                new IssueResultRow("old-rejected", 2L, CouponIssueResult.REJECTED, EPOCH));
        requests.put("old-issued", record(1, EPOCH));
        requests.put("old-rejected", record(2, EPOCH));      // 보상 표식 없음
        requests.put("old-unresolved", record(3, EPOCH));    // DB 결과 없음
        users.add(1L);
        when(store.snapshot(COUPON_ID)).thenReturn(
                new Snapshot(true, true, "OPEN", EPOCH + 1, null, 99L, false, users, requests));

        var summary = useCase.runScheduled();

        verify(store).removeRequests(COUPON_ID, List.of("old-issued"));
        verify(store, never()).hold(COUPON_ID, IssueHoldReason.RECONCILE);
        assertThat(summary.mismatched()).isZero();
    }

    @Test
    void 접수_중인_쿠폰의_보상을_마친_거절_기록은_지우지_않는다() {
        givenCoupon(openSellerCoupon());
        givenDb(new IssueResultRow("compensated", 1L, CouponIssueResult.REJECTED, EPOCH));
        requests.put("compensated", new IssueRequestRecord(1, NOW.minusSeconds(60), NOW.plus(Duration.ofDays(30)),
                EPOCH, true, true).format());
        givenRedis(null, 100L);

        var summary = useCase.runScheduled();

        verify(store, never()).removeRequests(anyLong(), anyList());
        assertThat(summary.mismatched()).isZero();
    }

    @Test
    void 발급_종료된_쿠폰은_보상을_마친_거절과_DB_결과가_있는_이전_epoch_기록도_지운다() {
        Coupon closed = openSellerCoupon();
        closed.close(NOW);
        givenCoupon(closed);
        givenDb(new IssueResultRow("compensated", 1L, CouponIssueResult.REJECTED, EPOCH + 1),
                new IssueResultRow("old-rejected", 2L, CouponIssueResult.REJECTED, EPOCH));
        requests.put("compensated", new IssueRequestRecord(1, NOW.minusSeconds(60), NOW.plus(Duration.ofDays(30)),
                EPOCH + 1, true, true).format());
        requests.put("old-rejected", record(2, EPOCH));
        when(store.snapshot(COUPON_ID)).thenReturn(
                new Snapshot(true, true, "CLOSED", EPOCH + 1, null, 100L, false, users, requests));

        var summary = useCase.runScheduled();

        verify(store).removeRequests(eq(COUPON_ID), argThat(ids -> ids.size() == 2
                && ids.containsAll(List.of("compensated", "old-rejected"))));
        verify(capacitySupport).release(COUPON_ID);
        assertThat(summary.released()).isEqualTo(1);
    }

    @Test
    void 열기_전_쿠폰에_Redis_key가_하나도_없으면_대기_시간이_지난_예약만_고아로_푼다() {
        givenCoupon(reviewSellerCoupon());
        givenDb();
        when(store.snapshot(COUPON_ID)).thenReturn(emptySnapshot());
        when(capacitySupport.releaseOrphan(anyLong(), any())).thenReturn(true);

        var summary = useCase.runScheduled();

        verify(capacitySupport).releaseOrphan(COUPON_ID, NOW.minus(Duration.ofMinutes(10)));
        verify(capacitySupport, never()).release(anyLong());
        verify(store, never()).cleanUp(anyLong());
        assertThat(summary.released()).isEqualTo(1);
    }

    @Test
    void 심사_거절된_쿠폰에_Redis_key가_없으면_기다리지_않고_예약을_푼다() {
        Coupon rejected = reviewSellerCoupon();
        rejected.reject("사유", NOW);
        givenCoupon(rejected);
        givenDb();
        when(store.snapshot(COUPON_ID)).thenReturn(emptySnapshot());

        useCase.runScheduled();

        verify(capacitySupport).release(COUPON_ID);
        verify(capacitySupport, never()).releaseOrphan(anyLong(), any());
    }

    @Test
    void 열기_전_쿠폰에_Redis_key가_남았으면_예약을_풀지_않는다() {
        givenCoupon(reviewSellerCoupon());
        givenDb();
        // 준비만 되고 issue-control이 없는 상태(재고 key만 남음)
        when(store.snapshot(COUPON_ID)).thenReturn(
                new Snapshot(false, false, null, null, null, 100L, false, Set.of(), Map.of()));

        var summary = useCase.runScheduled();

        verify(capacitySupport, never()).release(anyLong());
        verify(capacitySupport, never()).releaseOrphan(anyLong(), any());
        assertThat(summary.skipped()).isEqualTo(1);
    }

    @Test
    void 발급_중인_쿠폰은_Redis_key가_없어도_예약을_풀지_않는다() {
        givenCoupon(openSellerCoupon());
        givenDb();
        when(store.snapshot(COUPON_ID)).thenReturn(emptySnapshot());

        var summary = useCase.runScheduled();

        verify(capacitySupport, never()).release(anyLong());
        verify(capacitySupport, never()).releaseOrphan(anyLong(), any());
        assertThat(summary.skipped()).isEqualTo(1);
    }

    @Test
    void 대사_불일치_보류를_풀면_released다() {
        when(couponRepository.existsById(COUPON_ID)).thenReturn(true);

        var view = useCase.releaseReconcileHold(ADMIN_ID, COUPON_ID);

        verify(store).releaseHold(COUPON_ID, IssueHoldReason.RECONCILE);
        assertThat(view.released()).isTrue();
        assertThat(view.hold()).isNull();
    }

    @Test
    void 풀_보류가_없으면_바꾸지_않고_released_false다() {
        when(couponRepository.existsById(COUPON_ID)).thenReturn(true);
        when(store.releaseHold(COUPON_ID, IssueHoldReason.RECONCILE)).thenReturn(false);
        when(store.readHold(COUPON_ID)).thenReturn(null);

        var view = useCase.releaseReconcileHold(ADMIN_ID, COUPON_ID);

        assertThat(view.released()).isFalse();
        assertThat(view.hold()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = IssueHoldReason.class, names = {"BACKLOG", "RECOVERY"})
    void 다른_사유의_보류는_풀지_않고_409다(IssueHoldReason other) {
        when(couponRepository.existsById(COUPON_ID)).thenReturn(true);
        when(store.releaseHold(COUPON_ID, IssueHoldReason.RECONCILE)).thenReturn(false);
        when(store.readHold(COUPON_ID)).thenReturn(other);

        assertThatThrownBy(() -> useCase.releaseReconcileHold(ADMIN_ID, COUPON_ID))
                .isInstanceOfSatisfying(CouponException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(CouponErrorCode.COUPON_RECONCILE_HOLD_NOT_RELEASABLE));
    }

    @Test
    void 없는_쿠폰의_보류_해제는_404이고_Redis를_보지_않는다() {
        when(couponRepository.existsById(COUPON_ID)).thenReturn(false);

        assertThatThrownBy(() -> useCase.releaseReconcileHold(ADMIN_ID, COUPON_ID))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_NOT_FOUND));
        verifyNoInteractions(store);
    }

    @Test
    void 다시_읽어도_맞지_않으면_접수를_RECONCILE로_보류하고_기록을_고치거나_지우지_않는다() {
        givenCoupon(openSellerCoupon());
        givenDb(new IssueResultRow("issued", 1L, CouponIssueResult.ISSUED, EPOCH));
        accepted("issued", 1, NOW);
        givenRedis(null, 100L); // 발급했는데 재고가 그대로다

        var summary = useCase.runScheduled();

        verify(store, times(2)).snapshot(COUPON_ID);
        verify(store).hold(COUPON_ID, IssueHoldReason.RECONCILE);
        verify(store, never()).removeRequests(anyLong(), anyList());
        verify(store, never()).repair(anyLong(), any(), anyList());
        verify(store, never()).cleanUp(anyLong());
        assertThat(summary.mismatched()).isEqualTo(1);
        assertThat(metrics.mismatches(Mismatch.UNEXPLAINED)).isEqualTo(1);
    }

    @Test
    void 처음_읽기만_맞지_않고_다시_읽어_맞으면_보류하지_않는다() {
        givenCoupon(openSellerCoupon());
        givenDb();
        accepted("r1", 1, NOW);
        Snapshot stale = snapshot(null, 100L);  // 접수 직후 재고를 읽기 전의 값처럼 어긋난 첫 읽기
        Snapshot fresh = snapshot(null, 99L);
        when(store.snapshot(COUPON_ID)).thenReturn(stale, fresh);

        var summary = useCase.runScheduled();

        verify(store, never()).hold(COUPON_ID, IssueHoldReason.RECONCILE);
        assertThat(summary.mismatched()).isZero();
        assertThat(metrics.mismatches(Mismatch.UNEXPLAINED)).isZero();
    }

    @Test
    void 발급_종료된_쿠폰의_기록이_모두_정리되면_key를_지운_뒤_용량_예약을_푼다() {
        Coupon closed = openSellerCoupon();
        closed.close(NOW);
        givenCoupon(closed);
        givenDb(new IssueResultRow("issued", 1L, CouponIssueResult.ISSUED, EPOCH));
        accepted("issued", 1, NOW);
        givenRedis(null, 99L);

        var summary = useCase.runScheduled();

        InOrder order = inOrder(store, capacitySupport);
        order.verify(store).removeRequests(COUPON_ID, List.of("issued"));
        order.verify(store).cleanUp(COUPON_ID);
        order.verify(capacitySupport).release(COUPON_ID);
        assertThat(summary.released()).isEqualTo(1);
    }

    @Test
    void 진행_중_접수가_남은_발급_종료_쿠폰은_용량_예약을_풀지_않는다() {
        Coupon closed = openSellerCoupon();
        closed.close(NOW);
        givenCoupon(closed);
        givenDb();
        accepted("pending", 1, NOW);
        givenRedis(null, 99L);

        useCase.runScheduled();

        verify(store, never()).cleanUp(anyLong());
        verify(capacitySupport, never()).release(anyLong());
    }

    @Test
    void 복구_중인_쿠폰은_대사하지_않는다() {
        givenCoupon(openSellerCoupon());
        givenDb();
        givenRedis(IssueHoldReason.RECOVERY, 1L);

        var summary = useCase.runScheduled();

        verify(store, times(1)).snapshot(COUPON_ID);
        verifyNoMoreInteractions(store);
        assertThat(summary.skipped()).isEqualTo(1);
    }

    @Test
    void 한_쿠폰의_예외는_다른_쿠폰의_대사를_막지_않는다() {
        long otherId = 2L;
        when(reservationRepository.findCouponIds()).thenReturn(List.of(otherId, COUPON_ID));
        when(couponRepository.findById(otherId)).thenThrow(new IllegalStateException("DB 오류"));
        givenCoupon(openSellerCoupon());
        givenDb();
        givenRedis(null, 100L);

        var summary = useCase.runScheduled();

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.coupons()).isEqualTo(2);
        verify(store).snapshot(COUPON_ID);
    }

    @Test
    void DB_결과가_없는_접수_기록이_남으면_key_만료를_지금_더하기_여유까지_늦춘다() {
        Coupon closed = openSellerCoupon();
        closed.close(NOW);
        givenCoupon(closed);
        givenDb();
        accepted("pending", 1, NOW.minusSeconds(60));
        when(store.snapshot(COUPON_ID)).thenReturn(
                new Snapshot(true, true, "CLOSED", EPOCH, null, 99L, false, users, requests));

        useCase.runScheduled();

        verify(issueStore).extendKeysExpiry(COUPON_ID, NOW.plus(Duration.ofDays(7)));
        verify(store, never()).cleanUp(anyLong());
    }

    @Test
    void 불일치로_보류한_쿠폰도_DB_결과가_없는_기록이_있으면_key_만료를_늦춘다() {
        givenCoupon(openSellerCoupon());
        givenDb();
        accepted("pending", 1, NOW);
        givenRedis(null, 100L); // 진행 중 1건인데 재고가 그대로다

        var summary = useCase.runScheduled();

        assertThat(summary.mismatched()).isEqualTo(1);
        verify(issueStore).extendKeysExpiry(COUPON_ID, NOW.plus(Duration.ofDays(7)));
    }

    @Test
    void DB_결과가_모두_확인된_쿠폰은_key_만료를_건드리지_않는다() {
        Coupon closed = openSellerCoupon();
        closed.close(NOW);
        givenCoupon(closed);
        givenDb(new IssueResultRow("issued", 1L, CouponIssueResult.ISSUED, EPOCH));
        accepted("issued", 1, NOW);
        givenRedis(null, 99L);

        useCase.runScheduled();

        verifyNoInteractions(issueStore);
        verify(store).cleanUp(COUPON_ID);
    }

    @Test
    void key_만료_연장이_실패해도_대사의_나머지는_계속한다() {
        givenCoupon(openSellerCoupon());
        givenDb(new IssueResultRow("issued", 1L, CouponIssueResult.ISSUED, EPOCH));
        accepted("issued", 1, NOW);
        accepted("pending", 2, NOW);
        givenRedis(null, 98L);
        when(issueStore.extendKeysExpiry(anyLong(), any())).thenThrow(new CouponIssueStoreUnavailableException("redis", null));

        var summary = useCase.runScheduled();

        verify(store).removeRequests(COUPON_ID, List.of("issued"));
        assertThat(summary.failed()).isZero();
    }

    private void givenCoupon(Coupon coupon) {
        assertThat(coupon.getQuantity()).isEqualTo(QUANTITY);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
    }

    private void givenDb(IssueResultRow... rows) {
        when(resultRepository.findRowsByCouponId(COUPON_ID)).thenReturn(List.of(rows));
    }

    private void givenRedis(IssueHoldReason hold, Long stock) {
        when(store.snapshot(COUPON_ID)).thenReturn(snapshot(hold, stock));
    }

    private Snapshot snapshot(IssueHoldReason hold, Long stock) {
        return new Snapshot(true, true, "OPEN", EPOCH, hold, stock, false, users, requests);
    }

    private static Snapshot emptySnapshot() {
        return new Snapshot(false, false, null, null, null, null, false, Set.of(), Map.of());
    }

    private static String record(long memberId, long epoch) {
        return IssueRequestRecord.accepted(memberId, NOW.minusSeconds(60), NOW.plus(Duration.ofDays(30)), epoch)
                .format();
    }

    private void accepted(String requestId, long memberId, Instant acceptedAt) {
        requests.put(requestId, IssueRequestRecord.accepted(memberId, acceptedAt, acceptedAt.plus(Duration.ofDays(30)))
                .format());
        users.add(memberId);
    }
}

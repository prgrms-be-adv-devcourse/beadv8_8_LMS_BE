package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.*;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.coupon.app.port.PromotionBudget;
import com.hapbang.coupon.app.port.PromotionBudget.BudgetResult;
import com.hapbang.coupon.app.port.PromotionBudget.Outcome;
import com.hapbang.coupon.app.port.PromotionBudget.WorkMetadata;
import com.hapbang.coupon.config.CouponBudgetWorkProperties;
import com.hapbang.coupon.domain.BudgetWork;
import com.hapbang.coupon.domain.BudgetWorkKind;
import com.hapbang.coupon.domain.BudgetWorkStatus;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.out.BudgetWorkRepository;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.coupon.out.MemberCouponExpiryTarget;

/** 조회 결과 검증·선점 분기를 독립적으로 확인한다. 저장소의 실제 lease·CAS와 트랜잭션 격리는 검증하지 않는다. */
class CouponBudgetWorkUseCaseTest {
    private static final String RESERVE_WORK_ID = "coupon-1-reserve-v1";
    private static final String RESERVATION_ID = "PB-42";
    private static final long BUDGET = 100_000L;
    private static final long RELEASE_HP = 300L;
    private static final Duration LEASE = Duration.ofMinutes(1);

    private final BudgetWorkRepository works = mock();
    private final CouponRepository coupons = mock();
    private final MemberCouponRepository members = mock();
    private final CouponBudgetReleaseRecorder releaseRecorder = mock();
    private final PromotionBudget budget = mock();
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PromotionBudget> budgetProvider = mock();

    private CouponBudgetWorkUseCase useCase;
    private Coupon coupon;

    @BeforeEach
    void setUp() {
        coupon = draftPlatformCoupon();
        when(budgetProvider.getIfAvailable()).thenReturn(budget);
        useCase = new CouponBudgetWorkUseCase(works, coupons, members, releaseRecorder,
                budgetProvider, new CouponBudgetWorkProperties(null, LEASE, 100),
                CouponUsageFixture.transactionManager(), CLOCK);
    }

    @Test
    void 후보_정책별로_회원_cursor를_진행하고_실패한_회원도_뒤_정책의_등록을_막지_않는다() {
        useCase = new CouponBudgetWorkUseCase(works, coupons, members, releaseRecorder, budgetProvider,
                new CouponBudgetWorkProperties(null, LEASE, 2), CouponUsageFixture.transactionManager(), CLOCK);
        when(coupons.findPlatformMemberBudgetCouponIds(NOW, 0, Limit.of(2))).thenReturn(List.of(1L, 2L));
        when(coupons.findPlatformMemberBudgetCouponIds(NOW, 2, Limit.of(2))).thenReturn(List.of(3L));
        when(members.findExpiredPlatformBudgetTargets(1, NOW, null, 0, Limit.of(2))).thenReturn(List.of(new MemberCouponExpiryTarget(11, NOW), new MemberCouponExpiryTarget(12, NOW)));
        when(members.findExpiredPlatformBudgetTargets(1, NOW, NOW, 12, Limit.of(2))).thenReturn(List.of(new MemberCouponExpiryTarget(13, NOW)));
        when(members.findExpiredPlatformBudgetTargets(2, NOW, null, 0, Limit.of(2))).thenReturn(List.of(new MemberCouponExpiryTarget(21, NOW)));
        when(members.findExpiredPlatformBudgetTargets(3, NOW, null, 0, Limit.of(2))).thenReturn(List.of(new MemberCouponExpiryTarget(31, NOW)));
        doThrow(new IllegalArgumentException("잘못된 한 발급 쿠폰")).when(releaseRecorder).recordDue(11, NOW);

        useCase.runDue();

        for (long id : List.of(11L, 12L, 13L, 21L, 31L)) {
            verify(releaseRecorder).recordDue(id, NOW);
        }
        verifyNoMoreInteractions(releaseRecorder);
        verify(coupons).findPlatformMemberBudgetCouponIds(NOW, 2, Limit.of(2));
        verify(members).findExpiredPlatformBudgetTargets(1, NOW, NOW, 12, Limit.of(2));
    }

    @Test
    void 선점이_0행이고_완료_기록도_없으면_billing을_부르지_않는다() {
        when(works.claim(RESERVE_WORK_ID, NOW, NOW.plus(LEASE))).thenReturn(0);
        when(works.findForUpdateById(RESERVE_WORK_ID)).thenReturn(Optional.empty());

        useCase.execute(RESERVE_WORK_ID);

        verifyNoInteractions(budget, coupons, releaseRecorder);
        verify(works, never()).findById(any());
    }

    @Test
    void 조회_성공도_다른_시도가_현재_작업을_선점했으면_버린다() {
        BudgetWork sent = reserveWork(1);
        BudgetWork current = reserveWork(2);
        claimed(sent);
        when(works.findForUpdateById(RESERVE_WORK_ID)).thenReturn(Optional.of(current));
        when(budget.status(RESERVE_WORK_ID)).thenReturn(succeeded(sent, RESERVATION_ID));

        useCase.execute(RESERVE_WORK_ID);

        assertThat(current.getStatus()).isEqualTo(BudgetWorkStatus.PENDING);
        assertThat(current.getResult()).isNull();
        assertThat(coupon.getBudgetReservationId()).isNull();
        verifyNoInteractions(coupons, releaseRecorder);
        verify(budget).status(RESERVE_WORK_ID);
        verifyNoMoreInteractions(budget);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mismatchedReserveStatuses")
    void 예약_조회_성공의_작업_정보가_다르면_재전송도_완료_기록도_하지_않는다(String difference,
                                                                                 BudgetResult response) {
        BudgetWork work = reserveWork(1);
        claimed(work);
        when(budget.status(RESERVE_WORK_ID)).thenReturn(response);

        useCase.execute(RESERVE_WORK_ID);

        assertThat(work.getStatus()).isEqualTo(BudgetWorkStatus.PENDING);
        assertThat(work.getResult()).isNull();
        assertThat(coupon.getBudgetReservationId()).isNull();
        verifyNoInteractions(coupons, releaseRecorder);
        verify(budget).status(RESERVE_WORK_ID);
        verifyNoMoreInteractions(budget);
    }

    private static Stream<Arguments> mismatchedReserveStatuses() {
        return Stream.of(
                Arguments.of("작업 종류", new BudgetResult(Outcome.SUCCEEDED, RESERVATION_ID, null,
                        new WorkMetadata(RESERVE_WORK_ID, BudgetWorkKind.RELEASE, COUPON_ID, BUDGET))),
                Arguments.of("작업 금액", new BudgetResult(Outcome.SUCCEEDED, RESERVATION_ID, null,
                        new WorkMetadata(RESERVE_WORK_ID, BudgetWorkKind.RESERVE, COUPON_ID, BUDGET - 1))),
                Arguments.of("쿠폰 ID", new BudgetResult(Outcome.SUCCEEDED, RESERVATION_ID, null,
                        new WorkMetadata(RESERVE_WORK_ID, BudgetWorkKind.RESERVE, COUPON_ID + 1, BUDGET))),
                Arguments.of("작업 ID", new BudgetResult(Outcome.SUCCEEDED, RESERVATION_ID, null,
                        new WorkMetadata("coupon-2-reserve-v1", BudgetWorkKind.RESERVE, COUPON_ID, BUDGET))),
                Arguments.of("예약 ID", new BudgetResult(Outcome.SUCCEEDED, "PB-0", null,
                        new WorkMetadata(RESERVE_WORK_ID, BudgetWorkKind.RESERVE, COUPON_ID, BUDGET))),
                Arguments.of("조회 메타데이터 누락", new BudgetResult(Outcome.SUCCEEDED, RESERVATION_ID, null)));
    }

    @Test
    void 응답_불일치로_조회가_불명이면_원래_명령도_다시_보내지_않는다() {
        BudgetWork work = reserveWork(1);
        claimed(work);
        when(budget.status(RESERVE_WORK_ID)).thenReturn(new BudgetResult(Outcome.UNKNOWN, null, "RESPONSE_MISMATCH"));

        useCase.execute(RESERVE_WORK_ID);

        assertThat(work.getStatus()).isEqualTo(BudgetWorkStatus.PENDING);
        verifyNoInteractions(coupons, releaseRecorder);
        verify(budget).status(RESERVE_WORK_ID);
        verifyNoMoreInteractions(budget);
    }

    @Test
    void 조회가_NOT_FOUND면_같은_작업_ID로_원래_명령을_보내_성공을_기록한다() {
        BudgetWork work = reserveWork(1);
        claimed(work);
        when(budget.status(RESERVE_WORK_ID)).thenReturn(new BudgetResult(Outcome.UNKNOWN, null, "NOT_FOUND"));
        when(coupons.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
        when(coupons.findForBudgetRelease(COUPON_ID)).thenReturn(Optional.of(coupon));
        when(budget.reserve(RESERVE_WORK_ID, COUPON_ID, 1, BUDGET))
                .thenReturn(new BudgetResult(Outcome.SUCCEEDED, RESERVATION_ID, null));

        useCase.execute(RESERVE_WORK_ID);

        assertThat(work.getStatus()).isEqualTo(BudgetWorkStatus.DONE);
        assertThat(coupon.getBudgetReservationId()).isEqualTo(RESERVATION_ID);
        verify(budget).status(RESERVE_WORK_ID);
        verify(budget).reserve(RESERVE_WORK_ID, COUPON_ID, 1, BUDGET);
        verifyNoMoreInteractions(budget);
    }

    @Test
    void 해제_조회_성공이_다른_예약_ID이면_완료하거나_명령을_재전송하지_않는다() {
        coupon.recordBudgetReserved(RESERVATION_ID, NOW);
        BudgetWork work = BudgetWork.release(coupon, "member-901-to-1000", RELEASE_HP,
                "MEMBER_COUPON:901", "발급 쿠폰 만료 뒤 남은 예산", NOW);
        ReflectionTestUtils.setField(work, "attempt", 1L);
        claimed(work);
        when(budget.status(work.getWorkId())).thenReturn(succeeded(work, "PB-43"));

        useCase.execute(work.getWorkId());

        assertThat(work.getStatus()).isEqualTo(BudgetWorkStatus.PENDING);
        assertThat(work.getResult()).isNull();
        verifyNoInteractions(coupons, releaseRecorder);
        verify(budget).status(work.getWorkId());
        verifyNoMoreInteractions(budget);
    }

    @Test
    void 일치하는_예약_조회_성공은_명령을_다시_보내지_않고_완료와_쿠폰_예약을_기록한다() {
        BudgetWork work = reserveWork(1);
        claimed(work);
        when(budget.status(RESERVE_WORK_ID)).thenReturn(succeeded(work, RESERVATION_ID));
        when(coupons.findForBudgetRelease(COUPON_ID)).thenReturn(Optional.of(coupon));

        useCase.execute(RESERVE_WORK_ID);

        assertThat(work.getStatus()).isEqualTo(BudgetWorkStatus.DONE);
        assertThat(work.getResult()).isEqualTo(RESERVATION_ID);
        assertThat(coupon.getBudgetReservationId()).isEqualTo(RESERVATION_ID);
        verify(releaseRecorder).recordExpiredDraft(COUPON_ID, NOW);
        verify(budget).status(RESERVE_WORK_ID);
        verifyNoMoreInteractions(budget);
    }

    private BudgetWork reserveWork(long attempt) {
        BudgetWork work = BudgetWork.reserve(coupon, BudgetWork.FIRST_ATTEMPT, NOW);
        ReflectionTestUtils.setField(work, "attempt", attempt);
        return work;
    }

    /** claim의 결과를 입력으로 주며 DB 선점 자체를 흉내 내지 않는다. */
    private void claimed(BudgetWork work) {
        when(works.claim(work.getWorkId(), NOW, NOW.plus(LEASE))).thenReturn(1);
        when(works.findById(work.getWorkId())).thenReturn(Optional.of(work));
        when(works.findForUpdateById(work.getWorkId())).thenReturn(Optional.of(work));
    }

    private static BudgetResult succeeded(BudgetWork work, String reservationId) {
        return new BudgetResult(Outcome.SUCCEEDED, reservationId, null,
                new WorkMetadata(work.getWorkId(), work.getKind(), work.getCouponId(), work.getAmount()));
    }
}

package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.*;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.hapbang.coupon.domain.BudgetWork;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponUsage;
import com.hapbang.coupon.domain.CouponUsageStatus;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.out.BudgetWorkRepository;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.CouponUsageRepository;
import com.hapbang.coupon.out.MemberCouponBudgetState;
import com.hapbang.coupon.out.MemberCouponRepository;

/** 저장소가 돌려준 현재 상태에서 해제 차액과 CAS 인자를 정한다. 실제 CAS·트랜잭션 원자성은 DB 테스트가 확인한다. */
class CouponBudgetReleaseRecorderTest {
    private static final long MEMBER_COUPON_ID = 901L;
    private static final long GENERATION = 3L;
    private static final long MAXIMUM = 1_000L;
    private static final Instant EXPIRED_AT = NOW.minusSeconds(1);
    private static final String RESERVATION_ID = "rsv-1";

    private final CouponRepository coupons = mock();
    private final MemberCouponRepository members = mock();
    private final CouponUsageRepository usages = mock();
    private final BudgetWorkRepository works = mock();
    private final CouponBudgetReleaseRecorder recorder = new CouponBudgetReleaseRecorder(coupons, members, usages, works);

    @BeforeEach
    void setUp() {
        Coupon coupon = draftPlatformCoupon();
        coupon.recordBudgetReserved(RESERVATION_ID, NOW);
        when(coupons.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
    }

    @Test
    void 사용_만료로_M빼기F를_계획한_뒤_만료_복원되면_차액F만_추가한다() {
        when(members.findBudgetState(MEMBER_COUPON_ID)).thenReturn(
                Optional.of(state(MemberCouponStatus.USED, GENERATION, 0, null)),
                Optional.of(state(MemberCouponStatus.EXPIRED, GENERATION + 1, 700, MemberCouponStatus.USED)));
        paidUsage(300);
        when(members.planBudgetRelease(MEMBER_COUPON_ID, GENERATION, MemberCouponStatus.USED, 0, 700, NOW))
                .thenReturn(1);
        when(members.planBudgetRelease(MEMBER_COUPON_ID, GENERATION + 1, MemberCouponStatus.EXPIRED, 700, MAXIMUM, NOW))
                .thenReturn(1);

        recorder.recordDue(MEMBER_COUPON_ID, NOW);
        recorder.recordExpired(MEMBER_COUPON_ID, NOW);

        ArgumentCaptor<BudgetWork> saved = ArgumentCaptor.forClass(BudgetWork.class);
        verify(works, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(BudgetWork::getAmount).containsExactly(700L, 300L);
        assertThat(saved.getAllValues()).extracting(BudgetWork::getWorkId).containsExactly(
                "coupon-1-release-member-901-to-700", "coupon-1-release-member-901-to-1000");
        assertThat(saved.getAllValues()).allSatisfy(work -> {
            assertThat(work.getReservationId()).isEqualTo(RESERVATION_ID);
            assertThat(work.getTarget()).isEqualTo("MEMBER_COUPON:901");
        });
        verify(members).planBudgetRelease(MEMBER_COUPON_ID, GENERATION, MemberCouponStatus.USED, 0, 700, NOW);
        verify(members).planBudgetRelease(MEMBER_COUPON_ID, GENERATION + 1, MemberCouponStatus.EXPIRED, 700, MAXIMUM, NOW);
        verify(usages, times(1)).findByMemberCouponIdAndGeneration(MEMBER_COUPON_ID, GENERATION);
    }

    @Test
    void 현재_상태_CAS가_0행이면_해제_작업을_저장하지_않는다() {
        when(members.findBudgetState(MEMBER_COUPON_ID))
                .thenReturn(Optional.of(state(MemberCouponStatus.EXPIRED, GENERATION, 0, null)));
        when(members.planBudgetRelease(MEMBER_COUPON_ID, GENERATION, MemberCouponStatus.EXPIRED, 0, MAXIMUM, NOW))
                .thenReturn(0);

        recorder.recordDue(MEMBER_COUPON_ID, NOW);

        verify(members).planBudgetRelease(MEMBER_COUPON_ID, GENERATION, MemberCouponStatus.EXPIRED, 0, MAXIMUM, NOW);
        verifyNoInteractions(works);
    }

    @Test
    void 사용_부담액이_장당_최대_예산을_넘으면_CAS와_작업_저장_전에_거부한다() {
        when(members.findBudgetState(MEMBER_COUPON_ID))
                .thenReturn(Optional.of(state(MemberCouponStatus.USED, GENERATION, 0, null)));
        paidUsage(MAXIMUM + 1);

        assertThatThrownBy(() -> recorder.recordDue(MEMBER_COUPON_ID, NOW))
                .isInstanceOf(IllegalStateException.class);

        verify(members, never()).planBudgetRelease(anyLong(), anyLong(), any(), anyLong(), anyLong(), any());
        verifyNoInteractions(works);
    }

    @Test
    void 해제액이_0이어도_점검_완료_CAS를_하고_이미_점검한_상태는_다시_계획하지_않는다() {
        when(members.findBudgetState(MEMBER_COUPON_ID)).thenReturn(
                Optional.of(state(MemberCouponStatus.USED, GENERATION, 0, null)),
                Optional.of(state(MemberCouponStatus.USED, GENERATION, 0, MemberCouponStatus.USED)));
        paidUsage(MAXIMUM);
        when(members.planBudgetRelease(MEMBER_COUPON_ID, GENERATION, MemberCouponStatus.USED, 0, 0, NOW))
                .thenReturn(1);

        recorder.recordDue(MEMBER_COUPON_ID, NOW);
        recorder.recordDue(MEMBER_COUPON_ID, NOW);

        verify(members, times(1)).planBudgetRelease(MEMBER_COUPON_ID, GENERATION, MemberCouponStatus.USED, 0, 0, NOW);
        verify(usages, times(1)).findByMemberCouponIdAndGeneration(MEMBER_COUPON_ID, GENERATION);
        verifyNoInteractions(works);
    }

    private static MemberCouponBudgetState state(MemberCouponStatus status, long generation, long planned,
                                                  MemberCouponStatus checkedStatus) {
        return new MemberCouponBudgetState(MEMBER_COUPON_ID, COUPON_ID, status, generation, EXPIRED_AT, planned,
                checkedStatus);
    }

    private void paidUsage(long platformHp) {
        CouponUsage usage = mock();
        when(usage.getStatus()).thenReturn(CouponUsageStatus.PAID);
        when(usage.getPlatformAmount()).thenReturn(platformHp);
        when(usages.findByMemberCouponIdAndGeneration(MEMBER_COUPON_ID, GENERATION)).thenReturn(Optional.of(usage));
    }
}

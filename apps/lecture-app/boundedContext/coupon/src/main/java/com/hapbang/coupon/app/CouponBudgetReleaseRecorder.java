package com.hapbang.coupon.app;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.coupon.domain.BudgetWork;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.CouponUsage;
import com.hapbang.coupon.domain.CouponUsageStatus;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.out.BudgetWorkRepository;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.CouponUsageRepository;
import com.hapbang.coupon.out.MemberCouponBudgetState;
import com.hapbang.coupon.out.MemberCouponRepository;

import lombok.RequiredArgsConstructor;

/** 해제 의도를 쿠폰 변경과 함께 영속화한다. billing 호출은 작업 배치가 커밋 뒤에 한다. */
@Component
@RequiredArgsConstructor
public class CouponBudgetReleaseRecorder {
    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;
    private final CouponUsageRepository couponUsageRepository;
    private final BudgetWorkRepository budgetWorkRepository;

    /** 정상 대사가 발급 정리를 확인한 CLOSED 쿠폰에서만 호출한다. Redis 유실·불명 경로에서는 부르지 않는다. */
    @Transactional
    public void recordUnissued(long couponId, Instant now) {
        Coupon coupon = couponRepository.findForBudgetRelease(couponId).orElseThrow();
        if (coupon.getType() != CouponType.PLATFORM || coupon.getStatus() != CouponStatus.CLOSED) {
            return;
        }
        long quantity = Math.subtractExact(coupon.getQuantity(), coupon.getIssuedCount());
        long amount = Math.multiplyExact(quantity, maximum(coupon));
        if (amount <= 0) {
            return;
        }
        BudgetWork release = BudgetWork.release(coupon, "unissued-v" + coupon.getPolicyVersion(), amount,
                "UNISSUED:" + quantity, "발급 종료 뒤 미발급 예산", now);
        if (!budgetWorkRepository.existsById(release.getWorkId())) {
            budgetWorkRepository.save(release);
        }
    }

    /** AVAILABLE 만료·예약 해제·취소 복원과 같은 트랜잭션에서 호출한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordExpired(long memberCouponId, Instant now) {
        recordMember(memberCouponId, now, false);
    }

    /** 사용한 쿠폰의 만료 뒤 M-F, 이미 만료된 쿠폰의 아직 등록하지 않은 M을 확인한다. */
    @Transactional
    public void recordDue(long memberCouponId, Instant now) {
        recordMember(memberCouponId, now, true);
    }

    private void recordMember(long memberCouponId, Instant now, boolean allowUsed) {
        MemberCouponBudgetState state = memberCouponRepository.findBudgetState(memberCouponId).orElseThrow();
        if (state.expiresAt().isAfter(now)
                || (state.status() != MemberCouponStatus.EXPIRED
                    && (!allowUsed || state.status() != MemberCouponStatus.USED))) {
            return;
        }
        Coupon coupon = couponRepository.findById(state.couponId()).orElseThrow();
        if (coupon.getType() != CouponType.PLATFORM) {
            return;
        }
        long maximum = maximum(coupon);
        long retained = 0;
        if (state.status() == MemberCouponStatus.USED) {
            CouponUsage usage = couponUsageRepository.findByMemberCouponIdAndGeneration(state.id(), state.generation())
                    .orElseThrow(() -> new IllegalStateException("사용한 발급 쿠폰의 사용 기록이 없습니다."));
            if (usage.getStatus() != CouponUsageStatus.PAID) {
                throw new IllegalStateException("발급 쿠폰과 사용 기록 상태가 다릅니다.");
            }
            retained = usage.getPlatformAmount();
        }
        long planned = Math.subtractExact(maximum, retained);
        if (retained < 0 || planned < 0 || state.plannedHp() < 0 || state.plannedHp() > maximum) {
            throw new IllegalStateException("쿠폰 예산 해제 누적액이 예산 범위를 벗어났습니다.");
        }
        if (planned <= state.plannedHp()) {
            return;
        }
        long amount = Math.subtractExact(planned, state.plannedHp());
        if (memberCouponRepository.planBudgetRelease(state.id(), state.generation(), state.status(), state.plannedHp(),
                planned, now) != 1) {
            // 같은 발급 쿠폰을 취소·만료·다른 작업이 먼저 바꿨다. 그 전이 또는 다음 회차가 현재 값으로 다시 등록한다.
            return;
        }
        budgetWorkRepository.save(BudgetWork.release(coupon, "member-" + state.id() + "-to-" + planned,
                amount, "MEMBER_COUPON:" + state.id(), "발급 쿠폰 만료 뒤 남은 예산", now));
    }

    private static long maximum(Coupon coupon) {
        return coupon.budgetAmount() / coupon.getQuantity();
    }
}

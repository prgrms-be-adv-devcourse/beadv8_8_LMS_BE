package com.hapbang.coupon.out;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.coupon.domain.BudgetWork;
import com.hapbang.coupon.domain.BudgetWorkKind;

/**
 * 프로모션 예산 작업 저장소. 발급 열기 재시도는 결정적 작업 ID({@code Coupon.budgetReserveWorkId(attempt)})로 {@code findById}한다.
 */
public interface BudgetWorkRepository extends JpaRepository<BudgetWork, String> {

    /** 쿠폰의 이 종류 작업들. 오래된 순 */
    List<BudgetWork> findByCouponIdAndKindOrderByCreatedAtAsc(Long couponId, BudgetWorkKind kind);
}

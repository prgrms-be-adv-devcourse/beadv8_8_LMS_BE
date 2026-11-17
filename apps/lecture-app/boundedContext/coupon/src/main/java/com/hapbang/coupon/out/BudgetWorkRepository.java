package com.hapbang.coupon.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.coupon.domain.BudgetWork;
import com.hapbang.coupon.domain.BudgetWorkKind;

/**
 * 프로모션 예산 작업 저장소. 발급 열기 재시도는 결정적 작업 ID({@code Coupon.budgetReserveWorkId(attempt)})로 {@code findById}한다.
 * 조회는 {@link BudgetWorkRepositoryCustom}(QueryDSL)이, 선점 조건부 갱신은 여기의 JPQL이 맡는다. {@code @Primary}는 조회 계약
 * ({@code BudgetWorkReader}) 주입이 같은 계약을 구현한 QueryDSL 구현체 빈이 아닌 이 저장소를 고르게 한다.
 */
@Primary
public interface BudgetWorkRepository extends JpaRepository<BudgetWork, String>, BudgetWorkRepositoryCustom {

    /** 쿠폰의 이 종류 작업들. 오래된 순 */
    List<BudgetWork> findByCouponIdAndKindOrderByCreatedAtAsc(Long couponId, BudgetWorkKind kind);

    @Modifying(flushAutomatically = true)
    @Query("""
            update BudgetWork w set w.attempt = w.attempt + 1, w.leaseUntil = :until,
                w.updatedAt = :now, w.version = w.version + 1
            where w.workId = :id and w.status = com.hapbang.coupon.domain.BudgetWorkStatus.PENDING
              and (w.leaseUntil is null or w.leaseUntil <= :now)
            """)
    int claim(@Param("id") String id, @Param("now") Instant now, @Param("until") Instant until);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from BudgetWork w where w.workId = :id")
    Optional<BudgetWork> findForUpdateById(@Param("id") String id);
}

package com.hapbang.coupon.app.port;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.coupon.domain.BudgetWork;

import jakarta.persistence.LockModeType;

/**
 * 프로모션 예산 작업 저장소. 발급 열기 재시도는 결정적 작업 ID({@code Coupon.budgetReserveWorkId(attempt)})로 {@code findById}한다.
 * 조회는 {@link BudgetWorkRepositoryCustom}(QueryDSL)이, 선점 조건부 갱신은 여기의 JPQL이 맡는다.
 */
public interface BudgetWorkRepository extends Repository<BudgetWork, String>, BudgetWorkRepositoryCustom {

    // 쓰는 기본 메서드. 구현은 Spring Data JPA 기본 구현(SimpleJpaRepository)이 맡는다
    <S extends BudgetWork> S save(S entity);
    Optional<BudgetWork> findById(String id);
    boolean existsById(String id);

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

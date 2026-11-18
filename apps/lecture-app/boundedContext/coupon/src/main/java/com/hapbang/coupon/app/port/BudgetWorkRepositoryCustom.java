package com.hapbang.coupon.app.port;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;

/**
 * 프로모션 예산 작업 조회. 선점(lease)·상태 변경은 저장소의 조건부 갱신이 맡는다
 * <p>
 * {@link BudgetWorkRepository}의 QueryDSL 조회 조각이다. 구현은 {@code out}의 {@code BudgetWorkRepositoryCustomImpl}이다.
 */
public interface BudgetWorkRepositoryCustom {

    /** 지금 처리할 PENDING 작업 ID: lease가 없거나 지났다. 오래 기다린 순(갱신 시각 → 작업 ID) */
    List<String> findDueIds(Instant now, Limit limit);
}

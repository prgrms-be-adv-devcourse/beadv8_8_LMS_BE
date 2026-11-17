package com.hapbang.coupon.app.port;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;

/** 프로모션 예산 작업 조회. 선점(lease)·상태 변경은 저장소의 조건부 갱신이 맡는다 */
public interface BudgetWorkReader {

    /** 지금 처리할 PENDING 작업 ID: lease가 없거나 지났다. 오래 기다린 순(갱신 시각 → 작업 ID) */
    List<String> findDueIds(Instant now, Limit limit);
}

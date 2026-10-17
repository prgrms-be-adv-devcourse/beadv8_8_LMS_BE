package com.hapbang.billing.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

// 관리자가 수동으로 실행하는 기존 회원 BillingUser Backfill 진입점 (#324). 자동 실행(Scheduler·ApplicationRunner)은 두지 않는다.
// 관리자 API는 두 겹으로 막는다: global SecurityConfig의 /api/v1/*/admin/**(ADMIN)과 이 Facade의 @PreAuthorize.
// 회원 내부 API 호출이 있어 트랜잭션을 열지 않는다(회원마다 BillingUserSyncUseCase가 짧은 트랜잭션을 연다).
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class BillingUserBackfillFacade {

    private final BillingUserBackfillUseCase billingUserBackfillUseCase;

    public BillingUserBackfillResult backfill(long afterId, int limit) {
        return billingUserBackfillUseCase.run(afterId, limit);
    }
}

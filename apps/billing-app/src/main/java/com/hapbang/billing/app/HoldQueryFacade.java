package com.hapbang.billing.app;

import java.time.Instant;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.shared.billing.domain.BillingReferenceType;

import lombok.RequiredArgsConstructor;

// 서비스 간 HP 대사용 보류·지급 조회 진입점 (#577, BD-137). HP를 움직이는 HoldFacade와 나눠, 조회만 내부 API(ROLE_INTERNAL)로 연다.
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('INTERNAL')")
public class HoldQueryFacade {

    private final HoldQueryUseCase holdQueryUseCase;

    public List<HoldState> query(BillingReferenceType referenceType, List<Long> referenceIds) {
        return holdQueryUseCase.query(referenceType, referenceIds);
    }

    public HoldStatePage changes(BillingReferenceType referenceType, Instant updatedAfter, long afterId, int size) {
        return holdQueryUseCase.changes(referenceType, updatedAfter, afterId, size);
    }
}

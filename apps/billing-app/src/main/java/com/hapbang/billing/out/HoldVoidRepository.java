package com.hapbang.billing.out;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.HoldVoid;
import com.hapbang.shared.billing.domain.BillingReferenceType;

// 보류 무효화 기록의 저장·조회를 담당하며 삭제 메서드는 제공하지 않는다 (#479).
public interface HoldVoidRepository extends Repository<HoldVoid, Long> {

    // 무효화 기록을 저장하고 바로 DB에 반영한다(참조 대상 UNIQUE 충돌을 이 자리에서 알기 위해).
    HoldVoid saveAndFlush(HoldVoid entity);

    // 참조 대상의 무효화 기록을 조회한다.
    Optional<HoldVoid> findByReferenceTypeAndReferenceId(BillingReferenceType referenceType, Long referenceId);

    // 참조 대상이 무효화됐는지 확인한다.
    boolean existsByReferenceTypeAndReferenceId(BillingReferenceType referenceType, Long referenceId);
}

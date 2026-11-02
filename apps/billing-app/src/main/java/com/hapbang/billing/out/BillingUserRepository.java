package com.hapbang.billing.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.billing.domain.BillingUser;

import jakarta.persistence.LockModeType;

// Billing 회원 복제본 저장·조회 (#294). 삭제 메서드는 두지 않는다.
public interface BillingUserRepository extends Repository<BillingUser, Long> {

    BillingUser saveAndFlush(BillingUser entity);

    Optional<BillingUser> findById(Long id);

    // 같은 회원의 이벤트 반영을 직렬화하려고 복제본 행을 PESSIMISTIC_WRITE로 잠가 읽는다(버전 비교 → 갱신이 겹치지 않게).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from BillingUser u where u.id = :id")
    Optional<BillingUser> findByIdForUpdate(@Param("id") Long id);
}

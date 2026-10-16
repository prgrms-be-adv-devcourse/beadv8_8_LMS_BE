package com.hapbang.billing.out;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.billing.domain.Exchange;
import com.hapbang.billing.domain.ExchangeStatus;

import jakarta.persistence.LockModeType;

// 환전 요청 정보의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다.
public interface ExchangeRepository extends Repository<Exchange, Long> {

    // 환전 요청 정보를 저장한다.
    Exchange save(Exchange entity);

    // 기본키 ID로 환전 요청 정보를 조회한다.
    Optional<Exchange> findById(Long id);

    // 멱등키로 기존 환전 요청 정보를 조회한다.
    Optional<Exchange> findByIdempotencyKey(String idempotencyKey);

    // 환전 신청을 PESSIMISTIC_WRITE로 잠가 조회한다. 지갑 락 다음에 잡는다(지갑 → 환전 → 보류 순서, #285).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Exchange e where e.id = :id")
    Optional<Exchange> findByIdForUpdate(@Param("id") Long id);

    // 지갑의 환전 신청을 최신순(ID 내림차순)으로 조회한다.
    Page<Exchange> findAllByWalletIdOrderByIdDesc(Long walletId, Pageable pageable);

    // 관리자 처리 전에 잠글 지갑을 알기 위해 환전의 지갑 ID만 락 없이 읽는다(엔티티를 영속성 컨텍스트에 올리지 않는다, #294).
    @Query("select e.walletId from Exchange e where e.id = :id")
    Optional<Long> findWalletIdById(@Param("id") Long id);

    // 관리자 목록: 상태별 오래된 순(승인 대기 처리용) (#294)
    Page<Exchange> findAllByStatusOrderByIdAsc(ExchangeStatus status, Pageable pageable);

    // 관리자 목록: 상태별 최신순(처리 이력 조회용) (#294)
    Page<Exchange> findAllByStatusOrderByIdDesc(ExchangeStatus status, Pageable pageable);
}

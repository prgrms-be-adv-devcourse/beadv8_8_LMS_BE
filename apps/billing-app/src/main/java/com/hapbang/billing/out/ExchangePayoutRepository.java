package com.hapbang.billing.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.billing.domain.ExchangePayout;
import com.hapbang.billing.domain.ExchangePayoutStatus;

import jakarta.persistence.LockModeType;

// 환전 송금 시도의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다. 환전 1건에 시도 여러 건이다 (#304).
public interface ExchangePayoutRepository extends Repository<ExchangePayout, Long> {

    // 환전 송금 시도를 저장한다.
    ExchangePayout save(ExchangePayout entity);

    // 기본키 ID로 환전 송금 시도를 조회한다.
    Optional<ExchangePayout> findById(Long id);

    // 환전의 마지막(시도 번호가 가장 큰) 송금 시도를 조회한다.
    Optional<ExchangePayout> findTopByExchangeIdOrderByAttemptNoDesc(Long exchangeId);

    // 환전의 송금 시도 전부(시도 번호 순).
    List<ExchangePayout> findAllByExchangeIdOrderByAttemptNoAsc(Long exchangeId);

    // 상태별 송금 시도(관리자 운영자 확인 대상 조회), 오래된 순.
    Page<ExchangePayout> findAllByStatusOrderByIdAsc(ExchangePayoutStatus status, Pageable pageable);

    // 송금 요청 ID에 해당하는 환전 송금 시도를 조회한다.
    Optional<ExchangePayout> findByPayoutRequestId(String payoutRequestId);

    // 송금 시도를 PESSIMISTIC_WRITE로 잠가 조회한다. 지갑 → 환전 다음에 잡는다(#304).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ExchangePayout p where p.payoutRequestId = :payoutRequestId")
    Optional<ExchangePayout> findByPayoutRequestIdForUpdate(@Param("payoutRequestId") String payoutRequestId);

    // 결과 확인 대상: 주어진 상태이고 마지막 송금·확인 시작이 기준 시각 이전인 시도의 환전 ID(오래 확인하지 않은 순).
    @Query("""
            select p.exchange.id from ExchangePayout p
            where p.status in :statuses and p.lastCheckedAt <= :checkedBefore
            order by p.lastCheckedAt asc, p.id asc""")
    List<Long> findExchangeIdsByStatusInCheckedBefore(@Param("statuses") Collection<ExchangePayoutStatus> statuses,
            @Param("checkedBefore") Instant checkedBefore, Pageable pageable);
}

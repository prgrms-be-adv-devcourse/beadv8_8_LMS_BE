package com.hapbang.billing.out;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.ExchangePayout;

// 환전 송금 정보의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다.
public interface ExchangePayoutRepository extends Repository<ExchangePayout, Long> {

    // 환전 송금 정보를 저장한다.
    ExchangePayout save(ExchangePayout entity);

    // 기본키 ID로 환전 송금 정보를 조회한다.
    Optional<ExchangePayout> findById(Long id);

    // 환전 ID에 연결된 환전 송금 정보를 조회한다.
    Optional<ExchangePayout> findByExchangeId(Long exchangeId);

    // 송금 요청 ID에 해당하는 환전 송금 내역을 조회한다.
    Optional<ExchangePayout> findByPayoutRequestId(String payoutRequestId);
}

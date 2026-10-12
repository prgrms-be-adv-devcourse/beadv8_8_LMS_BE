package com.hapbang.billing.out;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.Exchange;

// 환전 요청 정보의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다.
public interface ExchangeRepository extends Repository<Exchange, Long> {

    // 환전 요청 정보를 저장한다.
    Exchange save(Exchange entity);

    // 기본키 ID로 환전 요청 정보를 조회한다.
    Optional<Exchange> findById(Long id);

    // 멱등키로 기존 환전 요청 정보를 조회한다.
    Optional<Exchange> findByIdempotencyKey(String idempotencyKey);
}

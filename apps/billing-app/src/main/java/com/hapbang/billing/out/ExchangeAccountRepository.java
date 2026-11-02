package com.hapbang.billing.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.ExchangeAccount;

// 환전 계좌 정보의 저장·조회를 담당하며 금융 데이터 삭제 메서드는 제공하지 않는다.
public interface ExchangeAccountRepository extends Repository<ExchangeAccount, Long> {

    // 환전 계좌 정보를 저장한다.
    ExchangeAccount save(ExchangeAccount entity);

    // 기본키 ID로 환전 계좌 정보를 조회한다.
    Optional<ExchangeAccount> findById(Long id);

    // 환전 ID에 연결된 환전 계좌 정보를 조회한다.
    Optional<ExchangeAccount> findByExchangeId(Long exchangeId);

    // 여러 환전의 계좌 스냅샷을 한 번에 조회한다(목록 조회용).
    List<ExchangeAccount> findAllByExchangeIdIn(Collection<Long> exchangeIds);
}

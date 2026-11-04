package com.hapbang.billing.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.billing.domain.ExchangeAccount;
import com.hapbang.billing.domain.ExchangeStatus;

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

    // 회원 정보 파기 때 회원의 끝난 환전(주어진 끝난 상태)의 계좌번호 암호문·예금주를 비운다 (#674, BD-144).
    // 은행 코드·검증 상태·일시는 남긴다. 끝난 환전은 처리 중으로 돌아가지 않으므로 송금 배치와 겹쳐도 송금에 쓸 계좌를 지우지 않는다.
    // 이미 비운 계좌는 건너뛰어 다시 받아도 결과가 같다. 비운 계좌 수를 돌려준다
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ExchangeAccount a set a.accountNumberEncrypted = null, a.accountHolderName = null
            where (a.accountNumberEncrypted is not null or a.accountHolderName is not null)
              and a.exchange.id in (select e.id from Exchange e where e.userId = :userId and e.status in :finished)""")
    int eraseFinishedOf(@Param("userId") Long userId, @Param("finished") Collection<ExchangeStatus> finished);
}

package com.hapbang.billing.out;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.billing.domain.WalletReconciliationResult;
import com.hapbang.billing.domain.WalletReconciliationRunWallet;

// 잔액 대사 실행 기록의 문제 지갑 저장·조회 (#822, BD-146). 지우지 않으므로 삭제 메서드는 두지 않는다.
public interface WalletReconciliationRunWalletRepository extends Repository<WalletReconciliationRunWallet, Long> {

    <S extends WalletReconciliationRunWallet> List<S> saveAll(Iterable<S> wallets);

    // 한 실행에서 주어진 결과로 남은 지갑 ID(오름차순)
    @Query("""
            select w.walletId from WalletReconciliationRunWallet w
            where w.runId = :runId and w.result = :result
            order by w.walletId""")
    List<Long> findWalletIds(@Param("runId") Long runId, @Param("result") WalletReconciliationResult result);
}

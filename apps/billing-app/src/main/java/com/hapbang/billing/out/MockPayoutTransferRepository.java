package com.hapbang.billing.out;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.hapbang.billing.domain.ExchangeStatus;
import com.hapbang.billing.domain.MockPayoutTransfer;
import com.hapbang.billing.domain.MockPayoutTransferStatus;

// Mock 은행의 가상 송금 기록 저장·조회 (#288). 삭제 메서드는 두지 않는다.
public interface MockPayoutTransferRepository extends Repository<MockPayoutTransfer, Long> {

    MockPayoutTransfer save(MockPayoutTransfer entity);

    MockPayoutTransfer saveAndFlush(MockPayoutTransfer entity);

    Optional<MockPayoutTransfer> findByPayoutRequestId(String payoutRequestId);

    // 회원 정보 파기 때 회원의 끝난 환전에 속한 끝난 가상 송금(SUCCEEDED·FAILED)의 예금주·계좌번호 해시를 고정 마스킹 값으로 덮는다
    // (#674, BD-144). 두 칸이 NOT NULL이라 비우지 않고 덮는다. 처리 중인 송금(PROCESSING)과 처리 중인 환전의 송금은 같은 키 재요청
    // 비교에 해시가 필요하므로 건드리지 않는다. 이미 덮은 기록은 건너뛰어 다시 받아도 결과가 같다. 덮은 기록 수를 돌려준다
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update MockPayoutTransfer t
            set t.accountHolderName = :maskedHolder, t.accountNumberHash = :maskedHash, t.updatedAt = :now
            where t.status in :finished
              and (t.accountHolderName <> :maskedHolder or t.accountNumberHash <> :maskedHash)
              and t.exchangeId in (select e.id from Exchange e where e.userId = :userId and e.status not in :inProgress)""")
    int maskFinishedOf(@Param("userId") Long userId, @Param("inProgress") Collection<ExchangeStatus> inProgress,
            @Param("finished") Collection<MockPayoutTransferStatus> finished, @Param("maskedHolder") String maskedHolder,
            @Param("maskedHash") String maskedHash, @Param("now") Instant now);
}

package com.hapbang.billing.out;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.MockPayoutTransfer;

// Mock 은행의 가상 송금 기록 저장·조회 (#288). 삭제 메서드는 두지 않는다.
public interface MockPayoutTransferRepository extends Repository<MockPayoutTransfer, Long> {

    MockPayoutTransfer save(MockPayoutTransfer entity);

    MockPayoutTransfer saveAndFlush(MockPayoutTransfer entity);

    Optional<MockPayoutTransfer> findByPayoutRequestId(String payoutRequestId);
}

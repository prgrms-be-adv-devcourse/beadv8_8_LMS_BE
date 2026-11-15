package com.hapbang.billing.out;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.WalletReconciliationRun;

// 잔액 대사 실행 기록의 저장·조회 (#822, BD-146). 지우지 않으므로 삭제 메서드는 두지 않는다.
public interface WalletReconciliationRunRepository extends Repository<WalletReconciliationRun, Long> {

    WalletReconciliationRun save(WalletReconciliationRun run);

    // 가장 최근 실행(실행 시작 시각, ID 역순). 결과별 지갑 수 복원과 이월 대상의 기준이다.
    Optional<WalletReconciliationRun> findFirstByOrderByStartedAtDescIdDesc();

    // failed가 0인 가장 최근 실행. 마지막 성공 시각 복원의 기준이다.
    Optional<WalletReconciliationRun> findFirstByFailedCountOrderByStartedAtDescIdDesc(int failedCount);
}

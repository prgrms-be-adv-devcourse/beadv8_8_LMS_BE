package com.hapbang.billing.out;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.billing.domain.WalletWithdrawal;
import com.hapbang.shared.billing.domain.WithdrawalCloseOutcome;

// 탈퇴용 지갑 닫기·다시 열기 기록의 저장·조회 (#623). 금융 기록이라 삭제 메서드는 두지 않는다.
public interface WalletWithdrawalRepository extends Repository<WalletWithdrawal, Long> {

    // 기록을 저장하고 바로 반영한다. 다른 회원이 같은 탈퇴 작업 ID를 먼저 저장했으면 PK 충돌이 여기서 드러난다.
    WalletWithdrawal saveAndFlush(WalletWithdrawal withdrawal);

    Optional<WalletWithdrawal> findById(Long withdrawalId);

    // 회원의 마지막 닫기 기록(닫은 시각, 탈퇴 작업 ID 역순). 지갑이 없을 때 지갑이 닫혀 있어야 하는지 이 기록으로 판단한다.
    Optional<WalletWithdrawal> findFirstByUserIdAndCloseOutcomeOrderByClosedAtDescWithdrawalIdDesc(Long userId,
            WithdrawalCloseOutcome closeOutcome);
}

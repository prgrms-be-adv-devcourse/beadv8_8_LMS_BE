package com.hapbang.billing.app;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.domain.Exchange;
import com.hapbang.billing.domain.MockPayoutTransferStatus;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.ExchangeAccountRepository;
import com.hapbang.billing.out.ExchangeRepository;
import com.hapbang.billing.out.MockPayoutTransferRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.billing.out.WalletUserLock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 회원 정보 파기(UserMaskedEvent, #594)에 맞춰 지갑이 가진 그 회원의 식별 정보를 지운다 (#674, BD-144·BD-145).
// 지갑은 날짜를 세지 않고, 회원 앱이 파기했다고 알리면 그때 지운다. 파기된 회원은 복구하지 않으므로 되돌릴 일이 없다.
// 한 트랜잭션에서 회원 락 → 지갑 락 → 열린 지갑 닫기 → 끝난 환전의 계좌 지우기 → 끝난 가상 송금 덮기 순서로 처리한다.
// - 지갑: 열려 있으면 닫는다(탈퇴 작업 ID 없이 닫은 시각만). 복구 때 다시 열기가 회원 쪽 시간 제한에 걸린 뒤 지갑에서 늦게 처리돼
//   지갑이 열린 채 남은 경우다. 잔액·처리 중인 거래와 관계없이 닫는다. 닫힌 지갑도 이미 생긴 의무(보류 반환·지급·복구 배치)는 받고,
//   남은 잔액은 "잔액이 남은 닫힌 지갑" 지표로 운영자가 확인한다(policy.md 지갑 규칙). 이미 닫혀 있으면 바꾸지 않는다.
// - 계좌: 끝난 환전만 지운다. 처리 중인 환전(탈퇴를 막는 상태와 같다)은 송금에 계좌가 필요해 남기고 error 로그를 남긴다.
//   이벤트는 다시 오지 않으므로 남긴 계좌는 운영자가 정리한다.
// - 금액·상태·일시·원장은 건드리지 않는다. 같은 이벤트를 다시 받아도 지운 값을 다시 지우는 일이라 결과가 같다.
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletMaskingUseCase {

    // 가상 송금 기록의 예금주·계좌번호 해시를 덮는 값. 해시 자리에 해시가 아닌 값을 넣어 어떤 계좌의 HMAC과도 같아질 수 없다
    static final String MASKED_HOLDER_NAME = "탈퇴회원";
    static final String MASKED_ACCOUNT_HASH = "masked";
    private static final Set<MockPayoutTransferStatus> FINISHED_TRANSFERS = EnumSet.of(
            MockPayoutTransferStatus.SUCCEEDED, MockPayoutTransferStatus.FAILED);

    private final WalletUserLock walletUserLock;
    private final WalletRepository walletRepository;
    private final LedgerRecordUseCase ledgerRecordUseCase;
    private final ExchangeRepository exchangeRepository;
    private final ExchangeAccountRepository exchangeAccountRepository;
    private final MockPayoutTransferRepository mockPayoutTransferRepository;

    @Transactional
    public void erase(long userId, Instant maskedAt) {
        walletUserLock.lock(userId);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        closeWalletIfOpen(userId, maskedAt, now);

        List<Long> inProgress = exchangeRepository
                .findAllByUserIdAndStatusIn(userId, WalletWithdrawalBlocks.EXCHANGE_IN_PROGRESS_STATUSES).stream()
                .map(Exchange::getId)
                .toList();
        if (!inProgress.isEmpty()) {
            log.error("파기된 회원에게 처리 중인 환전이 있어 그 환전의 계좌는 지우지 않았다. 송금이 끝난 뒤 운영자가 지워야 한다 "
                    + "userId={}, exchangeIds={}, maskedAt={}", userId, inProgress, maskedAt);
        }
        int accounts = exchangeAccountRepository.eraseFinishedOf(userId,
                WalletWithdrawalBlocks.EXCHANGE_IN_PROGRESS_STATUSES);
        int transfers = mockPayoutTransferRepository.maskFinishedOf(userId,
                WalletWithdrawalBlocks.EXCHANGE_IN_PROGRESS_STATUSES, FINISHED_TRANSFERS, MASKED_HOLDER_NAME,
                MASKED_ACCOUNT_HASH, now);
        log.info("파기된 회원의 환전 계좌를 지웠다 userId={}, erasedAccounts={}, maskedTransfers={}, maskedAt={}", userId,
                accounts, transfers, maskedAt);
    }

    private void closeWalletIfOpen(long userId, Instant maskedAt, Instant now) {
        Optional<Long> walletId = walletRepository.findByUserId(userId).map(Wallet::getId);
        if (walletId.isEmpty()) {
            // 관리자이거나 지갑이 아직 없는 회원. 환전도 없어 지울 계좌가 없다
            log.info("파기된 회원의 지갑이 없어 닫지 않는다 userId={}, maskedAt={}", userId, maskedAt);
            return;
        }
        Wallet wallet = ledgerRecordUseCase.lockWallet(walletId.get());
        if (wallet.closeForMasking(now)) {
            walletRepository.save(wallet);
            log.warn("파기된 회원의 지갑이 열려 있어 닫았다 userId={}, walletId={}, chargeBalanceHp={}, revenueBalanceHp={}, "
                            + "heldChargeHp={}, heldRevenueHp={}, maskedAt={}", userId, wallet.getId(),
                    wallet.getChargeBalanceHp(), wallet.getRevenueBalanceHp(), wallet.getHeldChargeHp(),
                    wallet.getHeldRevenueHp(), maskedAt);
        }
    }
}

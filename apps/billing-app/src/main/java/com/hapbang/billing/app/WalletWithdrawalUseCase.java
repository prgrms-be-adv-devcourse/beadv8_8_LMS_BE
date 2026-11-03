package com.hapbang.billing.app;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.domain.WalletWithdrawal;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.billing.out.WalletUserLock;
import com.hapbang.billing.out.WalletWithdrawalRepository;
import com.hapbang.shared.billing.domain.WithdrawalCloseOutcome;
import com.hapbang.shared.billing.domain.WithdrawalReopenOutcome;
import com.hapbang.shared.member.dto.WithdrawalBlockReasonDto;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

// 회원 탈퇴용 지갑 확인하고 닫기·다시 열기 (#623, docs/api/internal-withdrawals.md 4장). 회원 앱이 탈퇴를 지휘하고, 지갑은 그중
// 유일하게 상태를 바꾸는 단계(돈이 드나드는 문을 닫는 일)를 맡는다.
// - 한 트랜잭션에서 회원 락(WalletUserLock) → 지갑 락 → 기록 확인 → 막힌 이유 계산 → 닫기 순서로 처리해, 확인과 닫기 사이에
//   새 결제가 끼어들 틈이 없다(충전·보류·환불·환전 신청도 같은 지갑 락 아래에서 닫힘을 확인한다).
// - 회원 락은 지갑이 아직 없는 회원(가입 이벤트 미반영)의 닫기와 지갑 생성(WalletCreateUseCase)을 직렬화한다. 지갑이 없으면 지갑을
//   만들지 않고 CLOSED 기록만 남기며, 나중에 지갑을 만들 때 그 기록으로 닫힌 지갑을 만든다.
// - 탈퇴 작업 ID가 멱등 키다. 같은 회원의 재요청은 처음 결과를 replayed로, 다른 회원이면 IDEMPOTENCY_KEY_CONFLICT다.
@Service
@RequiredArgsConstructor
public class WalletWithdrawalUseCase {

    static final String WITHDRAWAL_PK = "pk_wallet_withdrawal";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<WithdrawalBlockReasonDto>> REASONS = new TypeReference<>() {
    };

    private final WalletUserLock walletUserLock;
    private final WalletRepository walletRepository;
    private final WalletWithdrawalRepository walletWithdrawalRepository;
    private final LedgerRecordUseCase ledgerRecordUseCase;
    private final WalletWithdrawalBlocks walletWithdrawalBlocks;

    // 막힌 이유가 없으면 지갑을 닫고(CLOSED), 있으면 닫지 않고 이유를 남긴다(BLOCKED).
    // 다시 열기를 이미 받은 탈퇴 작업이면 지갑을 닫지 않고 WITHDRAWAL_ALREADY_REOPENED로 거절한다(늦게 도착한 닫기).
    @Transactional
    public WalletWithdrawalCloseResult close(long withdrawalId, long userId) {
        walletUserLock.lock(userId);
        Optional<WalletWithdrawal> existing = walletWithdrawalRepository.findById(withdrawalId);
        if (existing.isPresent()) {
            WalletWithdrawal recorded = existing.get();
            requireOwner(recorded, userId);
            if (recorded.isReopened()) {
                throw new BillingException(BillingErrorCode.WITHDRAWAL_ALREADY_REOPENED);
            }
            return closeResult(recorded, true);
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Optional<Long> walletId = walletRepository.findByUserId(userId).map(Wallet::getId);
        if (walletId.isEmpty()) {
            // 지갑이 없으면 잔액·환불·환전·충전이 없다. 기록만 남기고 지갑을 만들 때 닫힌 지갑으로 만든다
            return closeResult(save(WalletWithdrawal.closed(withdrawalId, userId, now)), false);
        }
        Wallet wallet = ledgerRecordUseCase.lockWallet(walletId.get());
        List<WithdrawalBlockReasonDto> reasons = walletWithdrawalBlocks.of(wallet);
        if (!reasons.isEmpty()) {
            return closeResult(save(WalletWithdrawal.blocked(withdrawalId, userId, write(reasons))), false);
        }
        wallet.closeFor(withdrawalId, now);
        return closeResult(save(WalletWithdrawal.closed(withdrawalId, userId, now)), false);
    }

    // 이 탈퇴 작업으로 닫힌 지갑이면 다시 열고(REOPENED), 아니면 바꾸지 않는다(NOT_CLOSED). 어느 경우든 다시 열기 기록을 남겨
    // 이 탈퇴 작업의 늦은 닫기를 거절한다. 지갑이 다른 탈퇴 작업으로 닫혀 있으면 열지 않는다.
    @Transactional
    public WalletWithdrawalReopenResult reopen(long withdrawalId, long userId) {
        walletUserLock.lock(userId);
        Optional<WalletWithdrawal> existing = walletWithdrawalRepository.findById(withdrawalId);
        if (existing.isPresent()) {
            requireOwner(existing.get(), userId);
            if (existing.get().isReopened()) {
                return reopenResult(existing.get(), true);
            }
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (existing.isEmpty()) {
            // 닫기를 받기 전(닫기가 실패했거나 아직 오지 않음)
            return reopenResult(save(WalletWithdrawal.reopenedBeforeClose(withdrawalId, userId, now)), false);
        }
        WalletWithdrawal recorded = existing.get();
        WithdrawalReopenOutcome outcome = recorded.isClosed() && reopenWallet(withdrawalId, userId)
                ? WithdrawalReopenOutcome.REOPENED : WithdrawalReopenOutcome.NOT_CLOSED;
        recorded.recordReopen(outcome, now);
        walletWithdrawalRepository.saveAndFlush(recorded);
        return reopenResult(recorded, false);
    }

    // 이 탈퇴 작업으로 닫힌 지갑을 연다. 지갑이 없으면 회원의 마지막 닫기가 이 작업일 때 연 것으로 본다(지갑을 만들 때 이 기록을 본다).
    private boolean reopenWallet(long withdrawalId, long userId) {
        Optional<Long> walletId = walletRepository.findByUserId(userId).map(Wallet::getId);
        if (walletId.isEmpty()) {
            return walletWithdrawalRepository
                    .findFirstByUserIdAndCloseOutcomeOrderByClosedAtDescWithdrawalIdDesc(userId,
                            WithdrawalCloseOutcome.CLOSED)
                    .filter(latest -> latest.getWithdrawalId() == withdrawalId && !latest.isReopened())
                    .isPresent();
        }
        Wallet wallet = ledgerRecordUseCase.lockWallet(walletId.get());
        if (!wallet.isClosedBy(withdrawalId)) {
            return false;
        }
        wallet.reopen();
        return true;
    }

    private static void requireOwner(WalletWithdrawal withdrawal, long userId) {
        if (!withdrawal.isOwnedBy(userId)) {
            throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
    }

    // 새 기록을 저장한다. 다른 회원이 같은 탈퇴 작업 ID를 동시에 저장해(회원 락이 달라 직렬화되지 않는다) PK가 충돌하면
    // IDEMPOTENCY_KEY_CONFLICT로 바꿔 지갑 변경까지 전부 롤백시킨다.
    private WalletWithdrawal save(WalletWithdrawal withdrawal) {
        try {
            return walletWithdrawalRepository.saveAndFlush(withdrawal);
        } catch (DataIntegrityViolationException exception) {
            if (WITHDRAWAL_PK.equals(HoldProcessing.uniqueViolation(exception))) {
                throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception);
            }
            throw exception;
        }
    }

    private static WalletWithdrawalCloseResult closeResult(WalletWithdrawal withdrawal, boolean replayed) {
        List<WithdrawalBlockReasonDto> reasons = withdrawal.getBlockReasons() == null ? List.of()
                : read(withdrawal.getBlockReasons());
        return new WalletWithdrawalCloseResult(withdrawal.getWithdrawalId(), withdrawal.getCloseOutcome(), reasons,
                replayed);
    }

    private static WalletWithdrawalReopenResult reopenResult(WalletWithdrawal withdrawal, boolean replayed) {
        return new WalletWithdrawalReopenResult(withdrawal.getWithdrawalId(), withdrawal.getReopenOutcome(),
                replayed);
    }

    private static String write(List<WithdrawalBlockReasonDto> reasons) {
        try {
            return JSON.writeValueAsString(reasons);
        } catch (JacksonException exception) {
            throw new IllegalStateException("막힌 이유를 JSON으로 바꾸지 못했습니다.", exception);
        }
    }

    private static List<WithdrawalBlockReasonDto> read(String reasons) {
        try {
            return JSON.readValue(reasons, REASONS);
        } catch (JacksonException exception) {
            throw new IllegalStateException("저장한 막힌 이유를 읽지 못했습니다.", exception);
        }
    }
}

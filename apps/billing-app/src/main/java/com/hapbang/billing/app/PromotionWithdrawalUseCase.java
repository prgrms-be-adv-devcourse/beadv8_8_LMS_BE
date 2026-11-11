package com.hapbang.billing.app;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.PromotionWithdrawal;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.PromotionWithdrawalRepository;
import com.hapbang.billing.out.WalletRepository;

import lombok.RequiredArgsConstructor;

// 관리자 프로모션 지갑 회수. 충전으로 발행한 HP 중 쿠폰 예산으로 묶지 않은 가용 칸에서만 거둬들인다(예약·보류 칸은 건드리지 않는다).
// 회수 작업 ID 하나에 회수 기록 한 건과 원장 한 줄(PROMOTION_WITHDRAW, 가용 칸 −)을 한 트랜잭션에 남긴다.
// - 프로모션 지갑 락 아래에서 같은 회수 작업 ID의 기록을 찾는다. 같은 작업 ID의 요청은 이 락으로 직렬화된다.
// - 기록이 있고 금액·근거 번호·메모가 같으면 원장을 다시 쓰지 않고 처음 결과를 돌려준다(replayed). 다르면 IDEMPOTENCY_KEY_CONFLICT다.
//   재요청 확인을 잔액 확인보다 먼저 해서, 이미 회수한 작업은 그 뒤 잔액이 줄었어도 처음 결과를 받는다.
// - 가용 잔액이 모자라면 PROMOTION_BALANCE_INSUFFICIENT(409)로 거절하고 아무것도 저장하지 않는다. 원장 기록의 음수 잔액 거절
//   (INSUFFICIENT_BALANCE)은 같은 락 아래라 닿지 않는 마지막 방어선이다.
// - 회수한 관리자는 내용으로 보지 않는다. 회수 작업 ID UNIQUE 충돌은 IDEMPOTENCY_KEY_CONFLICT로 바꿔 전체를 롤백한다.
@Service
@RequiredArgsConstructor
public class PromotionWithdrawalUseCase {

    static final String WITHDRAWAL_WORK_ID_UNIQUE = "uk_promotion_withdrawal_withdrawal_work_id";
    static final String UNIQUE_VIOLATION = "23505";

    private final WalletRepository walletRepository;
    private final PromotionWithdrawalRepository promotionWithdrawalRepository;
    private final LedgerRecordUseCase ledgerRecordUseCase;

    @Transactional
    public PromotionWithdrawalResult withdraw(Long adminUserId, PromotionWithdrawalCommand command) {
        Objects.requireNonNull(adminUserId, "회수한 관리자 ID는 필수입니다.");
        // 0 HP 원장은 만들지 않는다. 재요청 확인보다 먼저 거절해 아무것도 잠그거나 읽지 않는다
        if (command.amountHp() <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        Wallet wallet = ledgerRecordUseCase.lockWallet(
                PromotionWalletQueryUseCase.promotionWallet(walletRepository).getId());

        Optional<PromotionWithdrawal> existing =
                promotionWithdrawalRepository.findByWithdrawalWorkId(command.withdrawalWorkId());
        if (existing.isPresent()) {
            if (!sameContent(existing.get(), command)) {
                throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return result(existing.get(), wallet, true);
        }

        if (wallet.balanceOf(PointType.PROMOTION, BalanceBucket.AVAILABLE) < command.amountHp()) {
            throw new BillingException(BillingErrorCode.PROMOTION_BALANCE_INSUFFICIENT);
        }
        PromotionWithdrawal withdrawal = save(PromotionWithdrawal.of(command.withdrawalWorkId(), wallet.getId(),
                command.amountHp(), command.referenceNo(), command.memo(), adminUserId));
        ledgerRecordUseCase.record(PromotionLedgerOperations.withdraw(withdrawal,
                Instant.now().truncatedTo(ChronoUnit.MICROS)));
        return result(withdrawal, wallet, false);
    }

    // 같은 회수 작업으로 볼 내용: 금액·근거 번호·메모(메모가 없으면 없는 것끼리 같다)
    private static boolean sameContent(PromotionWithdrawal withdrawal, PromotionWithdrawalCommand command) {
        return withdrawal.getAmountHp() == command.amountHp()
                && withdrawal.getReferenceNo().equals(command.referenceNo())
                && Objects.equals(withdrawal.getMemo(), command.memo());
    }

    // 락을 잡은 지갑은 원장 기록으로 바뀐 같은 영속 객체라 회수가 반영된 가용 잔액을 읽는다
    private static PromotionWithdrawalResult result(PromotionWithdrawal withdrawal, Wallet wallet, boolean replayed) {
        return new PromotionWithdrawalResult(withdrawal.getId(), withdrawal.getWithdrawalWorkId(),
                withdrawal.getAmountHp(), wallet.balanceOf(PointType.PROMOTION, BalanceBucket.AVAILABLE), replayed);
    }

    private PromotionWithdrawal save(PromotionWithdrawal withdrawal) {
        try {
            return promotionWithdrawalRepository.saveAndFlush(withdrawal);
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateWithdrawalWorkId(exception)) {
                throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception);
            }
            throw exception;
        }
    }

    // 예외 원인의 PostgreSQL 오류가 UNIQUE 위반(23505)이고 제약 이름이 회수 작업 ID UNIQUE인지 확인한다(서버 언어와 무관).
    static boolean isDuplicateWithdrawalWorkId(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgresException) {
                ServerErrorMessage serverError = postgresException.getServerErrorMessage();
                return serverError != null
                        && UNIQUE_VIOLATION.equals(serverError.getSQLState())
                        && WITHDRAWAL_WORK_ID_UNIQUE.equals(serverError.getConstraint());
            }
        }
        return false;
    }
}

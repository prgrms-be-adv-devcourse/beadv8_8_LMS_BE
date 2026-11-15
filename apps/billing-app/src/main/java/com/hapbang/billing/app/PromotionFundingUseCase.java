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
import com.hapbang.billing.domain.PromotionFunding;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.PromotionFundingRepository;
import com.hapbang.billing.out.WalletRepository;

import lombok.RequiredArgsConstructor;

// 관리자 프로모션 지갑 충전. 충전 작업 ID 하나에 충전 기록 한 건과 원장 한 줄(PROMOTION_FUND, 가용 칸 +)을 한 트랜잭션에 남긴다.
// - 프로모션 지갑 락 아래에서 같은 충전 작업 ID의 기록을 찾는다. 같은 작업 ID의 요청은 이 락으로 직렬화되어, 뒤 요청은 먼저 커밋된 기록을 본다.
// - 기록이 있고 금액·출처·근거 번호·메모가 같으면 원장을 다시 쓰지 않고 처음 결과를 돌려준다(replayed). 다르면 IDEMPOTENCY_KEY_CONFLICT다.
// - 충전한 관리자는 내용으로 보지 않는다(같은 작업의 재요청이면 누가 보냈든 처음 기록을 돌려준다).
// - 충전 작업 ID UNIQUE가 최종 보장이고, 그 충돌은 IDEMPOTENCY_KEY_CONFLICT로 바꿔 전체를 롤백한다(보류 생성과 같은 방식).
@Service
@RequiredArgsConstructor
public class PromotionFundingUseCase {

    static final String FUNDING_WORK_ID_UNIQUE = "uk_promotion_funding_funding_work_id";
    static final String UNIQUE_VIOLATION = "23505";

    private final WalletRepository walletRepository;
    private final PromotionFundingRepository promotionFundingRepository;
    private final LedgerRecordUseCase ledgerRecordUseCase;

    @Transactional
    public PromotionFundingResult fund(Long adminUserId, PromotionFundingCommand command) {
        Objects.requireNonNull(adminUserId, "충전한 관리자 ID는 필수입니다.");
        // 0 HP 원장은 만들지 않는다. 재요청 확인보다 먼저 거절해 아무것도 잠그거나 읽지 않는다
        if (command.amountHp() <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
        Wallet wallet = ledgerRecordUseCase.lockWallet(
                PromotionWalletQueryUseCase.promotionWallet(walletRepository).getId());

        Optional<PromotionFunding> existing = promotionFundingRepository.findByFundingWorkId(command.fundingWorkId());
        if (existing.isPresent()) {
            if (!sameContent(existing.get(), command)) {
                throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return result(existing.get(), wallet, true);
        }

        PromotionFunding funding = save(PromotionFunding.of(command.fundingWorkId(), wallet.getId(),
                command.amountHp(), command.sourceType(), command.sourceRef(), command.memo(), adminUserId));
        ledgerRecordUseCase.record(PromotionLedgerOperations.fund(funding,
                Instant.now().truncatedTo(ChronoUnit.MICROS)));
        return result(funding, wallet, false);
    }

    // 같은 충전 작업으로 볼 내용: 금액·출처·근거 번호·메모(메모가 없으면 없는 것끼리 같다)
    private static boolean sameContent(PromotionFunding funding, PromotionFundingCommand command) {
        return funding.getAmountHp() == command.amountHp()
                && funding.getSourceType() == command.sourceType()
                && funding.getSourceRef().equals(command.sourceRef())
                && Objects.equals(funding.getMemo(), command.memo());
    }

    // 락을 잡은 지갑은 원장 기록으로 바뀐 같은 영속 객체라 충전이 반영된 가용 잔액을 읽는다
    private static PromotionFundingResult result(PromotionFunding funding, Wallet wallet, boolean replayed) {
        return new PromotionFundingResult(funding.getId(), funding.getFundingWorkId(), funding.getAmountHp(),
                wallet.balanceOf(PointType.PROMOTION, BalanceBucket.AVAILABLE), replayed);
    }

    private PromotionFunding save(PromotionFunding funding) {
        try {
            return promotionFundingRepository.saveAndFlush(funding);
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateFundingWorkId(exception)) {
                throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception);
            }
            throw exception;
        }
    }

    // 예외 원인의 PostgreSQL 오류가 UNIQUE 위반(23505)이고 제약 이름이 충전 작업 ID UNIQUE인지 확인한다(서버 언어와 무관).
    static boolean isDuplicateFundingWorkId(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgresException) {
                ServerErrorMessage serverError = postgresException.getServerErrorMessage();
                return serverError != null
                        && UNIQUE_VIOLATION.equals(serverError.getSQLState())
                        && FUNDING_WORK_ID_UNIQUE.equals(serverError.getConstraint());
            }
        }
        return false;
    }
}

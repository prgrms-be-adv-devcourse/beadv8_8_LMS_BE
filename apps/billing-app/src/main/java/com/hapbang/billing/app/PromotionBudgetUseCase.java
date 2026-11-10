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
import com.hapbang.billing.domain.BudgetRelease;
import com.hapbang.billing.domain.BudgetReservation;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.BudgetReleaseRepository;
import com.hapbang.billing.out.BudgetReservationRepository;
import com.hapbang.billing.out.WalletRepository;

import lombok.RequiredArgsConstructor;

// 쿠폰 예산 예약·해제. 요청 하나를 한 트랜잭션으로 처리하고, 거절하면 아무것도 저장하지 않는다(같은 작업 ID로 다시 보낼 수 있다).
// 락 순서: 프로모션 지갑 → 예약 행. 주문 항목 보류(구매자 지갑 → 프로모션 지갑 → 예약 쿠폰 ID 오름차순)와 같은 방향이다.
// 같은 작업 ID·같은 쿠폰의 요청은 프로모션 지갑 락으로 직렬화되어, 뒤 요청은 먼저 커밋된 기록을 본다. UNIQUE는 마지막 보장이다.
// 예약과 해제의 작업 ID는 한 이름 공간이다(작업 조회가 작업 ID 하나로 찾는다). 다른 종류에 쓴 작업 ID는 IDEMPOTENCY_KEY_CONFLICT다.
@Service
@RequiredArgsConstructor
public class PromotionBudgetUseCase {

    static final String UNIQUE_VIOLATION = "23505";
    static final String RESERVATION_COUPON_UNIQUE = "uk_budget_reservation_coupon_id";
    static final String RESERVATION_WORK_ID_UNIQUE = "uk_budget_reservation_work_id";
    static final String RELEASE_WORK_ID_UNIQUE = "uk_budget_release_work_id";

    private final WalletRepository walletRepository;
    private final BudgetReservationRepository budgetReservationRepository;
    private final BudgetReleaseRepository budgetReleaseRepository;
    private final LedgerRecordUseCase ledgerRecordUseCase;

    // 예약: 프로모션 지갑 가용 → 예약 칸으로 예약 총액을 옮긴다(원장 BUDGET_RESERVE 두 줄).
    // 같은 작업 ID·같은 내용이면 처음 결과, 다른 내용이면 IDEMPOTENCY_KEY_CONFLICT. 재요청 확인을 먼저 해, 이미 예약한 작업은
    // 그 뒤 가용 잔액이 줄었어도 처음 결과를 받는다. 같은 쿠폰에 다른 작업 ID의 예약이 있으면 BUDGET_RESERVATION_EXISTS,
    // 가용 잔액이 모자라면 PROMOTION_BALANCE_INSUFFICIENT다.
    @Transactional
    public BudgetReserveResult reserve(BudgetReserveCommand command) {
        requirePositive(command.amountHp());
        Wallet wallet = lockPromotionWallet();

        Optional<BudgetReservation> existing = budgetReservationRepository.findByWorkId(command.workId());
        if (existing.isPresent()) {
            if (!sameContent(existing.get(), command)) {
                throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return reserveResult(existing.get(), true);
        }
        if (budgetReleaseRepository.findByWorkId(command.workId()).isPresent()) {
            throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        if (budgetReservationRepository.findByCouponId(command.couponId()).isPresent()) {
            throw new BillingException(BillingErrorCode.BUDGET_RESERVATION_EXISTS);
        }
        if (wallet.balanceOf(PointType.PROMOTION, BalanceBucket.AVAILABLE) < command.amountHp()) {
            throw new BillingException(BillingErrorCode.PROMOTION_BALANCE_INSUFFICIENT);
        }

        BudgetReservation reservation = saveReservation(BudgetReservation.reserve(command.workId(), wallet.getId(),
                command.couponId(), command.policyVersion(), command.amountHp()));
        ledgerRecordUseCase.record(PromotionLedgerOperations.reserve(reservation, now()));
        return reserveResult(reservation, false);
    }

    // 해제: 예약의 남은 예산에서 빼 예약 칸 → 프로모션 지갑 가용 칸으로 되돌린다(원장 BUDGET_RELEASE 두 줄).
    // 같은 작업 ID·같은 내용이면 처음 결과, 다른 내용이면 IDEMPOTENCY_KEY_CONFLICT. 예약이 없으면 BUDGET_RESERVATION_NOT_FOUND,
    // 남은 예산보다 많으면 BUDGET_RELEASE_EXCEEDS_REMAINING이다(보류 중인 예산은 해제하지 않는다).
    @Transactional
    public BudgetReleaseResult release(BudgetReleaseCommand command) {
        requirePositive(command.amountHp());
        lockPromotionWallet();

        Optional<BudgetRelease> existing = budgetReleaseRepository.findByWorkId(command.workId());
        if (existing.isPresent()) {
            if (!sameContent(existing.get(), command)) {
                throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            BudgetReservation reservation = budgetReservationRepository.findById(command.reservationId())
                    .orElseThrow(() -> new IllegalStateException("해제 기록의 예산 예약이 없습니다."));
            return releaseResult(existing.get(), reservation, true);
        }
        if (budgetReservationRepository.findByWorkId(command.workId()).isPresent()) {
            throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }

        BudgetReservation reservation = budgetReservationRepository.findByIdForUpdate(command.reservationId())
                .orElseThrow(() -> new BillingException(BillingErrorCode.BUDGET_RESERVATION_NOT_FOUND));
        reservation.release(command.amountHp());
        BudgetRelease release = saveRelease(BudgetRelease.of(command.workId(), reservation.getId(),
                command.amountHp(), command.target(), command.reason()));
        ledgerRecordUseCase.record(PromotionLedgerOperations.release(reservation, release, now()));
        return releaseResult(release, reservation, false);
    }

    private Wallet lockPromotionWallet() {
        return ledgerRecordUseCase.lockWallet(PromotionWalletQueryUseCase.promotionWallet(walletRepository).getId());
    }

    // 0 HP 원장은 만들지 않는다. 재요청 확인보다 먼저 거절해 아무것도 잠그거나 읽지 않는다
    private static void requirePositive(long amountHp) {
        if (amountHp <= 0) {
            throw new BillingException(BillingErrorCode.INVALID_POINT_AMOUNT);
        }
    }

    // 같은 예약 작업으로 볼 내용: 쿠폰·정책 버전·금액
    private static boolean sameContent(BudgetReservation reservation, BudgetReserveCommand command) {
        return reservation.getCouponId() == command.couponId()
                && reservation.getPolicyVersion() == command.policyVersion()
                && reservation.getAmountHp() == command.amountHp();
    }

    // 같은 해제 작업으로 볼 내용: 예약·금액·대상·사유(사유가 없으면 없는 것끼리 같다)
    private static boolean sameContent(BudgetRelease release, BudgetReleaseCommand command) {
        return release.getBudgetReservationId() == command.reservationId()
                && release.getAmountHp() == command.amountHp()
                && release.getTarget().equals(command.target())
                && Objects.equals(release.getReason(), command.reason());
    }

    private static BudgetReserveResult reserveResult(BudgetReservation reservation, boolean replayed) {
        return new BudgetReserveResult(reservation.getWorkId(), reservation.getId(), reservation.getCouponId(),
                reservation.getAmountHp(), replayed);
    }

    private static BudgetReleaseResult releaseResult(BudgetRelease release, BudgetReservation reservation,
            boolean replayed) {
        return new BudgetReleaseResult(release.getWorkId(), reservation.getId(), release.getAmountHp(),
                reservation.getRemainingHp(), replayed);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    // 예약을 저장한다. 지갑 락 아래라 UNIQUE 충돌은 생기지 않아야 하지만, 생기면 쿠폰 충돌은 BUDGET_RESERVATION_EXISTS,
    // 작업 ID 충돌은 IDEMPOTENCY_KEY_CONFLICT로 바꿔 전체를 롤백시킨다.
    private BudgetReservation saveReservation(BudgetReservation reservation) {
        try {
            return budgetReservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException exception) {
            if (isUniqueViolation(exception, RESERVATION_COUPON_UNIQUE)) {
                throw new BillingException(BillingErrorCode.BUDGET_RESERVATION_EXISTS, exception);
            }
            if (isUniqueViolation(exception, RESERVATION_WORK_ID_UNIQUE)) {
                throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception);
            }
            throw exception;
        }
    }

    private BudgetRelease saveRelease(BudgetRelease release) {
        try {
            return budgetReleaseRepository.saveAndFlush(release);
        } catch (DataIntegrityViolationException exception) {
            if (isUniqueViolation(exception, RELEASE_WORK_ID_UNIQUE)) {
                throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception);
            }
            throw exception;
        }
    }

    // 예외 원인의 PostgreSQL 오류가 UNIQUE 위반(23505)이고 제약 이름이 주어진 이름인지 확인한다(서버 언어와 무관).
    static boolean isUniqueViolation(DataIntegrityViolationException exception, String constraint) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgresException) {
                ServerErrorMessage serverError = postgresException.getServerErrorMessage();
                return serverError != null
                        && UNIQUE_VIOLATION.equals(serverError.getSQLState())
                        && constraint.equals(serverError.getConstraint());
            }
        }
        return false;
    }
}

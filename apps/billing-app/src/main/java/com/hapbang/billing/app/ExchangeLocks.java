package com.hapbang.billing.app;

import java.util.List;

import org.springframework.stereotype.Component;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.Exchange;
import com.hapbang.billing.domain.PointHold;
import com.hapbang.billing.domain.PointHoldStatus;
import com.hapbang.billing.domain.PointHoldType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.domain.WalletType;
import com.hapbang.billing.out.ExchangeRepository;
import com.hapbang.billing.out.PointHoldRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 신청자가 아닌 쪽(관리자 승인·거절 #294, 송금 실행 #304)의 환전 잠금과 보류 정합성 확인. 호출한 쪽 트랜잭션 안에서 쓴다.
// 잠금 순서(지갑 → 환전 → 보류, #285 계약): ① 환전의 지갑 ID를 락 없이 읽고 ② 그 지갑 ③ 환전을 잠근 뒤 최신 상태로 다시 읽고
// ④ 보류를 잠가 정합성을 확인한다. 회원 취소(ExchangeProcessing.cancel)도 같은 순서라 서로 경쟁하면 지갑 락에서 차례가 정해진다.
@Slf4j
@Component
@RequiredArgsConstructor
class ExchangeLocks {

    private final ExchangeRepository exchangeRepository;
    private final PointHoldRepository pointHoldRepository;
    private final WalletRepository walletRepository;
    private final LedgerRecordUseCase ledgerRecordUseCase;
    private final EntityManager entityManager;

    // 지갑 → 환전 순서로 잠그고 환전을 DB 최신 상태로 다시 읽는다. 지갑 ID는 엔티티를 올리지 않고 읽는다(잠금 전 상태로 판단하지 않게).
    Exchange lock(Long exchangeId) {
        Long walletId = walletIdOf(exchangeId);
        ledgerRecordUseCase.lockWallet(walletId);
        return lockExchange(exchangeId, walletId);
    }

    // 송금 결과 반영용(#336): 신청자 지갑과 플랫폼 지갑을 ID 오름차순으로 함께 잠근 뒤 환전을 잠근다. 송금이 성공하면 같은 트랜잭션에서
    // 플랫폼 지갑에 수수료 수입을 기록하므로, 보류보다 먼저 두 지갑을 잡아 지갑 → 환전 → 보류 순서를 지킨다. 플랫폼 지갑 ID를 함께 돌려준다.
    // 플랫폼 지갑이 없으면 마이그레이션이 빠진 것이라 EXCHANGE_STATE_INCONSISTENT(처리 중단)다.
    LockedForPayout lockForPayout(Long exchangeId) {
        Long walletId = walletIdOf(exchangeId);
        Long platformWalletId = walletRepository.findByWalletType(WalletType.PLATFORM).map(Wallet::getId)
                .orElseThrow(() -> {
                    log.error("플랫폼 지갑이 없어 환전 송금 결과를 반영하지 않는다 exchangeId={}", exchangeId);
                    return new BillingException(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
                });
        ledgerRecordUseCase.lockWallets(List.of(walletId, platformWalletId));
        return new LockedForPayout(lockExchange(exchangeId, walletId), platformWalletId);
    }

    private Long walletIdOf(Long exchangeId) {
        return exchangeRepository.findWalletIdById(exchangeId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.EXCHANGE_NOT_FOUND));
    }

    private Exchange lockExchange(Long exchangeId, Long walletId) {
        Exchange exchange = exchangeRepository.findByIdForUpdate(exchangeId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.EXCHANGE_NOT_FOUND));
        entityManager.refresh(exchange);
        if (!exchange.getWalletId().equals(walletId)) {
            throw new BillingException(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
        }
        return exchange;
    }

    // 환전 신청의 보류를 잠그고(지갑·환전 다음) 손대지 않은 보류인지 확인한다: 이 환전의 수익 HP 전용 보류이고, 보류 상태(HELD)이며,
    // 남은 수익 HP 보류가 신청 금액 그대로이고 충전 HP는 없다. 맞지 않으면 EXCHANGE_STATE_INCONSISTENT(처리 중단, 로그로 원인 확인).
    void requireFullyHeld(Exchange exchange) {
        if (exchange.getPointHoldId() == null) {
            throw inconsistent(exchange, "보류 없음");
        }
        PointHold hold = pointHoldRepository.findById(exchange.getPointHoldId())
                .orElseThrow(() -> inconsistent(exchange, "보류 행 없음"));
        entityManager.refresh(hold, LockModeType.PESSIMISTIC_WRITE);
        boolean consistent = hold.getWalletId().equals(exchange.getWalletId())
                && hold.getReferenceType() == BillingReferenceType.EXCHANGE
                && hold.getReferenceId().equals(exchange.getId())
                && hold.getHoldType() == PointHoldType.REVENUE_ONLY
                && hold.getStatus() == PointHoldStatus.HELD
                && hold.getHeldRevenueHp().longValue() == exchange.getRequestedAmountHp().longValue()
                && hold.getHeldChargeHp().longValue() == 0L;
        if (!consistent) {
            throw inconsistent(exchange, "holdId=" + hold.getId() + ", status=" + hold.getStatus() + ", type="
                    + hold.getHoldType() + ", heldRevenue=" + hold.getHeldRevenueHp());
        }
    }

    record LockedForPayout(Exchange exchange, Long platformWalletId) {
    }

    private BillingException inconsistent(Exchange exchange, String detail) {
        log.error("환전 보류 정합성 불일치로 처리를 중단한다 exchangeId={}, status={}, {}", exchange.getId(),
                exchange.getStatus(), detail);
        return new BillingException(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
    }
}

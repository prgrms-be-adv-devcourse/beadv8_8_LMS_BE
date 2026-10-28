package com.hapbang.billing.app;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.Exchange;
import com.hapbang.billing.domain.ExchangeAccount;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.ExchangeAccountRepository;
import com.hapbang.billing.out.ExchangeRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;

import lombok.RequiredArgsConstructor;

// 수익 HP 환전 신청을 접수한다 (#285). 한 트랜잭션에서 신청(REQUESTED)·수익 HP 보류(REVENUE_ONLY)·테스트 계좌 스냅샷을 저장한다.
// 보류가 실패하면(수익 HP 부족 등) 신청도 남지 않는다. 실제 송금·은행 확인은 하지 않는다.
// - 금액: 1,000 HP 이상, 1,000 HP 단위. 가용 수익 HP에서만 보류한다(충전 HP는 환전할 수 없다).
// - 수수료: 신청 금액의 10%(원 미만 버림)를 신청 시 확정·저장한다. 예상 수령액(KRW) = 신청 금액 − 수수료.
// - 잠정 정산 예정일: 신청일(KST) 1~9일이면 당월 10일, 10일 이후면 다음 달 10일, 토·일이면 다음 월요일(공휴일 미반영,
//   실제 송금일 아님. ExchangeSettlementRound).
// - 멱등성: 같은 회원의 같은 Idempotency-Key는 처음 신청이다. 금액·계좌가 다르면 IDEMPOTENCY_KEY_CONFLICT.
// - 동시성: 지갑 락으로 같은 회원의 신청·보류를 직렬화해 가용 수익 HP보다 많이 보류하지 않는다. 다른 키의 신청은 여러 건 허용한다.
@Service
@RequiredArgsConstructor
public class ExchangeRequestUseCase {

    private final WalletRepository walletRepository;
    private final ExchangeRepository exchangeRepository;
    private final ExchangeAccountRepository exchangeAccountRepository;
    private final LedgerRecordUseCase ledgerRecordUseCase;
    private final InternalHoldFacade internalHoldFacade;
    private final ExchangeAccountCipher exchangeAccountCipher;

    // 환전 신청 ID를 돌려준다. 수익 HP 가용 잔액이 모자라면 INSUFFICIENT_BALANCE(409)로 거절하고 아무것도 남기지 않는다.
    @Transactional
    public Long create(Long userId, String clientKey, ExchangeCommand command) {
        if (command.amountHp() < Exchange.UNIT_HP || command.amountHp() % Exchange.UNIT_HP != 0) {
            throw new BillingException(BillingErrorCode.EXCHANGE_AMOUNT_INVALID);
        }
        Long walletId = walletRepository.findByUserId(userId)
                .map(Wallet::getId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.WALLET_NOT_FOUND));
        // 같은 회원의 환전 신청 접수를 지갑 락으로 직렬화한다(같은 키 동시 요청은 뒤의 요청이 처음 신청을 본다).
        ledgerRecordUseCase.lockWallet(walletId);
        String key = BillingKeys.exchangeRequest(walletId, clientKey);
        String holderName = command.accountHolderName().strip();
        Optional<Exchange> existing = exchangeRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            requireSameRequest(existing.get(), command, holderName);
            return existing.get().getId();
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Exchange exchange = exchangeRepository.save(Exchange.requested(userId, walletId, command.amountHp(), key,
                ExchangeSettlementRound.of(now), now));
        HoldResult hold = internalHoldFacade.hold(new InternalHoldCommand(userId, BillingReferenceType.EXCHANGE,
                exchange.getId(), command.amountHp()));
        if (hold.replayed() || hold.revenueHp() != command.amountHp() || hold.chargeHp() != 0
                || !hold.walletId().equals(walletId)) {
            throw new BillingException(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
        }
        exchange.attachHold(hold.holdId());
        exchangeAccountRepository.save(ExchangeAccount.unverified(exchange, command.bankCode(),
                exchangeAccountCipher.encrypt(command.accountNumber()), holderName));
        return exchange.getId();
    }

    // 같은 키의 재요청은 금액과 계좌(은행·계좌번호·예금주)가 모두 같아야 한다.
    private void requireSameRequest(Exchange exchange, ExchangeCommand command, String holderName) {
        ExchangeAccount account = exchangeAccountRepository.findByExchangeId(exchange.getId())
                .orElseThrow(() -> new BillingException(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT));
        boolean same = exchange.getRequestedAmountHp() == command.amountHp()
                && account.getBankCode().equals(command.bankCode())
                && account.getAccountHolderName().equals(holderName)
                && exchangeAccountCipher.matches(exchange.getId(), account.getAccountNumberEncrypted(),
                        command.accountNumber());
        if (!same) {
            throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
    }
}

package com.hapbang.billing.app;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

// 회원 자신의 환전 신청 조회·취소 (#285). 다른 회원의 신청은 없는 신청과 같게 본다(EXCHANGE_NOT_FOUND).
// 취소: 지갑 → 환전 신청 → 보류 순서로 잠근다(신청 접수·후속 승인·송금 처리도 같은 순서를 지킨다).
// 관리자 승인 전(REQUESTED)만 취소할 수 있고, 보류한 수익 HP를 전부 수익 HP로 반환한다. 이미 취소한 신청은 반환하지 않고 현재 결과를 돌려준다.
@Service
@RequiredArgsConstructor
public class ExchangeProcessing {

    private final WalletRepository walletRepository;
    private final ExchangeRepository exchangeRepository;
    private final ExchangeAccountRepository exchangeAccountRepository;
    private final LedgerRecordUseCase ledgerRecordUseCase;
    private final InternalHoldFacade internalHoldFacade;
    private final ExchangeAccountCipher exchangeAccountCipher;
    private final EntityManager entityManager;

    @Transactional
    public ExchangeResult cancel(Long userId, Long exchangeId) {
        Long walletId = walletIdOf(userId);
        ledgerRecordUseCase.lockWallet(walletId);
        Exchange exchange = exchangeRepository.findByIdForUpdate(exchangeId)
                .filter(found -> found.getWalletId().equals(walletId))
                .orElseThrow(() -> new BillingException(BillingErrorCode.EXCHANGE_NOT_FOUND));
        entityManager.refresh(exchange);
        if (!exchange.isCanceled()) {
            exchange.cancel(Instant.now().truncatedTo(ChronoUnit.MICROS));
            ReleaseResult released = internalHoldFacade.release(InternalReleaseCommand.full(userId,
                    BillingReferenceType.EXCHANGE, exchange.getId(), BillingKeys.exchangeCancelRequestId(exchange.getId())));
            if (released.releasedRevenueHp() != exchange.getRequestedAmountHp() || released.releasedChargeHp() != 0
                    || released.remainingHeldHp() != 0) {
                throw new BillingException(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
            }
        }
        return resultOf(exchange);
    }

    @Transactional(readOnly = true)
    public ExchangeResult get(Long userId, Long exchangeId) {
        Long walletId = walletIdOf(userId);
        Exchange exchange = exchangeRepository.findById(exchangeId)
                .filter(found -> found.getWalletId().equals(walletId))
                .orElseThrow(() -> new BillingException(BillingErrorCode.EXCHANGE_NOT_FOUND));
        return resultOf(exchange);
    }

    // 내 환전 신청 목록(최신순)
    @Transactional(readOnly = true)
    public Page<ExchangeResult> list(Long userId, Pageable pageable) {
        Long walletId = walletIdOf(userId);
        Page<Exchange> page = exchangeRepository.findAllByWalletIdOrderByIdDesc(walletId, pageable);
        List<Long> ids = page.getContent().stream().map(Exchange::getId).toList();
        Map<Long, ExchangeAccount> accounts = ids.isEmpty() ? Map.of()
                : exchangeAccountRepository.findAllByExchangeIdIn(ids).stream()
                        .collect(Collectors.toMap(account -> account.getExchange().getId(), Function.identity()));
        return page.map(exchange -> resultOf(exchange, accounts.get(exchange.getId())));
    }

    private ExchangeResult resultOf(Exchange exchange) {
        return resultOf(exchange, exchangeAccountRepository.findByExchangeId(exchange.getId()).orElse(null));
    }

    private ExchangeResult resultOf(Exchange exchange, ExchangeAccount account) {
        if (account == null) {
            throw new BillingException(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
        }
        return ExchangeResult.of(exchange, account,
                exchangeAccountCipher.masked(exchange.getId(), account.getAccountNumberEncrypted()));
    }

    // 지갑이 없으면 신청도 없으므로 EXCHANGE_NOT_FOUND와 구분해 WALLET_NOT_FOUND로 알린다.
    private Long walletIdOf(Long userId) {
        return walletRepository.findByUserId(userId)
                .map(Wallet::getId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.WALLET_NOT_FOUND));
    }
}

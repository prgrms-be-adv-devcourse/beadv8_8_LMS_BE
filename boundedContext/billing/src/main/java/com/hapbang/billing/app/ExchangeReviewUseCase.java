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
import com.hapbang.billing.domain.BillingUser;
import com.hapbang.billing.domain.Exchange;
import com.hapbang.billing.domain.ExchangeAccount;
import com.hapbang.billing.domain.ExchangeStatus;
import com.hapbang.billing.out.BillingUserRepository;
import com.hapbang.billing.out.ExchangeAccountRepository;
import com.hapbang.billing.out.ExchangeRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 관리자 환전 승인·거절과 조회 (#294). 역할 검사는 ExchangeAdminFacade가 한다.
// 잠금 순서(지갑 → 환전 → 보류, #285 계약)와 보류 정합성 확인은 ExchangeLocks(송금 실행 #304와 공유): ① 환전의 지갑 ID를 락 없이
// 읽고 ② 그 지갑 ③ 환전을 잠근 뒤 최신 상태로 다시 판단하고 ④ 보류를 잠가 정합성을 확인한다. 회원 취소(ExchangeProcessing.cancel)도
// 같은 순서라 승인·거절·취소가 경쟁하면 지갑 락에서 차례가 정해지고 먼저 커밋한 하나만 상태를 바꾼다(뒤쪽은 바뀐 상태를 보고 409).
// - 승인: REQUESTED → APPROVED. 신청자 Billing 회원 복제본이 정상 상태의 인플루언서여야 한다(없으면 EXCHANGE_APPLICANT_NOT_SYNCED,
//   자격 없음 EXCHANGE_APPLICANT_NOT_ELIGIBLE, 둘 다 409). 보류 유지, 확정 차감·송금 없음, 잠정 정산 예정일 그대로
// - 거절: REQUESTED → REJECTED + 보류 전액 반환(같은 트랜잭션). 신청자 회원 상태와 무관하다. 반환은 신청자 회원 ID로 한다
// - 멱등: 같은 관리자의 승인 재요청, 같은 관리자·같은 사유의 거절 재요청은 처음 결과(재반환 없음). 그 밖의 재요청은 EXCHANGE_ALREADY_REVIEWED
// - 보류 정합성(환전 대상·REVENUE_ONLY·HELD·남은 수익 HP 보류 = 신청 금액·충전 HP 0)이 맞지 않으면 승인·거절 모두 중단(500)
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeReviewUseCase {

    private final ExchangeRepository exchangeRepository;
    private final ExchangeAccountRepository exchangeAccountRepository;
    private final BillingUserRepository billingUserRepository;
    private final ExchangeLocks exchangeLocks;
    private final InternalHoldFacade internalHoldFacade;
    private final ExchangeAccountCipher exchangeAccountCipher;

    @Transactional
    public AdminExchangeResult approve(Long adminId, Long exchangeId) {
        Exchange exchange = exchangeLocks.lock(exchangeId);
        if (exchange.isApprovedBy(adminId)) {
            return resultOf(exchange);
        }
        if (exchange.getStatus() != ExchangeStatus.REQUESTED) {
            throw new BillingException(BillingErrorCode.EXCHANGE_ALREADY_REVIEWED);
        }
        BillingUser applicant = billingUserRepository.findById(exchange.getUserId())
                .orElseThrow(() -> new BillingException(BillingErrorCode.EXCHANGE_APPLICANT_NOT_SYNCED));
        if (!applicant.canReceiveExchangeApproval()) {
            throw new BillingException(BillingErrorCode.EXCHANGE_APPLICANT_NOT_ELIGIBLE);
        }
        exchangeLocks.requireFullyHeld(exchange);
        exchange.approve(adminId, now());
        log.info("환전 승인 exchangeId={}, adminId={}", exchangeId, adminId);
        return resultOf(exchange);
    }

    @Transactional
    public AdminExchangeResult reject(Long adminId, Long exchangeId, String reason) {
        String normalized = Exchange.normalizeRejectionReason(reason);
        Exchange exchange = exchangeLocks.lock(exchangeId);
        if (exchange.isRejectedBy(adminId, normalized)) {
            return resultOf(exchange);
        }
        if (exchange.getStatus() != ExchangeStatus.REQUESTED) {
            throw new BillingException(BillingErrorCode.EXCHANGE_ALREADY_REVIEWED);
        }
        exchangeLocks.requireFullyHeld(exchange);
        exchange.reject(adminId, normalized, now());
        ReleaseResult released = internalHoldFacade.release(InternalReleaseCommand.full(exchange.getUserId(),
                BillingReferenceType.EXCHANGE, exchange.getId(), BillingKeys.exchangeRejectRequestId(exchange.getId())));
        if (released.replayed() || released.releasedRevenueHp() != exchange.getRequestedAmountHp()
                || released.releasedChargeHp() != 0 || released.remainingHeldHp() != 0) {
            throw new BillingException(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
        }
        log.info("환전 거절 exchangeId={}, adminId={}", exchangeId, adminId);
        return resultOf(exchange);
    }

    @Transactional(readOnly = true)
    public AdminExchangeResult get(Long exchangeId) {
        return resultOf(exchangeRepository.findById(exchangeId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.EXCHANGE_NOT_FOUND)));
    }

    // 상태별 목록. 승인 대기(REQUESTED)는 오래된 신청부터, 그 밖의 상태는 최신부터
    @Transactional(readOnly = true)
    public Page<AdminExchangeResult> list(ExchangeStatus status, Pageable pageable) {
        Page<Exchange> page = status == ExchangeStatus.REQUESTED
                ? exchangeRepository.findAllByStatusOrderByIdAsc(status, pageable)
                : exchangeRepository.findAllByStatusOrderByIdDesc(status, pageable);
        List<Long> ids = page.getContent().stream().map(Exchange::getId).toList();
        Map<Long, ExchangeAccount> accounts = ids.isEmpty() ? Map.of()
                : exchangeAccountRepository.findAllByExchangeIdIn(ids).stream()
                        .collect(Collectors.toMap(account -> account.getExchange().getId(), Function.identity()));
        return page.map(exchange -> resultOf(exchange, accounts.get(exchange.getId())));
    }

    private AdminExchangeResult resultOf(Exchange exchange) {
        return resultOf(exchange, exchangeAccountRepository.findByExchangeId(exchange.getId()).orElse(null));
    }

    private AdminExchangeResult resultOf(Exchange exchange, ExchangeAccount account) {
        if (account == null) {
            throw new BillingException(BillingErrorCode.EXCHANGE_STATE_INCONSISTENT);
        }
        BillingUser applicant = billingUserRepository.findById(exchange.getUserId()).orElse(null);
        return AdminExchangeResult.of(exchange, account,
                exchangeAccountCipher.masked(exchange.getId(), account.getAccountNumberEncrypted()), applicant);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}

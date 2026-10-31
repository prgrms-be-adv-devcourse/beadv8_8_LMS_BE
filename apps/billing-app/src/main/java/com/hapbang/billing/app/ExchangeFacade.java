package com.hapbang.billing.app;

import java.util.regex.Pattern;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;

import lombok.RequiredArgsConstructor;

// 수익 HP 환전 신청·조회·취소 진입점 (#285). 환전은 인플루언서·인증된 인플루언서만 한다(루트 AGENTS.md 6장, 유저·관리자 403).
// 트랜잭션은 UseCase가 연다. 계좌번호 암호화 키가 없으면 신청 전에 EXCHANGE_UNAVAILABLE(503)로 거절한다.
// 실제 은행 확인·송금은 하지 않는다(승인·정산일 송금·결과 처리는 후속 Issue).
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('INFLUENCER', 'VERIFIED_INFLUENCER')")
public class ExchangeFacade {

    // 클라이언트 Idempotency-Key 형식(신청 멱등키 EXCHANGE:{walletId}:{키} 길이 제한, ':' 금지)
    static final Pattern CLIENT_KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final ExchangeRequestUseCase exchangeRequestUseCase;
    private final ExchangeProcessing exchangeProcessing;
    private final ExchangeAccountCipher exchangeAccountCipher;

    // 환전을 신청(수익 HP 보류)하고 신청 결과를 돌려준다. 같은 Idempotency-Key 재요청은 처음 신청의 현재 결과다.
    public ExchangeResult request(Long userId, String idempotencyKey, ExchangeCommand command) {
        if (!exchangeAccountCipher.isConfigured()) {
            throw new BillingException(BillingErrorCode.EXCHANGE_UNAVAILABLE);
        }
        if (idempotencyKey == null || !CLIENT_KEY.matcher(idempotencyKey).matches()) {
            throw new BillingException(BillingErrorCode.EXCHANGE_IDEMPOTENCY_KEY_INVALID);
        }
        Long exchangeId = LockTimeouts.translate(() -> exchangeRequestUseCase.create(userId, idempotencyKey, command));
        return exchangeProcessing.get(userId, exchangeId);
    }

    public Page<ExchangeResult> list(Long userId, Pageable pageable) {
        return exchangeProcessing.list(userId, pageable);
    }

    public ExchangeResult get(Long userId, Long exchangeId) {
        return exchangeProcessing.get(userId, exchangeId);
    }

    // 관리자 승인 전(REQUESTED)인 내 환전 신청을 취소하고 보류한 수익 HP를 모두 반환한다.
    public ExchangeResult cancel(Long userId, Long exchangeId) {
        return LockTimeouts.translate(() -> exchangeProcessing.cancel(userId, exchangeId));
    }
}

package com.hapbang.billing.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.stereotype.Component;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.out.PaymentKeyCipher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 환전 테스트 계좌번호의 암호화·마스킹 (#285). 결제키와 같은 AES-256-GCM 키(PaymentKeyCipher, 무작위 IV)를 쓴다.
// 계좌번호 원문은 저장·응답·로그에 남기지 않는다. 응답에는 끝 4자리만 남기고 나머지를 *로 가린다.
@Slf4j
@Component
@RequiredArgsConstructor
class ExchangeAccountCipher {

    static final int VISIBLE_DIGITS = 4;
    static final String UNREADABLE_MASK = "****";

    private final PaymentKeyCipher paymentKeyCipher;

    boolean isConfigured() {
        return paymentKeyCipher.isConfigured();
    }

    String encrypt(String accountNumber) {
        return paymentKeyCipher.encrypt(accountNumber);
    }

    // 저장된 암호문이 이 계좌번호인지 원문으로 비교한다(같은 멱등키 재요청 비교용). 암호문은 무작위 IV라 같은 계좌번호도 매번 다르므로
    // 암호문 문자열끼리 비교하지 않는다. 복호화하지 못하면(키 없음·키 변경·손상) 같은지 알 수 없으므로 EXCHANGE_ACCOUNT_UNREADABLE이다
    // (다른 신청이나 충돌로 판단하지 않는다).
    boolean matches(Long exchangeId, String encrypted, String accountNumber) {
        String stored;
        try {
            stored = paymentKeyCipher.decrypt(encrypted);
        } catch (RuntimeException exception) {
            log.error("환전 계좌번호를 복호화하지 못해 같은 신청인지 확인할 수 없다: exchangeId={}, cause={}", exchangeId,
                    exception.getClass().getSimpleName());
            throw new BillingException(BillingErrorCode.EXCHANGE_ACCOUNT_UNREADABLE);
        }
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),
                accountNumber.getBytes(StandardCharsets.UTF_8));
    }

    // 송금 요청에 넣을 계좌번호 원문 (#304). 메모리에서만 쓰고 저장·로그에 남기지 않는다. 복호화하지 못하면 EXCHANGE_ACCOUNT_UNREADABLE
    String decrypt(Long exchangeId, String encrypted) {
        try {
            return paymentKeyCipher.decrypt(encrypted);
        } catch (RuntimeException exception) {
            log.error("환전 계좌번호를 복호화하지 못해 송금 요청을 만들 수 없다: exchangeId={}, cause={}", exchangeId,
                    exception.getClass().getSimpleName());
            throw new BillingException(BillingErrorCode.EXCHANGE_ACCOUNT_UNREADABLE);
        }
    }

    // 암호문을 마스킹한 계좌번호로 바꾼다. 키가 없거나 복호화할 수 없으면 전부 가린 값을 돌려준다(조회는 막지 않는다).
    String masked(Long exchangeId, String encrypted) {
        try {
            return mask(paymentKeyCipher.decrypt(encrypted));
        } catch (RuntimeException exception) {
            log.warn("환전 계좌번호를 복호화하지 못해 전부 가린다: exchangeId={}, cause={}", exchangeId,
                    exception.getClass().getSimpleName());
            return UNREADABLE_MASK;
        }
    }

    static String mask(String accountNumber) {
        int hidden = Math.max(accountNumber.length() - VISIBLE_DIGITS, 0);
        return "*".repeat(hidden) + accountNumber.substring(hidden);
    }
}

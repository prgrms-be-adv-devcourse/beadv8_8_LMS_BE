package com.hapbang.billing.out;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 결제키(paymentKey) 보호용 키 설정이다 (#224). 두 키는 서로 다른 값을 쓴다(암호화 키가 새도 HMAC으로 원문을 맞춰 볼 수 없게).
// - encryptionKey: AES-256-GCM 키(Base64, 32바이트). 결제키 암호문을 만들고 결과 확인 때 원문으로 되돌린다.
// - hmacKey: HMAC-SHA256 키(Base64, 32바이트 이상). 결제키 중복 식별용 해시를 만든다. 바꾸면 기존 해시와 맞지 않으므로 바꾸지 않는다.
// 값은 환경 변수(BILLING_PAYMENT_KEY_ENCRYPTION_KEY·BILLING_PAYMENT_KEY_HMAC_KEY)로만 넣고 코드·로그·Git에 두지 않는다.
@ConfigurationProperties(prefix = "hapbang.billing.payment-key")
public record PaymentKeyProperties(String encryptionKey, String hmacKey) {
}

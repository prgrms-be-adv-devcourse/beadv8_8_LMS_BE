package com.hapbang.billing.fixture;

import java.util.Base64;

import org.springframework.test.context.DynamicPropertyRegistry;

// 충전 테스트의 TossPayments·결제키 설정 (#224). 실제 키가 아닌 테스트 전용 값이다.
public final class ChargeFixture {

    public static final String SECRET_KEY = "test_sk_fake_for_integration_test";
    public static final String ENCRYPTION_KEY = key(11);
    public static final String HMAC_KEY = key(22);

    private ChargeFixture() {
    }

    // 가짜 TossPayments 서버를 쓰도록 충전 설정을 등록한다. 읽기 시간 제한은 1초다(시간 초과 재현용).
    public static void register(DynamicPropertyRegistry registry, FakeTossPaymentsServer server) {
        registry.add("hapbang.billing.toss.base-url", server::baseUrl);
        registry.add("hapbang.billing.toss.secret-key", () -> SECRET_KEY);
        registry.add("hapbang.billing.toss.read-timeout", () -> "1s");
        registry.add("hapbang.billing.payment-key.encryption-key", () -> ENCRYPTION_KEY);
        registry.add("hapbang.billing.payment-key.hmac-key", () -> HMAC_KEY);
    }

    // 결제창이 발급했다고 보는 결제키
    public static String paymentKey(String orderId) {
        return "tgen_" + orderId;
    }

    private static String key(int seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed * 17 + i);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}

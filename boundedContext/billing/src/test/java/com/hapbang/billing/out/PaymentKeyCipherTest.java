package com.hapbang.billing.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 결제키 암호화(AES-GCM, 무작위 IV)와 중복 식별용 HMAC, 키 설정 검증을 DB 없이 확인한다 (#224).
class PaymentKeyCipherTest {

    static final String ENCRYPTION_KEY = key(1);
    static final String HMAC_KEY = key(2);
    private static final String PAYMENT_KEY = "tgen_20261015100000abcDE1";

    private final PaymentKeyCipher cipher = new PaymentKeyCipher(new PaymentKeyProperties(ENCRYPTION_KEY, HMAC_KEY));

    @Test
    @DisplayName("같은 결제키도 암호문은 매번 다르고(무작위 IV) 평문을 담지 않으며, 복호화하면 원래 결제키다")
    void encryptRandomIv() {
        String first = cipher.encrypt(PAYMENT_KEY);
        String second = cipher.encrypt(PAYMENT_KEY);

        assertThat(first).startsWith("v1:").isNotEqualTo(second).doesNotContain(PAYMENT_KEY);
        assertThat(cipher.decrypt(first)).isEqualTo(PAYMENT_KEY);
        assertThat(cipher.decrypt(second)).isEqualTo(PAYMENT_KEY);
    }

    @Test
    @DisplayName("200자 결제키의 암호문은 컬럼 길이(512) 안에 들어간다")
    void encryptedLength() {
        assertThat(cipher.encrypt("k".repeat(200))).hasSizeLessThanOrEqualTo(512);
    }

    @Test
    @DisplayName("HMAC은 같은 결제키면 항상 같은 hex 64자, 다른 결제키·다른 HMAC 키면 다르다")
    void hash() {
        String hash = cipher.hash(PAYMENT_KEY);

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}").isEqualTo(cipher.hash(PAYMENT_KEY));
        assertThat(cipher.hash(PAYMENT_KEY + "x")).isNotEqualTo(hash);
        PaymentKeyCipher otherKey = new PaymentKeyCipher(new PaymentKeyProperties(ENCRYPTION_KEY, key(3)));
        assertThat(otherKey.hash(PAYMENT_KEY)).isNotEqualTo(hash);
    }

    @Test
    @DisplayName("변조된 암호문·형식이 다른 값은 복호화하지 않는다")
    void tampered() {
        String encrypted = cipher.encrypt(PAYMENT_KEY);
        byte[] payload = Base64.getDecoder().decode(encrypted.substring(3));
        payload[payload.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt("v1:" + Base64.getEncoder().encodeToString(payload)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(PAYMENT_KEY)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("두 키가 모두 없으면 꺼진 상태로 만들어지고, 쓰려고 하면 거절한다")
    void notConfigured() {
        PaymentKeyCipher disabled = new PaymentKeyCipher(new PaymentKeyProperties(null, " "));

        assertThat(disabled.isConfigured()).isFalse();
        assertThatThrownBy(() -> disabled.hash(PAYMENT_KEY)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("한 키만 있거나, 길이·형식이 틀리거나, 두 키가 같으면 설정 오류로 시작하지 않는다")
    void invalidConfiguration() {
        assertThatThrownBy(() -> new PaymentKeyCipher(new PaymentKeyProperties(ENCRYPTION_KEY, null)))
                .isInstanceOf(IllegalStateException.class);
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new PaymentKeyCipher(new PaymentKeyProperties(shortKey, HMAC_KEY)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PaymentKeyCipher(new PaymentKeyProperties(ENCRYPTION_KEY, shortKey)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PaymentKeyCipher(new PaymentKeyProperties("not base64!", HMAC_KEY)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PaymentKeyCipher(new PaymentKeyProperties(ENCRYPTION_KEY, ENCRYPTION_KEY)))
                .isInstanceOf(IllegalStateException.class);
    }

    // 테스트 전용 32바이트 키(Base64). 실제 키가 아니다
    static String key(int seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed * 31 + i);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}

package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.fixture.ChargeFixture;
import com.hapbang.billing.out.PaymentKeyCipher;
import com.hapbang.billing.out.PaymentKeyProperties;

// 환전 계좌번호 암호화·원문 비교·마스킹(#285)을 DB 없이 검증한다. 결제키와 같은 AES-256-GCM(무작위 IV)을 쓰므로
// 같은 계좌번호도 암호문이 매번 다르다 → 멱등성은 암호문이 아니라 복호화한 원문으로 비교해야 한다.
class ExchangeAccountCipherTest {

    private static final String ACCOUNT = "12345678901234";

    @Test
    @DisplayName("같은 계좌번호를 여러 번 암호화하면 암호문은 모두 다르지만 모두 같은 계좌로 판정한다")
    void sameAccountDifferentCiphertext() {
        ExchangeAccountCipher cipher = cipher(ChargeFixture.ENCRYPTION_KEY);
        Set<String> ciphertexts = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            ciphertexts.add(cipher.encrypt(ACCOUNT));
        }

        assertThat(ciphertexts).hasSize(20).allSatisfy(encrypted -> {
            assertThat(encrypted).startsWith("v1:").doesNotContain(ACCOUNT);
            assertThat(cipher.matches(1L, encrypted, ACCOUNT)).isTrue();
            assertThat(cipher.matches(1L, encrypted, "12345678901235")).isFalse();
            assertThat(cipher.matches(1L, encrypted, "1234567890123")).isFalse();
        });
    }

    @Test
    @DisplayName("애플리케이션을 다시 시작해도(같은 키의 새 인스턴스) 저장된 암호문을 복호화해 비교·마스킹할 수 있다")
    void sameKeyAfterRestart() {
        String stored = cipher(ChargeFixture.ENCRYPTION_KEY).encrypt(ACCOUNT);

        ExchangeAccountCipher restarted = cipher(ChargeFixture.ENCRYPTION_KEY);

        assertThat(restarted.matches(1L, stored, ACCOUNT)).isTrue();
        assertThat(restarted.masked(1L, stored)).isEqualTo("**********1234");
    }

    @Test
    @DisplayName("다른 키·손상된 암호문·키 없음으로 복호화하지 못하면 다른 계좌로 보지 않고 EXCHANGE_ACCOUNT_UNREADABLE이다. 조회 마스킹은 전부 가린다")
    void unreadable() {
        String stored = cipher(ChargeFixture.ENCRYPTION_KEY).encrypt(ACCOUNT);
        ExchangeAccountCipher otherKey = cipher(Base64.getEncoder().encodeToString(new byte[32]));
        ExchangeAccountCipher noKey = new ExchangeAccountCipher(new PaymentKeyCipher(new PaymentKeyProperties(null, null)));
        ExchangeAccountCipher sameKey = cipher(ChargeFixture.ENCRYPTION_KEY);

        assertUnreadable(() -> otherKey.matches(1L, stored, ACCOUNT));
        assertUnreadable(() -> sameKey.matches(1L, "v1:" + Base64.getEncoder().encodeToString(new byte[40]), ACCOUNT));
        assertUnreadable(() -> sameKey.matches(1L, "garbage", ACCOUNT));
        assertUnreadable(() -> noKey.matches(1L, stored, ACCOUNT));
        assertThat(noKey.isConfigured()).isFalse();
        assertThat(otherKey.masked(1L, stored)).isEqualTo(ExchangeAccountCipher.UNREADABLE_MASK);
        assertThat(noKey.masked(1L, stored)).isEqualTo(ExchangeAccountCipher.UNREADABLE_MASK);
    }

    private static void assertUnreadable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(BillingException.class)
                .satisfies(exception -> {
                    assertThat(((BillingException) exception).getErrorCode())
                            .isEqualTo(BillingErrorCode.EXCHANGE_ACCOUNT_UNREADABLE);
                    assertThat(exception.getMessage()).doesNotContain(ACCOUNT);
                });
    }

    private static ExchangeAccountCipher cipher(String encryptionKey) {
        return new ExchangeAccountCipher(new PaymentKeyCipher(new PaymentKeyProperties(encryptionKey,
                ChargeFixture.HMAC_KEY)));
    }
}

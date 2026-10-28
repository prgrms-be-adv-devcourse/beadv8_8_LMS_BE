package com.hapbang.billing.out;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

// PG 결제키를 암호화해 저장하고 중복 식별용 HMAC을 만든다 (#224, 루트 AGENTS.md 5장 "결제키 암호화").
// - 암호문: AES-256-GCM, 호출마다 무작위 12바이트 IV. 형식 "v1:" + Base64(IV ‖ 암호문 ‖ 인증 태그). 같은 키도 매번 다른 값이다.
// - 해시: HMAC-SHA256(별도 키)의 소문자 hex 64자. 같은 결제키는 항상 같은 값이라 UNIQUE로 중복 사용을 막는다.
// 두 키가 모두 없으면 꺼진 상태(isConfigured=false)로 뜨고 충전 API가 503으로 거절한다. 하나만 있거나 형식이 틀리면 설정 오류로 시작에 실패한다.
@Slf4j
@Component
@EnableConfigurationProperties(PaymentKeyProperties.class)
public class PaymentKeyCipher {

    static final String VERSION_PREFIX = "v1:";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final int AES_KEY_BYTES = 32;
    private static final int MIN_HMAC_KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec hmacKey;

    public PaymentKeyCipher(PaymentKeyProperties properties) {
        boolean hasEncryptionKey = hasText(properties.encryptionKey());
        boolean hasHmacKey = hasText(properties.hmacKey());
        if (!hasEncryptionKey && !hasHmacKey) {
            log.warn("결제키 암호화 키가 설정되지 않아 HP 충전을 사용할 수 없습니다 (hapbang.billing.payment-key.*)");
            this.encryptionKey = null;
            this.hmacKey = null;
            return;
        }
        if (!hasEncryptionKey || !hasHmacKey) {
            throw new IllegalStateException("결제키 암호화 키와 HMAC 키는 함께 설정해야 합니다 (hapbang.billing.payment-key.*)");
        }
        byte[] encryption = decodeKey(properties.encryptionKey(), "encryption-key");
        byte[] hmac = decodeKey(properties.hmacKey(), "hmac-key");
        if (encryption.length != AES_KEY_BYTES) {
            throw new IllegalStateException("결제키 암호화 키는 Base64로 인코딩한 32바이트여야 합니다 (hapbang.billing.payment-key.encryption-key)");
        }
        if (hmac.length < MIN_HMAC_KEY_BYTES) {
            throw new IllegalStateException("결제키 HMAC 키는 Base64로 인코딩한 32바이트 이상이어야 합니다 (hapbang.billing.payment-key.hmac-key)");
        }
        if (Arrays.equals(encryption, hmac)) {
            throw new IllegalStateException("결제키 암호화 키와 HMAC 키는 서로 다른 값이어야 합니다 (hapbang.billing.payment-key.*)");
        }
        this.encryptionKey = new SecretKeySpec(encryption, "AES");
        this.hmacKey = new SecretKeySpec(hmac, MAC_ALGORITHM);
    }

    // 두 키가 모두 설정되어 암호화·해시를 쓸 수 있는지 확인한다.
    public boolean isConfigured() {
        return encryptionKey != null;
    }

    // 결제키를 무작위 IV로 암호화한다.
    public String encrypt(String paymentKey) {
        requireConfigured();
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(paymentKey.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("결제키를 암호화하지 못했습니다.", exception);
        }
    }

    // encrypt()로 만든 암호문을 결제키 원문으로 되돌린다. 형식이 다르거나 위·변조되었으면 IllegalStateException이다.
    public String decrypt(String encrypted) {
        requireConfigured();
        if (encrypted == null || !encrypted.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException("지원하지 않는 결제키 암호문 형식입니다.");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(encrypted.substring(VERSION_PREFIX.length()));
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("결제키 암호문을 복호화하지 못했습니다.", exception);
        }
    }

    // 결제키의 HMAC-SHA256을 소문자 hex 64자로 만든다. 같은 결제키는 항상 같은 값이다.
    public String hash(String paymentKey) {
        requireConfigured();
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(paymentKey.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("결제키 해시를 만들지 못했습니다.", exception);
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("결제키 암호화 키가 설정되지 않았습니다.");
        }
    }

    private static byte[] decodeKey(String value, String name) {
        try {
            return Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("결제키 " + name + "가 Base64 형식이 아닙니다 (hapbang.billing.payment-key."
                    + name + ")", exception);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

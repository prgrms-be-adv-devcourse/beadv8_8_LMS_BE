package com.hapbang.member.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

class JwtConfigTest {

    JwtConfig config = new JwtConfig();

    @Test
    @DisplayName("PKCS#8 PEM 개인 키로 서명 키를 만들고, kid는 공개 키 지문이다")
    void signingKey_fromPem() throws Exception {
        KeyPair keyPair = generateKeyPair();

        RSAKey key = config.jwtSigningKey(properties(pem(keyPair)));

        assertThat(key.isPrivate()).isTrue();
        assertThat(key.toRSAPublicKey()).isEqualTo(keyPair.getPublic());
        assertThat(key.getKeyID()).isEqualTo(key.computeThumbprint().toString());
    }

    @Test
    @DisplayName("같은 개인 키면 재시작해도 kid가 같다")
    void signingKey_sameKidForSameKey() throws Exception {
        String pem = pem(generateKeyPair());

        assertThat(config.jwtSigningKey(properties(pem)).getKeyID())
                .isEqualTo(config.jwtSigningKey(properties(pem)).getKeyID());
    }

    @Test
    @DisplayName("개인 키가 없고 임시 키를 허용하면 임시 키를 만든다")
    void signingKey_ephemeral() throws Exception {
        RSAKey key = config.jwtSigningKey(properties(null));

        assertThat(key.isPrivate()).isTrue();
        assertThat(key.getKeyID()).isNotBlank();
    }

    @Test
    @DisplayName("공개 키 목록에는 개인 키 정보가 없다")
    void publicKeys_excludePrivateKey() throws Exception {
        RSAKey signingKey = config.jwtSigningKey(properties(null));

        JWKSet publicKeys = config.jwtPublicKeys(signingKey);

        assertThat(publicKeys.getKeys()).singleElement().satisfies(key -> {
            assertThat(key.isPrivate()).isFalse();
            assertThat(key.getKeyID()).isEqualTo(signingKey.getKeyID());
        });
    }

    @Test
    @DisplayName("개인 키가 없는데 임시 키를 허용하지 않으면 설정을 만들 수 없다")
    void properties_requirePrivateKey() {
        assertThatThrownBy(() -> new JwtProperties("https://hapbang/member", Duration.ofMinutes(15), "", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hapbang.jwt.private-key");
    }

    @Test
    @DisplayName("설정을 문자열로 바꿔도 개인 키는 드러나지 않는다")
    void properties_toStringMasksPrivateKey() throws Exception {
        String pem = pem(generateKeyPair());

        assertThat(properties(pem).toString()).doesNotContain(pem.substring(40, 80)).contains("privateKey=****");
    }

    private static JwtProperties properties(String privateKey) {
        return new JwtProperties("https://hapbang/member", Duration.ofMinutes(15), privateKey, true);
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String pem(KeyPair keyPair) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }
}

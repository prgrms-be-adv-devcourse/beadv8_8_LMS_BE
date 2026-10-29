package com.hapbang.member.global.config;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import lombok.extern.slf4j.Slf4j;

/**
 * JWT 서명 키와 인코더 (#370). 개인 키는 member-app만 갖고, 공개 키는 JWKS로 공개한다.
 * kid는 공개 키의 지문(RFC 7638 thumbprint)이라 키를 바꾸면 kid도 바뀐다
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    private static final int EPHEMERAL_KEY_SIZE = 2048;

    /** 서명 키 (개인 키 + 공개 키). 개인 키 설정이 없으면 임시 키를 만든다 (allow-ephemeral-key일 때만, JwtProperties가 검사) */
    @Bean
    RSAKey jwtSigningKey(JwtProperties properties) throws Exception {
        if (properties.hasPrivateKey()) {
            return fromPem(properties.privateKey());
        }
        log.warn("hapbang.jwt.private-key가 없어 임시 서명 키를 만듭니다. 재시작하면 발급한 토큰이 모두 무효가 됩니다 (local·테스트 전용).");
        return new RSAKeyGenerator(EPHEMERAL_KEY_SIZE)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyIDFromThumbprint(true)
                .generate();
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwtSigningKey)));
    }

    /** 공개 키만 담은 목록. JWKS 응답으로 그대로 내보낸다 */
    @Bean
    JWKSet jwtPublicKeys(RSAKey jwtSigningKey) {
        return new JWKSet(jwtSigningKey.toPublicJWK());
    }

    /** PKCS#8 PEM(-----BEGIN PRIVATE KEY-----)에서 개인 키를 읽고, 같은 키의 modulus·지수로 공개 키를 만든다 */
    static RSAKey fromPem(String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) keyFactory.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(
                new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        try {
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyIDFromThumbprint()
                    .build();
        } catch (JOSEException e) {
            throw new IllegalStateException("JWT 서명 키의 kid를 계산하지 못했습니다.", e);
        }
    }
}

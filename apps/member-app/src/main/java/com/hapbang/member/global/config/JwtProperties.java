package com.hapbang.member.global.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 발급 설정 (hapbang.jwt.*, #370).
 *
 * @param issuer            토큰의 발급자(`iss`). Envoy Gateway의 JWT provider 설정과 같은 값이어야 한다
 * @param accessTokenTtl    access token 수명
 * @param privateKey        서명용 RSA 개인 키(PKCS#8 PEM). 공개 레포라 서버는 환경 변수(JWT_PRIVATE_KEY)로만 받는다
 * @param allowEphemeralKey 개인 키가 비어 있을 때 앱 시작마다 임시 키를 만들지. local·테스트에서만 켠다
 */
@ConfigurationProperties("hapbang.jwt")
public record JwtProperties(String issuer, Duration accessTokenTtl, String privateKey, boolean allowEphemeralKey) {

    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("hapbang.jwt.issuer 설정이 필요합니다.");
        }
        if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            throw new IllegalArgumentException("hapbang.jwt.access-token-ttl은 0보다 커야 합니다.");
        }
        // 운영에서 키를 빠뜨리면 재시작마다 키가 바뀌어 발급한 토큰이 모두 무효가 된다
        if ((privateKey == null || privateKey.isBlank()) && !allowEphemeralKey) {
            throw new IllegalArgumentException("hapbang.jwt.private-key 설정이 필요합니다.");
        }
    }

    public boolean hasPrivateKey() {
        return privateKey != null && !privateKey.isBlank();
    }

    @Override
    public String toString() {
        // 설정 오류 로그 등에 개인 키가 찍히지 않게 한다
        return "JwtProperties[issuer=" + issuer + ", accessTokenTtl=" + accessTokenTtl
                + ", privateKey=" + (hasPrivateKey() ? "****" : "") + ", allowEphemeralKey=" + allowEphemeralKey + "]";
    }
}

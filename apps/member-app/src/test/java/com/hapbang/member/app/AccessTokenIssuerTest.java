package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.hapbang.member.global.config.JwtProperties;
import com.hapbang.shared.member.domain.Role;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

class AccessTokenIssuerTest {

    static final String ISSUER = "https://hapbang/member";

    RSAKey signingKey = generateKey();
    JwtProperties properties = new JwtProperties(ISSUER, Duration.ofMinutes(15), null, true);
    AccessTokenIssuer issuer = new AccessTokenIssuer(
            new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey))), properties);
    JwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey(signingKey)).build();

    @Test
    @DisplayName("회원 ID를 sub에, 역할을 role에 문자열 하나로 담고 발급자를 넣는다")
    void issue_claims() {
        Jwt jwt = decoder.decode(issuer.issue(42L, Role.INFLUENCER));

        assertThat(jwt.getSubject()).isEqualTo("42");
        // Envoy claimToHeaders는 배열 claim을 넘기지 못한다 (#369)
        assertThat(jwt.getClaims().get("role")).isEqualTo("INFLUENCER");
        assertThat(jwt.getIssuer()).hasToString(ISSUER);
    }

    @Test
    @DisplayName("발급 시각(iat)을 넣고, 그로부터 15분 뒤에 만료된다")
    void issue_expiresAfterTtl() {
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        Jwt jwt = decoder.decode(issuer.issue(42L, Role.USER));

        assertThat(jwt.getIssuedAt()).isAfterOrEqualTo(before).isBeforeOrEqualTo(Instant.now());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("RS256으로 서명하고, 헤더 kid에 서명한 키의 ID를 넣는다")
    void issue_header() {
        Jwt jwt = decoder.decode(issuer.issue(42L, Role.USER));

        assertThat(jwt.getHeaders())
                .containsEntry("alg", "RS256")
                .containsEntry("kid", signingKey.getKeyID());
    }

    @Test
    @DisplayName("다른 키의 공개키로는 검증되지 않는다")
    void issue_rejectedByOtherKey() {
        JwtDecoder otherDecoder = NimbusJwtDecoder.withPublicKey(publicKey(generateKey())).build();

        assertThatThrownBy(() -> otherDecoder.decode(issuer.issue(42L, Role.USER)))
                .isInstanceOf(JwtException.class);
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAPublicKey publicKey(RSAKey key) {
        try {
            return key.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}

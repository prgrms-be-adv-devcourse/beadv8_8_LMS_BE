package com.hapbang.member.app;

import java.time.Instant;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import com.hapbang.member.global.config.JwtProperties;
import com.hapbang.shared.member.domain.Role;

import lombok.RequiredArgsConstructor;

/** access token(JWT, RS256)을 발급한다. Envoy Gateway가 서명을 검증하고 sub·role을 헤더로 넘긴다 (#369, #370) */
@Component
@RequiredArgsConstructor
public class AccessTokenIssuer {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    /**
     * 회원 ID(sub)와 역할(role)을 담은 access token을 발급한다.
     * role은 문자열 하나다 (Envoy claimToHeaders는 배열 claim을 넘기지 못한다). kid 헤더는 인코더가 서명 키의 ID로 넣는다
     */
    public String issue(Long userId, Role role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(userId))
                .claim("role", role.name())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}

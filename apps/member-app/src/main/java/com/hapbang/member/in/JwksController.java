package com.hapbang.member.in;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.jwk.JWKSet;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;

/**
 * JWT 서명 검증용 공개 키 목록(JWKS, #370). 같은 내용을 두 경로로 준다.
 * <ul>
 *     <li>{@link #PATH}: Envoy Gateway가 받는다(remoteJWKS). 외부 API 포트(8080)라 인증 없이 연다.</li>
 *     <li>{@link #INTERNAL_PATH}: chat-app이 STOMP 토큰 검증용으로 내부 API 키를 붙여 받는다(#371).
 *     앱의 8080은 Envoy가 넣는 사용자 헤더를 믿는 포트라 다른 앱에 열지 않고, 내부 포트(8081)로 준다.</li>
 *     <li>표준 JWKS 형식(`{"keys": [...]}`)이어야 받는 쪽이 읽을 수 있어 RsData로 감싸지 않는다.</li>
 *     <li>`/api/v1/internal/**` 아래에 두지 않는다. 그 경로는 내부 API 키가 있어야 해서 Envoy가 키를 받지 못한다.</li>
 *     <li>경로는 Envoy SecurityPolicy의 remoteJWKS 주소와 같아야 한다 (apps/member-app/k8s/base/gateway.yaml, #372).
 *     회원 경로 아래라 Envoy를 거쳐 밖에서도 보이지만, 공개 키라 인증 없이 연다.</li>
 * </ul>
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class JwksController {

    static final String PATH = "/api/v1/members/.well-known/jwks.json";
    static final String INTERNAL_PATH = "/api/v1/internal/members/jwks.json";

    private final JWKSet jwtPublicKeys;

    @GetMapping(value = {PATH, INTERNAL_PATH}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        // 공개 키 목록이지만, 개인 키 정보가 섞여도 나가지 않게 공개 값만 꺼낸다
        return jwtPublicKeys.toJSONObject(true);
    }
}

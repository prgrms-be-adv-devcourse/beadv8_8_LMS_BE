package com.hapbang.member.in;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.jwk.JWKSet;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;

/**
 * JWT 서명 검증용 공개 키 목록(JWKS, #370). Envoy Gateway와 chat-app이 cluster 안에서 받아 간다.
 * <ul>
 *     <li>표준 JWKS 형식(`{"keys": [...]}`)이어야 받는 쪽이 읽을 수 있어 RsData로 감싸지 않는다.</li>
 *     <li>`/api/v1/internal/**` 아래에 두지 않는다. 그 경로는 내부 API 키가 있어야 해서 Envoy가 키를 받지 못한다.</li>
 *     <li>공개 키라 인증 없이 열지만, 외부로는 노출하지 않는다(Envoy에 route를 만들지 않는다).</li>
 * </ul>
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class JwksController {

    static final String PATH = "/.well-known/jwks.json";

    private final JWKSet jwtPublicKeys;

    @GetMapping(value = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        // 공개 키 목록이지만, 개인 키 정보가 섞여도 나가지 않게 공개 값만 꺼낸다
        return jwtPublicKeys.toJSONObject(true);
    }
}

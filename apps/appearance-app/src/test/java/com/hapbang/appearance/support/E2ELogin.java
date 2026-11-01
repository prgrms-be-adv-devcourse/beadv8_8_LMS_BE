package com.hapbang.appearance.support;

import static io.restassured.RestAssured.given;

import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import com.hapbang.appearance.global.auth.GatewayUserHeaderFilter;
import com.hapbang.shared.member.domain.Role;

/**
 * E2E 테스트에서 로그인 상태 만들기 (#390, #395). 로그인 방식이 바뀌면 이 클래스만 고친다.
 * <p>
 * 운영에서 Gateway(Envoy)가 JWT를 검증한 뒤 앱에 넘기는 것과 같은 사용자 헤더({@code X-User-Id}·{@code X-User-Role})를 싣는다.
 * 앱은 서명을 다시 검증하지 않으므로 테스트도 토큰 없이 헤더만 보낸다. 세션·CSRF 토큰은 쓰지 않는다.
 * <p>
 * 요청 형식은 JSON으로 둔다. RestAssured는 형식을 정하지 않으면 {@code text/plain}을 보내서, 본문이 선택인 API({@code @RequestBody(required = false)})에
 * 본문 없이 보내면 415가 된다. 프론트도 JSON으로 보낸다.
 */
public final class E2ELogin {

    private E2ELogin() {
    }

    /** 이 회원 ID·역할로 로그인한 요청. {@code @LoginUserId}에는 userId가 들어간다 */
    public static RequestSpecification as(Long userId, Role role) {
        return given()
                .contentType(ContentType.JSON)
                .header(GatewayUserHeaderFilter.USER_ID, String.valueOf(userId))
                .header(GatewayUserHeaderFilter.USER_ROLE, role.name());
    }
}

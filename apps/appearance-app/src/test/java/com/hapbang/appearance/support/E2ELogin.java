package com.hapbang.appearance.support;

import static io.restassured.RestAssured.given;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import com.hapbang.shared.member.domain.Role;

/**
 * E2E 테스트에서 로그인 상태 만들기 (#390). 로그인 방식이 바뀌면(예: JWT) 이 클래스만 고친다.
 * <p>
 * 프론트와 같은 방식으로 요청한다: 세션 쿠키와 CSRF 토큰 쿠키를 싣고, {@code X-XSRF-TOKEN} 헤더에 토큰 쿠키 값을 넣는다.
 * 조회 요청에 헤더가 붙어도 결과는 같다.
 * <p>
 * 요청 형식은 JSON으로 둔다. RestAssured는 형식을 정하지 않으면 {@code text/plain}을 보내서, 본문이 선택인 API({@code @RequestBody(required = false)})에
 * 본문 없이 보내면 415가 된다. 프론트도 JSON으로 보낸다.
 */
public final class E2ELogin {

    private static final String SESSION_COOKIE = "JSESSIONID";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";

    private E2ELogin() {
    }

    /** 이 회원 ID·역할로 로그인한 요청 (CSRF 토큰 포함). {@code @LoginUserId}에는 userId가 들어간다 */
    public static RequestSpecification as(Long userId, Role role) {
        Response login = login(userId, role);
        String xsrfToken = cookie(login, XSRF_COOKIE);
        return given()
                .contentType(ContentType.JSON)
                .cookie(SESSION_COOKIE, cookie(login, SESSION_COOKIE))
                .cookie(XSRF_COOKIE, xsrfToken)
                .header(XSRF_HEADER, xsrfToken);
    }

    /** 로그인했지만 CSRF 토큰 없이 보내는 요청 (보안 필터가 막는지 확인용) */
    public static RequestSpecification withoutCsrf(Long userId, Role role) {
        return given()
                .contentType(ContentType.JSON)
                .cookie(SESSION_COOKIE, cookie(login(userId, role), SESSION_COOKIE));
    }

    private static Response login(Long userId, Role role) {
        return given()
                .queryParam("userId", userId)
                .queryParam("role", role.name())
                .when()
                .post(E2ELoginConfig.LOGIN_PATH)
                .then()
                .statusCode(204)
                .extract().response();
    }

    private static String cookie(Response response, String name) {
        String value = response.getCookie(name);
        if (value == null) {
            throw new AssertionError(name + " 쿠키가 없습니다: " + response.getHeaders().getValues("Set-Cookie"));
        }
        return value;
    }
}

package com.hapbang.member.in;

import org.springframework.http.ResponseEntity;

import com.hapbang.member.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 인증 API Swagger 명세 (#370). 매핑은 {@link ApiV1MemberAuthController}. 로그인(access token) 없이 refresh token 쿠키로 부른다 */
@Tag(name = "인증", description = "access token 재발급, 로그아웃")
public interface ApiV1MemberAuthSpec {

    @Operation(summary = "access token 재발급",
            description = "refresh token 쿠키(REFRESH_TOKEN, 브라우저가 자동으로 붙인다)로 새 access token을 받는다. "
                    + "access token은 본문(data.accessToken, data.expiresIn초)으로 주고 메모리에 두어 Authorization: Bearer로 보낸다. "
                    + "refresh token은 새 값으로 바뀌어 쿠키로 다시 내려간다. 페이지를 새로 열면 먼저 이 API를 부른다. "
                    + "여러 탭이 같은 쿠키로 거의 동시에 부르면(10초 안) 뒤 요청은 access token만 받고 쿠키는 바뀌지 않는다. "
                    + "쿠키가 없거나 만료·폐기된 토큰, 이용할 수 없는 회원이면 401 INVALID_REFRESH_TOKEN (다시 로그인).")
    ResponseEntity<RsData<AccessTokenResponse>> refresh(
            @Parameter(in = ParameterIn.COOKIE, name = RefreshTokenCookies.NAME, hidden = true) String refreshToken);

    @Operation(summary = "로그아웃",
            description = "이 기기의 refresh token을 폐기하고 쿠키를 지운다. 쿠키가 없어도 204. "
                    + "access token은 프론트가 메모리에서 버린다 (서버가 회수할 수 없고 최대 15분 뒤 만료된다).")
    ResponseEntity<Void> logout(
            @Parameter(in = ParameterIn.COOKIE, name = RefreshTokenCookies.NAME, hidden = true) String refreshToken);
}

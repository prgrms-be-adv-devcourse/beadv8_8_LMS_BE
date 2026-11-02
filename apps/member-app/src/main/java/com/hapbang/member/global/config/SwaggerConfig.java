package com.hapbang.member.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {
    // 구글 로그인은 필터가 처리해 API 목록에 없다. 다른 앱 명세는 Authorize 사용법을 이 설명으로 안내한다 (#498)
    private static final String DESCRIPTION = """
            회원 API. <a href="/api/v1/members/oauth2/authorization/google" target="_blank">구글 로그인 (새 탭)</a>

            로그인이 필요한 API는 Authorize 버튼으로 인증한다. 넣은 값은 이 화면의 모든 요청에 붙는다.

            - 운영: 위 링크로 구글 로그인하거나 `POST /api/v1/members/login`(이메일·비밀번호)으로 로그인한 뒤
              이 명세의 `POST /api/v1/members/auth/refresh`를 Try it out으로 실행한다
              (refresh token 쿠키가 자동으로 붙는다). 응답의 `data.accessToken`을 Authorize의 `bearerAuth`에 넣는다.
              access token은 15분 뒤 만료되니 다시 refresh한다. 만료된 토큰이 남아 있으면 로그인이 필요 없는 API도 401이므로 Logout으로 지운다.
            - 로컬: Gateway가 없으므로 Authorize에 `X-User-Id`·`X-User-Role` 값을 넣는다. 예: `1`, `USER`.

            로그아웃: `POST /api/v1/members/auth/logout`. 내부 API(`/api/v1/internal/**`)는 앱끼리만 부르며, 외부에서 호출하면 404다.
            """;

    private static final String BEARER = "bearerAuth";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLE_HEADER = "X-User-Role";

    // Authorize 버튼의 인증 방식 (#498). 운영(swagger.yml의 prod)은 Gateway가 access token을 검증하므로 Bearer다.
    // 로컬은 토큰을 검증할 Gateway가 없어 Gateway가 넣어 주는 사용자 헤더를 직접 넣는다.
    // 운영에는 헤더 방식을 두지 않는다. Envoy가 외부에서 보낸 X-User-* 헤더를 지운다
    @Bean
    public OpenAPI openAPI(@Value("${hapbang.swagger.bearer-auth.enabled:false}") boolean bearerAuth) {
        OpenAPI openAPI = new OpenAPI()
                .info(new Info()
                        .title("합방 회원 API")
                        .version("v1")
                        .description(DESCRIPTION));
        if (bearerAuth) {
            return openAPI
                    .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT")))
                    .addSecurityItem(new SecurityRequirement().addList(BEARER));
        }
        return openAPI
                .components(new Components()
                        .addSecuritySchemes(USER_ID_HEADER, header(USER_ID_HEADER))
                        .addSecuritySchemes(USER_ROLE_HEADER, header(USER_ROLE_HEADER)))
                // 한 요구에 두 헤더를 함께 둬야 Authorize 뒤 요청마다 둘 다 붙는다
                .addSecurityItem(new SecurityRequirement().addList(USER_ID_HEADER).addList(USER_ROLE_HEADER));
    }

    private static SecurityScheme header(String name) {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name(name);
    }
}

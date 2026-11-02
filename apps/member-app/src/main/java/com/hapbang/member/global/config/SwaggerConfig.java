package com.hapbang.member.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {
    // 구글 로그인은 필터가 처리해 API 목록에 없다. 로그인이 필요한 API는 Gateway가 넣는 X-User-Id·X-User-Role 헤더로 인증한다 (로컬에는 Gateway가 없어 헤더를 직접 넣는다, #370)
    private static final String DESCRIPTION = """
            <a href="/api/v1/members/oauth2/authorization/google" target="_blank">구글 로그인 (새 탭)</a>

            로그인이 필요한 API는 Gateway가 access token을 검증하고 넣어 주는 `X-User-Id`·`X-User-Role` 헤더로 인증한다.
            로컬에는 Gateway가 없으므로 두 헤더를 직접 넣어 호출한다. 예: `X-User-Id: 1`, `X-User-Role: USER`

            로그아웃: `POST /api/v1/members/auth/logout`

            로컬에서는 구글 없이 `POST /api/v1/members/local-login`으로 역할별 로그인할 수 있다.
            """;

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("합방 API")
                        .version("v1")
                        .description(DESCRIPTION));
    }
}

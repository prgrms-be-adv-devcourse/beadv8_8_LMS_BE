package com.hapbang.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {
    // 구글 로그인은 필터가 처리해 API 목록에 없다. 새 탭에서 로그인하면 세션 쿠키가 생겨 이 탭의 Try it out에도 붙는다
    private static final String DESCRIPTION = """
            <a href="/api/v1/members/oauth2/authorization/google" target="_blank">구글 로그인 (새 탭)</a>

            로그인을 마치면 새 탭은 닫고 이 화면에서 API를 실행한다. 로그인 확인: `GET /api/v1/members/me`

            로그아웃: `POST /api/v1/members/logout` (API 목록에 없음)

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

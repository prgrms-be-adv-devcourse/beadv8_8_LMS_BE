package com.hapbang.chat.global.config;

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
    private static final String DESCRIPTION = """
            채팅 API. 내부 API(`/api/v1/internal/**`)는 앱끼리만 부르며, 외부에서 호출하면 404다.

            로그인이 필요한 API는 Authorize 버튼으로 인증한다. 운영은 access token(Bearer), 로컬은 `X-User-Id`·`X-User-Role` 헤더다.
            토큰 받는 법은 회원 API 명세(`/api/v1/members/swagger-ui.html`) 설명을 본다.
            """;

    private static final String BEARER = "bearerAuth";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLE_HEADER = "X-User-Role";

    // Authorize 버튼의 인증 방식. 운영(swagger.yml의 prod)은 Gateway가 access token을 검증하므로 Bearer다.
    // 로컬은 토큰을 검증할 Gateway가 없어 Gateway가 넣어 주는 사용자 헤더를 직접 넣는다.
    // 운영에는 헤더 방식을 두지 않는다. Envoy가 외부에서 보낸 X-User-* 헤더를 지운다
    @Bean
    public OpenAPI openAPI(@Value("${hapbang.swagger.bearer-auth.enabled:false}") boolean bearerAuth) {
        OpenAPI openAPI = new OpenAPI()
                .info(new Info()
                        .title("합방 채팅 API")
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

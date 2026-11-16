package com.hapbang.auxiliary.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.testsupport.WebSliceTestConfiguration;

// 운영 Swagger 목록(#894): 이 앱 화면의 상단 목록에서도 7개 앱 명세를 고르고, 이 앱이 기본이다.
// 다른 앱은 운영 프로필로 앱 전체를 띄워 검사하지만, 이 앱의 운영 설정은 DB·OpenAI 환경 변수와 ES 인증서 파일이 있어야 뜬다.
// 그래서 MVC 계층과 springdoc만 띄운다. 웹 슬라이스는 springdoc 자동 설정을 넣지 않으므로 직접 가져온다.
// 운영 값은 swagger.yml에 있고 테스트 설정(hapbang-module-test.yaml)도 같은 파일을 읽으므로, 이 테스트가 실제 설정을 검사한다.
@ContextConfiguration(classes = WebSliceTestConfiguration.class)
@WebMvcTest(properties = "spring.config.name=hapbang-module-test")
@ImportAutoConfiguration({
        SpringDocConfiguration.class, SpringDocConfigProperties.class, SpringDocWebMvcConfiguration.class,
        org.springdoc.webmvc.ui.SwaggerConfig.class, SwaggerUiConfigProperties.class, SwaggerUiOAuthProperties.class})
@Import(SecurityConfig.class)
@ActiveProfiles("prod")
class AuxiliarySwaggerPublicTest {

    private static final String API_DOCS = "/api/v1/auxiliary/v3/api-docs";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 운영_Swagger_목록에_7개_앱_명세가_있고_자기_앱이_기본이다() throws Exception {
        mockMvc.perform(get(API_DOCS + "/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urls.length()").value(7))
                .andExpect(jsonPath("$.urls[?(@.name == '회원')].url").value("/api/v1/members/v3/api-docs"))
                .andExpect(jsonPath("$.urls[?(@.name == '강의')].url").value("/api/v1/lectures/v3/api-docs"))
                .andExpect(jsonPath("$.urls[?(@.name == '출연')].url").value("/api/v1/appearances/v3/api-docs"))
                .andExpect(jsonPath("$.urls[?(@.name == '지갑')].url").value("/api/v1/wallets/v3/api-docs"))
                .andExpect(jsonPath("$.urls[?(@.name == '채팅')].url").value("/api/v1/chats/v3/api-docs"))
                .andExpect(jsonPath("$.urls[?(@.name == '고객센터')].url").value("/api/v1/inquiries/v3/api-docs"))
                .andExpect(jsonPath("$.urls[?(@.name == '검색·챗봇·알림')].url").value(API_DOCS))
                .andExpect(jsonPath("$['urls.primaryName']").value("검색·챗봇·알림"))
                // access token을 브라우저 저장소에 남기지 않는다 (swagger-ui 기본값 false)
                .andExpect(jsonPath("$.persistAuthorization").doesNotExist());
    }
}

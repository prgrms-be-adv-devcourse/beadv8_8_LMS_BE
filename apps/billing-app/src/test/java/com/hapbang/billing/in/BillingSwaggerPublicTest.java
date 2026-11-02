package com.hapbang.billing.in;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.testsupport.IntegrationTest;

// 운영 Swagger 공개(#498): 지갑 앱의 Swagger는 모듈 경로(/api/v1/wallets) 아래에서 로그인 없이 열린다.
// 경로·운영 값은 swagger.yml에 있고 모듈 테스트 설정도 같은 파일을 읽으므로, 이 테스트가 실제 설정을 검사한다.
class BillingSwaggerPublicTest {

    private static final String API_DOCS = "/api/v1/wallets/v3/api-docs";
    private static final String SCHEMES = "$.components.securitySchemes";

    @Nested
    @IntegrationTest
    @AutoConfigureMockMvc
    class 기본_프로필 {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void Swagger_명세는_로그인_없이_모듈_경로에서_열린다() throws Exception {
            mockMvc.perform(get(API_DOCS))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.info.title").value("합방 지갑 API"))
                    .andExpect(jsonPath("$.paths['/api/v1/wallets/me']").exists());
        }

        @Test
        void Swagger_UI는_모듈_경로에서_열린다() throws Exception {
            mockMvc.perform(get("/api/v1/wallets/swagger-ui.html"))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("/api/v1/wallets/swagger-ui/index.html"));
            mockMvc.perform(get("/api/v1/wallets/swagger-ui/index.html"))
                    .andExpect(status().isOk());
            mockMvc.perform(get(API_DOCS + "/swagger-config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value(API_DOCS));
        }

        @Test
        void 옛_루트_경로에서는_명세를_주지_않는다() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/swagger-ui.html"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void 로컬_명세에는_사용자_헤더_인증만_있다() throws Exception {
            mockMvc.perform(get(API_DOCS))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(SCHEMES + "['X-User-Id'].type").value("apiKey"))
                    .andExpect(jsonPath(SCHEMES + "['X-User-Id'].in").value("header"))
                    .andExpect(jsonPath(SCHEMES + "['X-User-Id'].name").value("X-User-Id"))
                    .andExpect(jsonPath(SCHEMES + "['X-User-Role'].type").value("apiKey"))
                    .andExpect(jsonPath(SCHEMES + "['X-User-Role'].in").value("header"))
                    .andExpect(jsonPath(SCHEMES + "['X-User-Role'].name").value("X-User-Role"))
                    // 한 요구 안에 두 헤더가 있어야 Authorize 뒤 두 헤더가 함께 붙는다
                    .andExpect(jsonPath("$.security[0]['X-User-Id']").exists())
                    .andExpect(jsonPath("$.security[0]['X-User-Role']").exists())
                    .andExpect(jsonPath(SCHEMES + ".bearerAuth").doesNotExist());
        }
    }

    // swagger.yml의 prod 문서를 읽는다. 프로필마다 컨텍스트가 따로 생기므로 연결 풀을 작게 두고 클래스가 끝나면 닫는다
    @Nested
    @IntegrationTest
    @AutoConfigureMockMvc
    @ActiveProfiles("prod")
    @TestPropertySource(properties = {
            "spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1"})
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class 운영_프로필 {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void 운영_명세에는_Bearer_인증만_있다() throws Exception {
            mockMvc.perform(get(API_DOCS))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(SCHEMES + ".bearerAuth.type").value("http"))
                    .andExpect(jsonPath(SCHEMES + ".bearerAuth.scheme").value("bearer"))
                    .andExpect(jsonPath(SCHEMES + ".bearerAuth.bearerFormat").value("JWT"))
                    .andExpect(jsonPath("$.security[0].bearerAuth").exists())
                    // Envoy가 외부의 X-User-* 헤더를 지우므로 운영에는 헤더 입력칸을 두지 않는다
                    .andExpect(jsonPath(SCHEMES + "['X-User-Id']").doesNotExist())
                    .andExpect(jsonPath(SCHEMES + "['X-User-Role']").doesNotExist());
        }

        @Test
        void 운영_Swagger_목록에_6개_앱_명세가_있고_자기_앱이_기본이다() throws Exception {
            mockMvc.perform(get(API_DOCS + "/swagger-config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.urls.length()").value(6))
                    .andExpect(jsonPath("$.urls[?(@.name == '회원')].url").value("/api/v1/members/v3/api-docs"))
                    .andExpect(jsonPath("$.urls[?(@.name == '강의')].url").value("/api/v1/lectures/v3/api-docs"))
                    .andExpect(jsonPath("$.urls[?(@.name == '출연')].url").value("/api/v1/appearances/v3/api-docs"))
                    .andExpect(jsonPath("$.urls[?(@.name == '지갑')].url").value("/api/v1/wallets/v3/api-docs"))
                    .andExpect(jsonPath("$.urls[?(@.name == '채팅')].url").value("/api/v1/chats/v3/api-docs"))
                    .andExpect(jsonPath("$.urls[?(@.name == '고객센터')].url").value("/api/v1/inquiries/v3/api-docs"))
                    .andExpect(jsonPath("$['urls.primaryName']").value("지갑"))
                    // access token을 브라우저 저장소에 남기지 않는다 (swagger-ui 기본값 false)
                    .andExpect(jsonPath("$.persistAuthorization").doesNotExist());
        }
    }
}

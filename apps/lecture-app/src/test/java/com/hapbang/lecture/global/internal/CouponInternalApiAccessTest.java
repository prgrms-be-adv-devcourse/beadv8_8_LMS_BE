package com.hapbang.lecture.global.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.lecture.support.GatewayLogin;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

// 같은 JVM에 실린 쿠폰 모듈의 사용 내부 API(/api/v1/internal/coupons/**)가 강의 앱 내부 API 보안 체인 아래에 있는지 본다.
// 쿠폰 코드는 lecture-app 테스트에서 컴파일 의존이 없어 경로로만 부른다. 요청 검증·응답 모양은 쿠폰 모듈의 웹 슬라이스 테스트가 본다
@IntegrationTest
@AutoConfigureMockMvc
class CouponInternalApiAccessTest {

    private static final String RELEASES = "/api/v1/internal/coupons/releases";
    private static final String BLOCKED = "/api/v1/internal/coupons/price-raise-blocked";
    // 작업 ID 형식이 틀려 쿠폰 진입점의 첫 검사에서 끝나는 요청. 키와 역할을 통과했는지만 본다
    private static final String BAD_WORK_ID_RELEASE = """
            {"orderId":9001,"workId":"a.b","usages":[{"memberCouponId":501,"generation":1}],"reason":"HOLD_FAILED"}""";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 내부_키가_없거나_회원_로그인만_있으면_본문_없이_401이다() throws Exception {
        mockMvc.perform(get(BLOCKED).param("lectureId", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(get(BLOCKED).param("lectureId", "1").with(GatewayLogin.as(7L, Role.ADMIN)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(RELEASES).contentType(MediaType.APPLICATION_JSON).content(BAD_WORK_ID_RELEASE)
                        .with(GatewayLogin.as(7L, Role.USER)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void 내부_키로_부르면_쿠폰_진입점까지_닿는다() throws Exception {
        mockMvc.perform(post(RELEASES).contentType(MediaType.APPLICATION_JSON).content(BAD_WORK_ID_RELEASE)
                        .header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WORK_ID"));
    }

    @Test
    void 요청_본문_오류는_강의_공통_핸들러의_INVALID_REQUEST로_응답한다() throws Exception {
        mockMvc.perform(post(RELEASES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":9001,\"workId\":\"w-1\",\"usages\":[],\"reason\":\"HOLD_FAILED\"}")
                        .header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}

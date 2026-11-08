package com.hapbang.coupon.in;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.coupon.app.CouponIssueFacade;
import com.hapbang.coupon.app.dto.IssueRequestResult;
import com.hapbang.coupon.app.dto.IssueStatusView;
import com.hapbang.coupon.app.dto.MemberCouponView;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.DiscountType;
import com.hapbang.coupon.domain.IssueResponseCode;
import com.hapbang.coupon.domain.IssueStatus;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.global.config.CouponWebMvcConfig;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.global.exception.CouponExceptionHandler;
import com.hapbang.testsupport.WebSliceTestConfiguration;

// 회원 쿠폰 API의 경로·요청 값 검증·응답 모양만 본다. 발급 규칙과 역할 검사는 Facade가 맡으므로 Facade를 mock으로 둔다.
// 요청 값 오류의 본문(code INVALID_REQUEST)은 같은 JVM의 강의 공통 핸들러가 만들어 이 슬라이스에서는 상태만 확인한다
@ContextConfiguration(classes = WebSliceTestConfiguration.class)
@WebMvcTest(controllers = ApiV1CouponController.class)
@Import({ApiV1CouponController.class, CouponWebMvcConfig.class, CouponExceptionHandler.class})
class ApiV1CouponControllerTest {

    private static final long MEMBER_ID = 7L;
    private static final long COUPON_ID = 42L;
    private static final String REQUEST_ID = "3f1c9a52-7d4b-4e7a-9a51-0c2f6d1e8b77";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CouponIssueFacade couponIssueFacade;

    @Test
    void 발급_접수는_202와_요청_ID_쿠폰_ID_결과_만료_시각을_돌려준다() throws Exception {
        given(couponIssueFacade.requestIssue(COUPON_ID, MEMBER_ID, REQUEST_ID))
                .willReturn(new IssueRequestResult(REQUEST_ID, COUPON_ID, IssueResponseCode.ACCEPTED,
                        Instant.parse("2026-12-06T03:00:00.123Z")));

        mockMvc.perform(post("/api/v1/coupons/{couponId}/issue-requests", COUPON_ID)
                        .with(member()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issueRequestId\":\"" + REQUEST_ID + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.statusCode").value(202))
                .andExpect(jsonPath("$.data.issueRequestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.data.couponId").value(COUPON_ID))
                .andExpect(jsonPath("$.data.result").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-12-06T03:00:00.123Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"issueRequestId\":\"\"}",
            "{\"issueRequestId\":\"공백 있는 요청\"}",
            "{\"issueRequestId\":\"a:b\"}",
            // 65자
            "{\"issueRequestId\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}"
    })
    void 발급_요청_ID가_없거나_형식이_틀리면_400이고_접수하지_않는다(String body) throws Exception {
        mockMvc.perform(post("/api/v1/coupons/{couponId}/issue-requests", COUPON_ID)
                        .with(member()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(couponIssueFacade, never()).requestIssue(anyLong(), anyLong(), anyString());
    }

    @Test
    void 발급_요청_ID는_64자까지_받는다() throws Exception {
        String requestId = "A_b-9".repeat(12) + "abcd";
        given(couponIssueFacade.requestIssue(COUPON_ID, MEMBER_ID, requestId))
                .willReturn(new IssueRequestResult(requestId, COUPON_ID, IssueResponseCode.ALREADY_ACCEPTED,
                        Instant.parse("2026-12-06T03:00:00Z")));

        mockMvc.perform(post("/api/v1/coupons/{couponId}/issue-requests", COUPON_ID)
                        .with(member()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issueRequestId\":\"" + requestId + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.result").value("ALREADY_ACCEPTED"));
    }

    @Test
    void 접수_거절은_쿠폰_오류_코드로_응답한다() throws Exception {
        given(couponIssueFacade.requestIssue(COUPON_ID, MEMBER_ID, REQUEST_ID))
                .willThrow(new CouponException(CouponErrorCode.COUPON_SOLD_OUT));

        mockMvc.perform(post("/api/v1/coupons/{couponId}/issue-requests", COUPON_ID)
                        .with(member()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issueRequestId\":\"" + REQUEST_ID + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_SOLD_OUT"));
    }

    @Test
    void 발급_요청_상태를_로그인_회원으로_조회한다() throws Exception {
        given(couponIssueFacade.issueStatus(COUPON_ID, MEMBER_ID, REQUEST_ID))
                .willReturn(new IssueStatusView(REQUEST_ID, COUPON_ID, IssueStatus.PENDING));

        mockMvc.perform(get("/api/v1/coupons/{couponId}/issue-requests/{requestId}", COUPON_ID, REQUEST_ID)
                        .with(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.issueRequestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.data.couponId").value(COUPON_ID))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void 내_쿠폰은_상태로_거르고_상태가_없으면_전체다() throws Exception {
        Instant issuedAt = Instant.parse("2026-11-06T03:00:00Z");
        MemberCouponView view = new MemberCouponView(5L, COUPON_ID, MemberCouponStatus.AVAILABLE, issuedAt,
                issuedAt.plusSeconds(86_400), CouponTargetType.ANY_LECTURE, null, DiscountType.RATE, 10, 3_000L, false);
        given(couponIssueFacade.myCoupons(MEMBER_ID, MemberCouponStatus.AVAILABLE)).willReturn(List.of(view));
        given(couponIssueFacade.myCoupons(MEMBER_ID, null)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/coupons/me").param("status", "AVAILABLE").with(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].memberCouponId").value(5))
                .andExpect(jsonPath("$.data[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data[0].issuedAt").value("2026-11-06T03:00:00Z"));
        mockMvc.perform(get("/api/v1/coupons/me").with(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void 강의에_쓸_수_있는_쿠폰은_강의_ID가_없으면_400이다() throws Exception {
        mockMvc.perform(get("/api/v1/coupons/me/usable").with(member()))
                .andExpect(status().isBadRequest());

        verify(couponIssueFacade, never()).usableFor(anyLong(), anyLong());
    }

    private static RequestPostProcessor member() {
        return user(String.valueOf(MEMBER_ID)).roles("USER");
    }
}

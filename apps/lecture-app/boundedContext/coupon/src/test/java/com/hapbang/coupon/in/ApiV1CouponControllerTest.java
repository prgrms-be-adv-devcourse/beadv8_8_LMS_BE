package com.hapbang.coupon.in;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;
import org.springframework.data.web.config.SpringDataWebSettings;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.coupon.app.CouponIssueFacade;
import com.hapbang.coupon.app.dto.CouponSummaryView;
import com.hapbang.coupon.app.dto.CouponUsageView;
import com.hapbang.coupon.app.dto.IssueRequestResult;
import com.hapbang.coupon.app.dto.IssueStatusView;
import com.hapbang.coupon.app.dto.MemberCouponView;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.CouponUsageStatus;
import com.hapbang.coupon.domain.DiscountType;
import com.hapbang.coupon.domain.ExpiryType;
import com.hapbang.coupon.domain.IssueResponseCode;
import com.hapbang.coupon.domain.IssueStatus;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.domain.usage.Selection;
import com.hapbang.coupon.global.config.CouponWebMvcConfig;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.global.exception.CouponExceptionHandler;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;
import com.hapbang.testsupport.WebSliceTestConfiguration;

// 회원 쿠폰 API의 경로·요청 값 검증·응답 모양만 본다. 발급 규칙과 역할 검사는 Facade가 맡으므로 Facade를 mock으로 둔다.
// 요청 값 오류의 본문(code INVALID_REQUEST)은 같은 JVM의 강의 공통 핸들러가 만들어 이 슬라이스에서는 상태만 확인한다
@ContextConfiguration(classes = WebSliceTestConfiguration.class)
@WebMvcTest(controllers = ApiV1CouponController.class)
@Import({ApiV1CouponController.class, CouponWebMvcConfig.class, CouponExceptionHandler.class,
        ApiV1CouponControllerTest.PageSerialization.class})
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
                .willReturn(new IssueStatusView(REQUEST_ID, COUPON_ID, IssueStatus.PENDING, null));

        mockMvc.perform(get("/api/v1/coupons/{couponId}/issue-requests/{requestId}", COUPON_ID, REQUEST_ID)
                        .with(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.issueRequestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.data.couponId").value(COUPON_ID))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.rejectReason").value(nullValue()));
    }

    @Test
    void 거절된_발급_요청은_거절_사유를_싣는다() throws Exception {
        given(couponIssueFacade.issueStatus(COUPON_ID, MEMBER_ID, REQUEST_ID))
                .willReturn(new IssueStatusView(REQUEST_ID, COUPON_ID, IssueStatus.REJECTED, "SOLD_OUT"));

        mockMvc.perform(get("/api/v1/coupons/{couponId}/issue-requests/{requestId}", COUPON_ID, REQUEST_ID)
                        .with(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectReason").value("SOLD_OUT"));
    }

    @Test
    void 강의에_받을_수_있는_쿠폰은_매진_받음_표시와_함께_배열로_돌려준다() throws Exception {
        given(couponIssueFacade.issuableCoupons(MEMBER_ID, 7L)).willReturn(List.of(summary(true, false)));

        mockMvc.perform(get("/api/v1/coupons").param("lectureId", "7").with(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].couponId").value(COUPON_ID))
                .andExpect(jsonPath("$.data[0].name").value("신규 회원 할인"))
                .andExpect(jsonPath("$.data[0].soldOut").value(true))
                .andExpect(jsonPath("$.data[0].issued").value(false))
                .andExpect(jsonPath("$.data[0].status").value("OPEN"));
    }

    @Test
    void 강의에_받을_수_있는_쿠폰은_강의_ID가_없으면_400이다() throws Exception {
        mockMvc.perform(get("/api/v1/coupons").with(member()))
                .andExpect(status().isBadRequest());

        verify(couponIssueFacade, never()).issuableCoupons(anyLong(), anyLong());
    }

    @Test
    void 쿠폰_상세는_로그인_회원으로_조회하고_못_보는_쿠폰은_404다() throws Exception {
        given(couponIssueFacade.coupon(MEMBER_ID, COUPON_ID)).willReturn(summary(false, true));
        given(couponIssueFacade.coupon(MEMBER_ID, 43L)).willThrow(new CouponException(CouponErrorCode.COUPON_NOT_FOUND));

        mockMvc.perform(get("/api/v1/coupons/{couponId}", COUPON_ID).with(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.couponId").value(COUPON_ID))
                .andExpect(jsonPath("$.data.issued").value(true));
        mockMvc.perform(get("/api/v1/coupons/{couponId}", 43L).with(member()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"));
    }

    @Test
    void 내_쿠폰_경로는_쿠폰_상세로_가지_않는다() throws Exception {
        given(couponIssueFacade.myCoupons(MEMBER_ID, null)).willReturn(List.of());
        given(couponIssueFacade.usableFor(MEMBER_ID, 7L)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/coupons/me").with(member())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/coupons/me/usable").param("lectureId", "7").with(member()))
                .andExpect(status().isOk());

        verify(couponIssueFacade).myCoupons(MEMBER_ID, null);
        verify(couponIssueFacade).usableFor(MEMBER_ID, 7L);
        verify(couponIssueFacade, never()).coupon(anyLong(), anyLong());
    }

    @Test
    void 내_쿠폰은_상태로_거르고_상태가_없으면_전체다() throws Exception {
        Instant issuedAt = Instant.parse("2026-11-06T03:00:00Z");
        MemberCouponView view = new MemberCouponView(5L, COUPON_ID, "신규 회원 할인", MemberCouponStatus.AVAILABLE, issuedAt,
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

    private static CouponSummaryView summary(boolean soldOut, boolean issued) {
        Instant start = Instant.parse("2026-11-07T00:00:00Z");
        return new CouponSummaryView(COUPON_ID, "신규 회원 할인", CouponType.PLATFORM, CouponTargetType.ANY_LECTURE, null,
                DiscountType.RATE, 10, 3_000L, false, start, start.plusSeconds(86_400 * 7), ExpiryType.RELATIVE, 30,
                null, 100, 40, soldOut, issued, CouponStatus.OPEN);
    }

    // 할인 계산 미리보기 -----------------------------------------------------------------------------------------

    @Test
    void 미리보기는_강의와_매핑을_Facade에_넘기고_계산_결과를_돌려준다() throws Exception {
        CouponCalculateResponse response = new CouponCalculateResponse(false, false, List.of(
                new CouponCalculateResponse.Item(3L, 10_000, 1_000, 0, 1_000, 9_000, 9_000, List.of())), 1_000, 9_000);
        given(couponIssueFacade.quote(MEMBER_ID, List.of(3L, 5L), List.of(new Selection(11L, 3L))))
                .willReturn(response);

        mockMvc.perform(post("/api/v1/coupons/me/quote").with(member()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lectureIds\":[3,5],\"selections\":[{\"memberCouponId\":11,\"lectureId\":3}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalBuyerAmount").value(9_000))
                .andExpect(jsonPath("$.data.items[0].buyerAmount").value(9_000));
    }

    @Test
    void 미리보기에서_매핑을_생략하면_추천으로_null을_넘긴다() throws Exception {
        mockMvc.perform(post("/api/v1/coupons/me/quote").with(member()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lectureIds\":[3]}"))
                .andExpect(status().isOk());

        verify(couponIssueFacade).quote(MEMBER_ID, List.of(3L), null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"lectureIds\":[]}", "{\"lectureIds\":[null]}",
            "{\"lectureIds\":[3],\"selections\":[{\"lectureId\":3}]}"})
    void 미리보기_요청_형식이_틀리면_400이고_Facade를_부르지_않는다(String body) throws Exception {
        mockMvc.perform(post("/api/v1/coupons/me/quote").with(member()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(couponIssueFacade, never()).quote(anyLong(), any(), any());
    }

    // 사용 이력 ----------------------------------------------------------------------------------------------

    @Test
    void 사용_이력은_상태와_기본_크기_20_페이지를_Facade에_넘기고_페이지_모양으로_돌려준다() throws Exception {
        CouponUsageView paid = new CouponUsageView(77L, 31L, 5L, "겨울 특강 할인", CouponType.SELLER, DiscountType.RATE,
                10, 7L, 900L, 901L, 10_000, 1_000, CouponUsageStatus.PAID, null,
                Instant.parse("2026-11-10T01:00:00Z"), Instant.parse("2026-11-10T01:00:05Z"), null, null, null);
        given(couponIssueFacade.myUsages(eq(MEMBER_ID), eq(CouponUsageStatus.PAID), any()))
                .willReturn(new PageImpl<>(List.of(paid), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/coupons/me/usages").param("status", "PAID").with(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].usageId").value(77))
                .andExpect(jsonPath("$.data.content[0].status").value("PAID"))
                .andExpect(jsonPath("$.data.content[0].returnedTo").value(nullValue()))
                .andExpect(jsonPath("$.data.page.size").value(20))
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.totalElements").value(1))
                .andExpect(jsonPath("$.data.page.totalPages").value(1));

        verify(couponIssueFacade).myUsages(eq(MEMBER_ID), eq(CouponUsageStatus.PAID),
                argThat(pageable -> pageable.getPageNumber() == 0 && pageable.getPageSize() == 20));
    }

    @Test
    void 사용_이력_status가_정해진_값이_아니면_400이고_Facade를_부르지_않는다() throws Exception {
        mockMvc.perform(get("/api/v1/coupons/me/usages").param("status", "USED").with(member()))
                .andExpect(status().isBadRequest());

        verify(couponIssueFacade, never()).myUsages(anyLong(), any(), any());
    }

    private static RequestPostProcessor member() {
        return user(String.valueOf(MEMBER_ID)).roles("USER");
    }

    /** 운영에서 강의 앱 PageConfig가 켜는 Page 직렬화(VIA_DTO: content, page). 이 모듈 슬라이스에는 그 설정이 없어 같은 모양으로 둔다 */
    @TestConfiguration(proxyBeanMethods = false)
    static class PageSerialization {

        @Bean
        SpringDataWebSettings springDataWebSettings() {
            return new SpringDataWebSettings(PageSerializationMode.VIA_DTO);
        }
    }
}

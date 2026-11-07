package com.hapbang.coupon.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponDomainError;
import com.hapbang.coupon.domain.CouponDomainException;
import com.hapbang.testsupport.WebSliceTestConfiguration;

import jakarta.servlet.ServletException;

// 쿠폰 업무 예외가 강의 공통 핸들러와 같은 ProblemDetail + code로 응답하는지, 다른 예외는 고르지 않는지 본다.
// 인증은 이 핸들러와 관계없어 보안 필터를 끈다. 컨트롤러는 테스트 전용이다(쿠폰 패키지 안이라 핸들러가 걸린다)
@ContextConfiguration(classes = WebSliceTestConfiguration.class)
@WebMvcTest(controllers = CouponExceptionHandlerTest.SampleController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({CouponExceptionHandler.class, CouponExceptionHandlerTest.SampleController.class})
class CouponExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 쿠폰_예외는_오류_코드의_상태_기본_메시지_code로_응답한다() throws Exception {
        mockMvc.perform(get("/api/v1/coupons/samples/sold-out"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("COUPON_SOLD_OUT"))
                .andExpect(jsonPath("$.detail").value("쿠폰이 모두 소진되었습니다."));
    }

    @Test
    void 상세_메시지와_추가_속성을_주면_detail과_속성이_바뀌고_code는_같다() throws Exception {
        mockMvc.perform(get("/api/v1/coupons/samples/invalid-policy"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COUPON_POLICY"))
                .andExpect(jsonPath("$.detail").value("수량은 1 이상이어야 합니다."))
                .andExpect(jsonPath("$.field").value("quantity"));
    }

    @Test
    void 서버_쪽_실패는_5xx_오류_코드로_응답한다() throws Exception {
        mockMvc.perform(get("/api/v1/coupons/samples/lecture-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("LECTURE_CATALOG_UNAVAILABLE"));
    }

    @Test
    void 도메인_규칙_위반은_대응하는_오류_코드와_도메인_메시지로_응답한다() throws Exception {
        mockMvc.perform(get("/api/v1/coupons/samples/domain-quantity"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COUPON_POLICY"))
                .andExpect(jsonPath("$.detail").value(CouponDomainError.INVALID_QUANTITY.getMessage()));
        mockMvc.perform(get("/api/v1/coupons/samples/domain-transition"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_COUPON_STATUS"));
    }

    @Test
    void 발급_종료가_지난_쿠폰은_409_발급_기간_종료_코드다() {
        CouponErrorCode code = CouponExceptionHandler.errorCodeOf(CouponDomainError.ISSUE_PERIOD_ENDED);

        assertThat(code).isEqualTo(CouponErrorCode.COUPON_ISSUE_PERIOD_ENDED);
        assertThat(code.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void 발급_저장소에_닿지_못하면_내부_메시지_없이_503이다() throws Exception {
        mockMvc.perform(get("/api/v1/coupons/samples/store-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COUPON_ISSUE_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value(CouponErrorCode.COUPON_ISSUE_STORE_UNAVAILABLE.getMessage()));
    }

    @Test
    void 낙관적_락_충돌은_409_동시_변경_코드로_응답한다() throws Exception {
        mockMvc.perform(get("/api/v1/coupons/samples/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_CONCURRENT_UPDATE"))
                .andExpect(jsonPath("$.detail").value(CouponErrorCode.COUPON_CONCURRENT_UPDATE.getMessage()));
    }

    @Test
    void 쿠폰_예외가_아니면_고르지_않는다() {
        // 운영에서는 같은 JVM의 강의 공통 핸들러가 받는다. 이 슬라이스에는 그 핸들러가 없어 예외가 그대로 나온다
        assertThatThrownBy(() -> mockMvc.perform(get("/api/v1/coupons/samples/unexpected")))
                .isInstanceOf(ServletException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @RestController
    static class SampleController {

        @GetMapping("/api/v1/coupons/samples/sold-out")
        void soldOut() {
            throw new CouponException(CouponErrorCode.COUPON_SOLD_OUT);
        }

        @GetMapping("/api/v1/coupons/samples/invalid-policy")
        void invalidPolicy() {
            throw new FieldCouponException(CouponErrorCode.INVALID_COUPON_POLICY, "수량은 1 이상이어야 합니다.", "quantity");
        }

        @GetMapping("/api/v1/coupons/samples/lecture-unavailable")
        void lectureUnavailable() {
            throw new CouponException(CouponErrorCode.LECTURE_CATALOG_UNAVAILABLE, new IllegalStateException("연결 실패"));
        }

        @GetMapping("/api/v1/coupons/samples/domain-quantity")
        void domainQuantity() {
            throw new CouponDomainException(CouponDomainError.INVALID_QUANTITY);
        }

        @GetMapping("/api/v1/coupons/samples/domain-transition")
        void domainTransition() {
            throw new CouponDomainException(CouponDomainError.INVALID_STATUS_TRANSITION);
        }

        @GetMapping("/api/v1/coupons/samples/store-unavailable")
        void storeUnavailable() {
            throw new CouponIssueStoreUnavailableException("발급 접수에 Redis를 쓰지 못했다", null);
        }

        @GetMapping("/api/v1/coupons/samples/optimistic-lock")
        void optimisticLock() {
            throw new ObjectOptimisticLockingFailureException(Coupon.class, 1L);
        }

        @GetMapping("/api/v1/coupons/samples/unexpected")
        void unexpected() {
            throw new IllegalStateException("예상하지 못한 오류");
        }
    }

    static class FieldCouponException extends CouponException {

        FieldCouponException(CouponErrorCode errorCode, String detail, String field) {
            super(errorCode, detail);
            addProperty("field", field);
        }
    }
}

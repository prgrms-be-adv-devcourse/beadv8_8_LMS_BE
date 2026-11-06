package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.*;
import static com.hapbang.coupon.app.CouponOpenUseCaseTest.assertCouponError;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueAttempt;
import com.hapbang.coupon.app.port.LectureCatalog;
import com.hapbang.coupon.domain.IssueResponseCode;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.out.CouponRepository;

// 접수 입력(epoch·시각·만료)과 Lua 결과의 응답 변환을 본다. 결과 해석(fromLua)은 도메인 테스트, 판정은 Lua 테스트가 본다
class CouponIssueRequestUseCaseTest {

    private static final String REQUEST_ID = "req-1";
    /** 밀리초 아래가 있는 지금. Redis에는 밀리초까지만 남는다 */
    private static final Instant NOW_WITH_MICROS = NOW.plusNanos(123_456_000);

    private final CouponRepository couponRepository = mock();
    private final CouponIssueStore issueStore = mock();
    private CouponIssueRequestUseCase useCase;

    @BeforeEach
    void setUp() {
        CouponSupport support = new CouponSupport(couponRepository, mock(LectureCatalog.class), issueStore);
        useCase = new CouponIssueRequestUseCase(support, issueStore, Clock.fixed(NOW_WITH_MICROS, ZoneOffset.UTC));
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(openSellerCoupon()));
    }

    @Test
    void 접수하면_밀리초로_자른_접수_시각과_그_시각의_만료_시각을_넘기고_ACCEPTED다() {
        when(issueStore.tryIssue(any())).thenReturn(1L);

        var result = useCase.requestIssue(COUPON_ID, MEMBER_ID, REQUEST_ID);

        assertThat(result.result()).isEqualTo(IssueResponseCode.ACCEPTED);
        assertThat(result.issueRequestId()).isEqualTo(REQUEST_ID);
        Instant issuedAt = NOW.plusMillis(123);
        verify(issueStore).tryIssue(new IssueAttempt(COUPON_ID, MEMBER_ID, REQUEST_ID, 1L, issuedAt,
                issuedAt.plus(Duration.ofDays(30))));
    }

    @Test
    void 같은_요청을_다시_보내면_ALREADY_ACCEPTED로_성공한다() {
        // 재전송은 이번 시각으로 만료 시각을 다시 계산해 넘기지만, Lua가 기존 접수 기록을 바꾸지 않고 2를 돌려준다
        // (기록 불변은 IssueLuaTest의 같은 요청 테스트가 본다)
        when(issueStore.tryIssue(any())).thenReturn(2L);

        var result = useCase.requestIssue(COUPON_ID, MEMBER_ID, REQUEST_ID);

        assertThat(result.result()).isEqualTo(IssueResponseCode.ALREADY_ACCEPTED);
        ArgumentCaptor<IssueAttempt> attempt = ArgumentCaptor.forClass(IssueAttempt.class);
        verify(issueStore).tryIssue(attempt.capture());
        assertThat(attempt.getValue().issueRequestId()).isEqualTo(REQUEST_ID);
    }

    @ParameterizedTest(name = "Lua {0} → {1}")
    @CsvSource({
            "0, COUPON_SOLD_OUT",
            "-2, COUPON_ISSUE_NOT_OPEN",
            "-4, ISSUE_REQUEST_ID_CONFLICT",
            "-9, COUPON_ISSUE_FAILED"
    })
    void 접수되지_않은_결과는_오류_코드로_응답한다(long luaValue, CouponErrorCode expected) {
        when(issueStore.tryIssue(any())).thenReturn(luaValue);

        assertCouponError(() -> useCase.requestIssue(COUPON_ID, MEMBER_ID, REQUEST_ID), expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"has space", "요청", "a/b",
            "12345678901234567890123456789012345678901234567890123456789012345"})
    void 요청_ID_형식이_틀리면_400이고_쿠폰과_Redis를_보지_않는다(String issueRequestId) {
        assertCouponError(() -> useCase.requestIssue(COUPON_ID, MEMBER_ID, issueRequestId),
                CouponErrorCode.INVALID_ISSUE_REQUEST_ID);

        verifyNoInteractions(couponRepository, issueStore);
    }

    @Test
    void 쿠폰이_없으면_404다() {
        assertCouponError(() -> useCase.requestIssue(99L, MEMBER_ID, REQUEST_ID), CouponErrorCode.COUPON_NOT_FOUND);

        verifyNoInteractions(issueStore);
    }
}

package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.*;
import static com.hapbang.coupon.app.CouponOpenUseCaseTest.assertCouponError;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueAttempt;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueOutcome;
import com.hapbang.coupon.domain.IssueResponseCode;
import com.hapbang.coupon.global.exception.CouponErrorCode;

// 접수 입력(시각)과 Lua 결과의 응답 변환을 본다. 결과 해석(fromLua)은 도메인 테스트, 판정·만료 계산은 Lua 테스트가 본다.
// 접수 경로는 DB를 읽지 않는다: 의존은 Redis 발급 저장소와 시계뿐이다
class CouponIssueRequestUseCaseTest {

    private static final String REQUEST_ID = "req-1";
    /** 밀리초 아래가 있는 지금. Redis에는 밀리초까지만 남는다 */
    private static final Instant NOW_WITH_MICROS = NOW.plusNanos(123_456_000);
    private static final Instant EXPIRES_AT = NOW.plus(Duration.ofDays(30));

    private final CouponIssueStore issueStore = mock();
    private CouponIssueRequestUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CouponIssueRequestUseCase(issueStore, Clock.fixed(NOW_WITH_MICROS, ZoneOffset.UTC));
    }

    @Test
    void 접수_경로는_DB에_의존하지_않고_Redis_발급_저장소와_시계만_받는다() {
        Constructor<?>[] constructors = CouponIssueRequestUseCase.class.getConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).containsExactly(CouponIssueStore.class, Clock.class);
    }

    @Test
    void 접수하면_밀리초로_자른_접수_시각을_넘기고_Lua가_정한_만료_시각과_ACCEPTED를_돌려준다() {
        when(issueStore.tryIssue(any())).thenReturn(new IssueOutcome(1, EXPIRES_AT));

        var result = useCase.requestIssue(COUPON_ID, MEMBER_ID, REQUEST_ID);

        assertThat(result.result()).isEqualTo(IssueResponseCode.ACCEPTED);
        assertThat(result.issueRequestId()).isEqualTo(REQUEST_ID);
        assertThat(result.couponId()).isEqualTo(COUPON_ID);
        assertThat(result.expiresAt()).isEqualTo(EXPIRES_AT);
        verify(issueStore).tryIssue(new IssueAttempt(COUPON_ID, MEMBER_ID, REQUEST_ID, NOW.plusMillis(123)));
    }

    @Test
    void 같은_요청을_다시_보내면_ALREADY_ACCEPTED와_최초_접수의_만료_시각을_돌려준다() {
        // 최초 접수의 만료 시각은 Lua가 requests 기록에서 꺼내 준다(기록 불변은 IssueLuaTest의 같은 요청 테스트가 본다)
        when(issueStore.tryIssue(any())).thenReturn(new IssueOutcome(2, EXPIRES_AT));

        var result = useCase.requestIssue(COUPON_ID, MEMBER_ID, REQUEST_ID);

        assertThat(result.result()).isEqualTo(IssueResponseCode.ALREADY_ACCEPTED);
        assertThat(result.expiresAt()).isEqualTo(EXPIRES_AT);
    }

    @ParameterizedTest(name = "Lua {0} → {1}")
    @CsvSource({
            "0, COUPON_SOLD_OUT",
            "-2, COUPON_ISSUE_NOT_OPEN",
            "-4, ISSUE_REQUEST_ID_CONFLICT",
            "-9, COUPON_ISSUE_FAILED"
    })
    void 접수되지_않은_결과는_오류_코드로_응답한다(long luaValue, CouponErrorCode expected) {
        // 쿠폰이 없거나 준비 전이면 issue-control이 없어 Lua가 -2(준비 안 됨)다. DB로 404를 가리지 않는다
        when(issueStore.tryIssue(any())).thenReturn(new IssueOutcome(luaValue, null));

        assertCouponError(() -> useCase.requestIssue(COUPON_ID, MEMBER_ID, REQUEST_ID), expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"has space", "요청", "a/b",
            "12345678901234567890123456789012345678901234567890123456789012345"})
    void 요청_ID_형식이_틀리면_400이고_Redis를_보지_않는다(String issueRequestId) {
        assertCouponError(() -> useCase.requestIssue(COUPON_ID, MEMBER_ID, issueRequestId),
                CouponErrorCode.INVALID_ISSUE_REQUEST_ID);

        verifyNoInteractions(issueStore);
    }
}

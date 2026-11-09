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
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.hapbang.coupon.app.port.CouponIssueEventPublisher;
import com.hapbang.coupon.app.port.CouponIssueEventPublisher.PublishResult;
import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueAttempt;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueOutcome;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.config.CouponIssueDeliveryProperties;
import com.hapbang.coupon.config.CouponSoldOutCacheProperties;
import com.hapbang.coupon.domain.IssueResponseCode;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.shared.coupon.event.CouponIssueRequested;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 접수 입력(시각)과 Lua 결과의 응답 변환, 접수 직후 전송, 매진 캐시 분기를 본다. 결과 해석(fromLua)·캐시 TTL 판정은 도메인 테스트,
// 판정·만료 계산·표식 쓰기는 Lua 테스트가 본다. 접수 경로는 DB를 읽지 않는다: 의존은 Redis 발급 저장소·Kafka 전송·매진 캐시·설정·지표·시계뿐이다
class CouponIssueRequestUseCaseTest {

    private static final String REQUEST_ID = "req-1";
    private static final long EPOCH = 3;
    /** 밀리초 아래가 있는 지금. Redis에는 밀리초까지만 남는다 */
    private static final Instant NOW_WITH_MICROS = NOW.plusNanos(123_456_000);
    private static final Instant ISSUED_AT = NOW.plusMillis(123);
    private static final Instant EXPIRES_AT = NOW.plus(Duration.ofDays(30));
    private static final CouponIssueRequested EVENT =
            new CouponIssueRequested(REQUEST_ID, COUPON_ID, MEMBER_ID, EPOCH, ISSUED_AT, EXPIRES_AT);

    private final CouponIssueStore issueStore = mock();
    private final CouponIssueEventPublisher publisher = mock();
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private CouponIssueRequestUseCase useCase;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW_WITH_MICROS, ZoneOffset.UTC);
        CouponIssueDeliveryProperties properties = CouponIssueDeliveryProperties.defaults();
        // 고정 시계에 맞춰 매진 캐시의 만료 시계도 멈춘다
        useCase = new CouponIssueRequestUseCase(issueStore, publisher,
                new CouponSoldOutCache(issueStore, properties, CouponSoldOutCacheProperties.defaults(), clock, meterRegistry,
                        () -> 0L, Runnable::run), properties, registry(meterRegistry), clock);
        when(publisher.publish(any(), any())).thenReturn(PublishResult.ACKED);
    }

    @Test
    void 접수_경로는_DB에_의존하지_않는다() {
        Constructor<?>[] constructors = CouponIssueRequestUseCase.class.getConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).containsExactly(CouponIssueStore.class,
                CouponIssueEventPublisher.class, CouponSoldOutCache.class, CouponIssueDeliveryProperties.class,
                ObjectProvider.class, Clock.class);
    }

    @Test
    void 접수하면_밀리초로_자른_접수_시각과_Lua의_만료_시각과_epoch로_보내고_ACK면_PUBLISHED_표식을_붙인다() {
        when(issueStore.tryIssue(any())).thenReturn(new IssueOutcome(1, EXPIRES_AT, EPOCH));

        var result = useCase.requestIssue(COUPON_ID, MEMBER_ID, REQUEST_ID);

        assertThat(result.result()).isEqualTo(IssueResponseCode.ACCEPTED);
        assertThat(result.issueRequestId()).isEqualTo(REQUEST_ID);
        assertThat(result.couponId()).isEqualTo(COUPON_ID);
        assertThat(result.expiresAt()).isEqualTo(EXPIRES_AT);
        verify(issueStore).tryIssue(new IssueAttempt(COUPON_ID, MEMBER_ID, REQUEST_ID, ISSUED_AT));
        verify(publisher).publish(EVENT, Duration.ofSeconds(1));
        verify(issueStore).markPublished(COUPON_ID, REQUEST_ID);
        assertThat(meterRegistry.find(CouponIssueRequestUseCase.PUBLISH_FAILURE_METRIC).counter()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = PublishResult.class, names = {"FAILED", "TIMED_OUT"})
    void 전송이_실패하거나_시간이_지나도_ACCEPTED이고_재고를_되돌리지_않으며_표식_없이_지표만_남긴다(PublishResult failure) {
        when(issueStore.tryIssue(any())).thenReturn(new IssueOutcome(1, EXPIRES_AT, EPOCH));
        when(publisher.publish(any(), any())).thenReturn(failure);

        var result = useCase.requestIssue(COUPON_ID, MEMBER_ID, REQUEST_ID);

        assertThat(result.result()).isEqualTo(IssueResponseCode.ACCEPTED);
        assertThat(result.expiresAt()).isEqualTo(EXPIRES_AT);
        // 저장소 호출은 매진 확인과 접수뿐이다: 재고를 되돌리거나 기록을 지우는 호출이 없다
        verify(issueStore).isSoldOut(COUPON_ID);
        verify(issueStore).tryIssue(any());
        verifyNoMoreInteractions(issueStore);
        assertThat(meterRegistry.get(CouponIssueRequestUseCase.PUBLISH_FAILURE_METRIC)
                .tag("result", failure.name().toLowerCase(Locale.ROOT)).counter().count()).isEqualTo(1);
    }

    @Test
    void 전송_포트가_예외를_던져도_ACCEPTED다() {
        when(issueStore.tryIssue(any())).thenReturn(new IssueOutcome(1, EXPIRES_AT, EPOCH));
        when(publisher.publish(any(), any())).thenThrow(new IllegalStateException("broker"));

        assertThat(useCase.requestIssue(COUPON_ID, MEMBER_ID, REQUEST_ID).result()).isEqualTo(IssueResponseCode.ACCEPTED);
        verify(issueStore, never()).markPublished(anyLong(), anyString());
    }

    @Test
    void 표식을_남기지_못해도_ACCEPTED다() {
        when(issueStore.tryIssue(any())).thenReturn(new IssueOutcome(1, EXPIRES_AT, EPOCH));
        when(issueStore.markPublished(anyLong(), anyString()))
                .thenThrow(new CouponIssueStoreUnavailableException("redis", null));

        assertThat(useCase.requestIssue(COUPON_ID, MEMBER_ID, REQUEST_ID).result()).isEqualTo(IssueResponseCode.ACCEPTED);
    }

    @Test
    void 같은_요청을_다시_보내면_ALREADY_ACCEPTED와_최초_접수의_만료_시각이고_보내지_않는다() {
        // 최초 접수의 만료 시각은 Lua가 requests 기록에서 꺼내 준다(기록 불변은 IssueLuaTest의 같은 요청 테스트가 본다)
        when(issueStore.tryIssue(any())).thenReturn(new IssueOutcome(2, EXPIRES_AT, 0));

        var result = useCase.requestIssue(COUPON_ID, MEMBER_ID, REQUEST_ID);

        assertThat(result.result()).isEqualTo(IssueResponseCode.ALREADY_ACCEPTED);
        assertThat(result.expiresAt()).isEqualTo(EXPIRES_AT);
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest(name = "Lua {0} → {1}")
    @CsvSource({
            "0, COUPON_SOLD_OUT",
            "-2, COUPON_ISSUE_NOT_OPEN",
            "-4, ISSUE_REQUEST_ID_CONFLICT",
            "-9, COUPON_ISSUE_FAILED"
    })
    void 접수되지_않은_결과는_오류_코드로_응답하고_보내지_않는다(long luaValue, CouponErrorCode expected) {
        // 쿠폰이 없거나 준비 전이면 issue-control이 없어 Lua가 -2(준비 안 됨)다. DB로 404를 가리지 않는다
        when(issueStore.tryIssue(any())).thenReturn(new IssueOutcome(luaValue, null, 0));

        assertCouponError(() -> useCase.requestIssue(COUPON_ID, MEMBER_ID, REQUEST_ID), expected);
        verifyNoInteractions(publisher);
    }

    @Test
    void 매진_표시가_있으면_Lua를_부르지_않고_매진이며_TTL_안에서는_다시_읽지_않는다() {
        when(issueStore.isSoldOut(COUPON_ID)).thenReturn(true);

        assertCouponError(() -> useCase.requestIssue(COUPON_ID, MEMBER_ID, REQUEST_ID), CouponErrorCode.COUPON_SOLD_OUT);
        assertCouponError(() -> useCase.requestIssue(COUPON_ID, OTHER_MEMBER_ID, "req-2"),
                CouponErrorCode.COUPON_SOLD_OUT);

        verify(issueStore, times(1)).isSoldOut(COUPON_ID);
        verify(issueStore, never()).tryIssue(any());
    }

    @Test
    void Lua가_매진을_돌려주면_다음_요청은_Redis를_부르지_않고_매진이다() {
        when(issueStore.tryIssue(any())).thenReturn(new IssueOutcome(0, null, 0));
        assertCouponError(() -> useCase.requestIssue(COUPON_ID, MEMBER_ID, REQUEST_ID), CouponErrorCode.COUPON_SOLD_OUT);

        assertCouponError(() -> useCase.requestIssue(COUPON_ID, OTHER_MEMBER_ID, "req-2"),
                CouponErrorCode.COUPON_SOLD_OUT);

        verify(issueStore, times(1)).tryIssue(any());
    }

    @Test
    void 매진_표시를_읽지_못하면_Lua가_판정한다() {
        when(issueStore.isSoldOut(COUPON_ID)).thenThrow(new CouponIssueStoreUnavailableException("redis", null));
        when(issueStore.tryIssue(any())).thenReturn(new IssueOutcome(1, EXPIRES_AT, EPOCH));

        assertThat(useCase.requestIssue(COUPON_ID, MEMBER_ID, REQUEST_ID).result()).isEqualTo(IssueResponseCode.ACCEPTED);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"has space", "요청", "a/b",
            "12345678901234567890123456789012345678901234567890123456789012345"})
    void 요청_ID_형식이_틀리면_400이고_Redis를_보지_않는다(String issueRequestId) {
        assertCouponError(() -> useCase.requestIssue(COUPON_ID, MEMBER_ID, issueRequestId),
                CouponErrorCode.INVALID_ISSUE_REQUEST_ID);

        verifyNoInteractions(issueStore, publisher);
    }

    static ObjectProvider<MeterRegistry> registry(MeterRegistry meterRegistry) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("meterRegistry", meterRegistry);
        return beanFactory.getBeanProvider(MeterRegistry.class);
    }
}

package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.*;
import static com.hapbang.coupon.app.CouponOpenUseCaseTest.assertCouponError;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStore.AllowResult;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.app.port.LectureCatalog;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponDomainError;
import com.hapbang.coupon.domain.CouponDomainException;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.out.CouponRepository;

// 중단·재개·종료에서 Redis와 DB의 순서·fence 인자, Redis 실패 뒤 남는 상태, 같은 요청 재호출을 본다.
// 늦게 도착한 Redis 쓰기의 경쟁은 실제 Redis로 CouponIssueRaceTest가 본다
class CouponOperateUseCaseTest {

    private final CouponRepository couponRepository = mock();
    private final CouponIssueStore issueStore = mock();
    private final CouponVersionFake db = new CouponVersionFake(couponRepository);

    private CouponOperateUseCase useCase;
    private Coupon coupon;
    /** Redis 차단을 부른 순간의 DB 상태 */
    private final List<CouponStatus> statusAtBlock = new ArrayList<>();

    @BeforeEach
    void setUp() {
        CouponSupport support = new CouponSupport(couponRepository, mock(LectureCatalog.class), issueStore);
        useCase = new CouponOperateUseCase(support, issueStore, db.transactionManager(), CLOCK);
        doAnswer(invocation -> statusAtBlock.add(coupon.getStatus())).when(issueStore)
                .block(anyLong(), anyString(), anyLong());
        when(issueStore.allowIssue(anyLong(), anyLong())).thenReturn(AllowResult.APPLIED);
    }

    private CouponOperateUseCase useCaseAt(Instant now) {
        CouponSupport support = new CouponSupport(couponRepository, mock(LectureCatalog.class), issueStore);
        return new CouponOperateUseCase(support, issueStore, db.transactionManager(), Clock.fixed(now, ZoneOffset.UTC));
    }

    private void given(Coupon value) {
        coupon = value;
        db.given(coupon);
    }

    @Test
    void 중단은_지금_version으로_Redis를_막은_뒤_SUSPENDED로_커밋하고_커밋된_version으로_한_번_더_막는다() {
        given(openSellerCoupon());

        var view = useCase.suspend(ADMIN_ID, COUPON_ID);

        assertThat(view.status()).isEqualTo(CouponStatus.SUSPENDED);
        InOrder order = inOrder(issueStore);
        order.verify(issueStore).block(COUPON_ID, "SUSPENDED", 0);
        order.verify(issueStore).block(COUPON_ID, "SUSPENDED", 1);
        assertThat(statusAtBlock).containsExactly(CouponStatus.OPEN, CouponStatus.SUSPENDED);
    }

    @Test
    void 중단_차단을_확인하지_못하면_DB는_OPEN으로_남는다() {
        given(openSellerCoupon());
        doThrow(new CouponIssueStoreUnavailableException("확인 실패", null)).when(issueStore)
                .block(anyLong(), anyString(), anyLong());

        assertThatThrownBy(() -> useCase.suspend(ADMIN_ID, COUPON_ID)).isInstanceOf(CouponIssueStoreUnavailableException.class);

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.OPEN);
    }

    @Test
    void 이미_중단된_쿠폰을_다시_중단하면_Redis만_다시_막고_성공한다() {
        given(openSellerCoupon());
        coupon.suspend(NOW);

        var view = useCase.suspend(ADMIN_ID, COUPON_ID);

        assertThat(view.status()).isEqualTo(CouponStatus.SUSPENDED);
        verify(issueStore, times(2)).block(COUPON_ID, "SUSPENDED", 0);
    }

    @Test
    void 판매자는_자기_쿠폰만_중단하고_남의_쿠폰이면_Redis를_건드리지_않는다() {
        given(openSellerCoupon());

        assertCouponError(() -> useCase.suspendBySeller(OTHER_MEMBER_ID, COUPON_ID), CouponErrorCode.NOT_COUPON_OWNER);

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.OPEN);
        verifyNoInteractions(issueStore);
    }

    @Test
    void 재개에서_Redis를_열지_못하면_DB는_OPEN이고_다시_요청하면_version을_올려_Redis만_다시_연다() {
        given(openSellerCoupon());
        coupon.suspend(NOW);
        when(issueStore.allowIssue(anyLong(), anyLong()))
                .thenThrow(new CouponIssueStoreUnavailableException("연결 실패", null))
                .thenReturn(AllowResult.APPLIED);
        assertThatThrownBy(() -> useCase.resume(ADMIN_ID, COUPON_ID)).isInstanceOf(CouponIssueStoreUnavailableException.class);
        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.OPEN);

        var view = useCase.resume(ADMIN_ID, COUPON_ID);

        assertThat(view.status()).isEqualTo(CouponStatus.OPEN);
        InOrder order = inOrder(issueStore);
        order.verify(issueStore).allowIssue(COUPON_ID, 1);
        order.verify(issueStore).allowIssue(COUPON_ID, 2);
        verify(issueStore, never()).block(anyLong(), anyString(), anyLong());
    }

    @Test
    void 발급_종료_시각이_지난_중단_쿠폰은_재개하지_않고_Redis도_건드리지_않는다() {
        given(openSellerCoupon());
        coupon.suspend(NOW);
        long version = coupon.getVersion();

        assertThatThrownBy(() -> useCaseAt(ISSUE_END).resume(ADMIN_ID, COUPON_ID))
                .isInstanceOfSatisfying(CouponDomainException.class,
                        e -> assertThat(e.getError()).isEqualTo(CouponDomainError.ISSUE_PERIOD_ENDED));

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.SUSPENDED);
        assertThat(coupon.getVersion()).isEqualTo(version);
        verifyNoInteractions(issueStore);
    }

    @Test
    void 이미_OPEN인_쿠폰의_재개_재호출은_발급_종료가_지나도_Redis_접수를_다시_연다() {
        // 재개 뒤 Redis를 열지 못한 쿠폰을 다시 요청하는 복구 경로다. Redis의 발급 기간 검사가 접수를 막는다
        given(openSellerCoupon());

        var view = useCaseAt(ISSUE_END).resume(ADMIN_ID, COUPON_ID);

        assertThat(view.status()).isEqualTo(CouponStatus.OPEN);
        verify(issueStore).allowIssue(COUPON_ID, 1);
    }

    @Test
    void Redis에서_이미_종료됐으면_재개는_409다() {
        given(openSellerCoupon());
        coupon.suspend(NOW);
        when(issueStore.allowIssue(anyLong(), anyLong())).thenThrow(new IllegalStateException("CLOSED"));

        assertCouponError(() -> useCase.resume(ADMIN_ID, COUPON_ID), CouponErrorCode.INVALID_COUPON_STATUS);
    }

    @Test
    void 종료는_되돌릴_수_있는_SUSPENDED로_먼저_막고_CLOSED_커밋_뒤에만_Redis를_CLOSED로_막는다() {
        given(openSellerCoupon());
        coupon.suspend(NOW);

        var view = useCase.close(ADMIN_ID, COUPON_ID);

        assertThat(view.status()).isEqualTo(CouponStatus.CLOSED);
        InOrder order = inOrder(issueStore);
        order.verify(issueStore).block(COUPON_ID, "SUSPENDED", 0);
        order.verify(issueStore).block(COUPON_ID, "CLOSED", 1);
        assertThat(statusAtBlock).containsExactly(CouponStatus.SUSPENDED, CouponStatus.CLOSED);
    }

    @Test
    void 막은_뒤_다른_요청이_version을_올렸으면_DB를_바꾸지_않고_새_version으로_다시_막은_뒤_전이한다() {
        given(openSellerCoupon());
        AtomicBoolean bumped = new AtomicBoolean();
        doAnswer(invocation -> {
            // 첫 막기 직후 다른 재개 요청이 version을 올리고 Redis를 다시 연 상황
            if (bumped.compareAndSet(false, true)) {
                ReflectionTestUtils.setField(coupon, "version", coupon.getVersion() + 1);
            }
            return null;
        }).when(issueStore).block(anyLong(), anyString(), anyLong());

        var view = useCase.suspend(ADMIN_ID, COUPON_ID);

        assertThat(view.status()).isEqualTo(CouponStatus.SUSPENDED);
        InOrder order = inOrder(issueStore);
        order.verify(issueStore).block(COUPON_ID, "SUSPENDED", 0);
        order.verify(issueStore).block(COUPON_ID, "SUSPENDED", 1);
        order.verify(issueStore).block(COUPON_ID, "SUSPENDED", 2);
    }

    @Test
    void 종료_차단을_확인하지_못하면_DB는_그대로다() {
        given(openSellerCoupon());
        doThrow(new CouponIssueStoreUnavailableException("확인 실패", null)).when(issueStore)
                .block(anyLong(), anyString(), anyLong());

        assertThatThrownBy(() -> useCase.close(ADMIN_ID, COUPON_ID)).isInstanceOf(CouponIssueStoreUnavailableException.class);

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.OPEN);
    }
}

package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.*;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStore.AllowResult;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueControl;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.app.port.LectureCatalog;
import com.hapbang.coupon.app.port.LectureCatalog.LectureSnapshot;
import com.hapbang.coupon.app.port.PromotionBudgetPort;
import com.hapbang.coupon.app.port.PromotionBudgetPort.BudgetResult;
import com.hapbang.coupon.app.port.PromotionBudgetPort.Outcome;
import com.hapbang.coupon.domain.BudgetWork;
import com.hapbang.coupon.domain.BudgetWorkStatus;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.BudgetWorkRepository;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.redis.CouponRedisProperties;

// 발급 열기의 단계 순서와 실패 뒤 남는 상태, 재시도를 본다. 저장소는 같은 객체를 돌려주는 대역이라 트랜잭션 사이에 상태가 이어지고,
// 커밋 때 version이 오른다(CouponVersionFake). Redis 발급 저장소는 mock이라 호출 순서·fence 인자를 본다
class CouponOpenUseCaseTest {

    private static final String WORK_ID = "coupon-1-reserve-v1";
    private static final long BUDGET = 100_000L;
    private static final Duration KEYS_EXPIRE_MARGIN = Duration.ofDays(7);

    private final CouponRepository couponRepository = mock();
    private final BudgetWorkRepository budgetWorkRepository = mock();
    private final CouponIssueStore issueStore = mock();
    private final PromotionBudgetPort budgetPort = mock();
    private final LectureCatalog lectureCatalog = mock();
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PromotionBudgetPort> budgetPortProvider = mock();
    private final Map<String, BudgetWork> works = new HashMap<>();
    private final CouponVersionFake db = new CouponVersionFake(couponRepository);

    private CouponOpenUseCase useCase;
    private Coupon coupon;

    @BeforeEach
    void setUp() {
        CouponSupport support = new CouponSupport(couponRepository, lectureCatalog, issueStore);
        useCase = new CouponOpenUseCase(support, budgetWorkRepository, issueStore, budgetPortProvider,
                new CouponRedisProperties(KEYS_EXPIRE_MARGIN), db.transactionManager(), CLOCK);
        when(budgetPortProvider.getIfAvailable()).thenReturn(budgetPort);
        lectureSaleStatus("ON_SALE");
        when(issueStore.allowIssue(anyLong(), anyLong())).thenReturn(AllowResult.APPLIED);
        when(budgetWorkRepository.findById(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(works.get(invocation.<String>getArgument(0))));
        when(budgetWorkRepository.save(any())).thenAnswer(invocation -> {
            BudgetWork work = invocation.getArgument(0);
            works.put(work.getWorkId(), work);
            return work;
        });
    }

    private void given(Coupon value) {
        coupon = value;
        db.given(coupon);
    }

    private void lectureSaleStatus(String saleStatus) {
        when(lectureCatalog.findLecture(LECTURE_ID))
                .thenReturn(Optional.of(new LectureSnapshot(LECTURE_ID, SELLER_ID, 10_000L, saleStatus)));
    }

    private void budgetReturns(Outcome outcome, String reservationId) {
        when(budgetPort.reserve(WORK_ID, COUPON_ID, 1, BUDGET)).thenReturn(new BudgetResult(outcome, reservationId, null));
    }

    @Test
    void 플랫폼_쿠폰은_예산_예약_Redis_준비_OPEN_커밋_뒤에_커밋된_version으로_접수를_연다() {
        given(draftPlatformCoupon());
        budgetReturns(Outcome.SUCCEEDED, "rsv-1");
        List<CouponStatus> statusAtRedis = new ArrayList<>();
        doAnswer(invocation -> statusAtRedis.add(coupon.getStatus())).when(issueStore).prepare(any());
        when(issueStore.allowIssue(anyLong(), anyLong())).thenAnswer(invocation -> {
            statusAtRedis.add(coupon.getStatus());
            return AllowResult.APPLIED;
        });

        var view = useCase.open(ADMIN_ID, COUPON_ID);

        assertThat(view.status()).isEqualTo(CouponStatus.OPEN);
        assertThat(coupon.getBudgetReservationId()).isEqualTo("rsv-1");
        assertThat(coupon.isRedisReady()).isTrue();
        assertThat(works.get(WORK_ID).getStatus()).isEqualTo(BudgetWorkStatus.DONE);
        // 예산 기록 커밋으로 version 1, 준비는 그 version을 fence로, OPEN 커밋(version 2) 뒤에 접수를 연다
        InOrder order = inOrder(budgetPort, issueStore);
        order.verify(budgetPort).reserve(WORK_ID, COUPON_ID, 1, BUDGET);
        order.verify(issueStore).prepare(new IssueControl(COUPON_ID, 1, 100, ISSUE_START, ISSUE_END,
                ISSUE_END.plus(KEYS_EXPIRE_MARGIN), 1));
        order.verify(issueStore).allowIssue(COUPON_ID, 2);
        assertThat(statusAtRedis).containsExactly(CouponStatus.DRAFT, CouponStatus.OPEN);
        assertThat(coupon.getVersion()).isEqualTo(2);
    }

    @Test
    void 예산_예약이_거절되면_작업은_FAILED이고_쿠폰은_DRAFT로_남는다() {
        given(draftPlatformCoupon());
        budgetReturns(Outcome.REJECTED, null);

        assertCouponError(() -> useCase.open(ADMIN_ID, COUPON_ID), CouponErrorCode.PROMOTION_BUDGET_REJECTED);

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.DRAFT);
        assertThat(works.get(WORK_ID).getStatus()).isEqualTo(BudgetWorkStatus.FAILED);
        verifyNoInteractions(issueStore);
    }

    static Stream<Arguments> unknownBudgetResults() {
        return Stream.of(
                Arguments.of("UNKNOWN", (Consumer<PromotionBudgetPort>) port -> when(port.reserve(any(), anyLong(),
                        anyInt(), anyLong())).thenReturn(new BudgetResult(Outcome.UNKNOWN, null, "timeout"))),
                Arguments.of("호출 예외", (Consumer<PromotionBudgetPort>) port -> when(port.reserve(any(), anyLong(),
                        anyInt(), anyLong())).thenThrow(new IllegalStateException("연결 실패"))),
                Arguments.of("예약 ID 없는 성공", (Consumer<PromotionBudgetPort>) port -> when(port.reserve(any(),
                        anyLong(), anyInt(), anyLong())).thenReturn(new BudgetResult(Outcome.SUCCEEDED, " ", null))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unknownBudgetResults")
    void 예산_결과를_모르면_작업은_PENDING이고_열지_않는다(String name, Consumer<PromotionBudgetPort> stub) {
        given(draftPlatformCoupon());
        stub.accept(budgetPort);

        assertCouponError(() -> useCase.open(ADMIN_ID, COUPON_ID), CouponErrorCode.PROMOTION_BUDGET_UNAVAILABLE);

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.DRAFT);
        assertThat(coupon.getBudgetReservationId()).isNull();
        assertThat(works.get(WORK_ID).getStatus()).isEqualTo(BudgetWorkStatus.PENDING);
        verifyNoInteractions(issueStore);
    }

    @Test
    void Redis를_준비하지_못하면_쿠폰은_DRAFT이고_예산_작업은_DONE으로_남으며_접수를_열지_않는다() {
        given(draftPlatformCoupon());
        budgetReturns(Outcome.SUCCEEDED, "rsv-1");
        doThrow(new CouponIssueStoreUnavailableException("연결 실패", null)).when(issueStore).prepare(any());

        assertThatThrownBy(() -> useCase.open(ADMIN_ID, COUPON_ID)).isInstanceOf(CouponIssueStoreUnavailableException.class);

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.DRAFT);
        assertThat(coupon.isRedisReady()).isFalse();
        assertThat(coupon.getBudgetReservationId()).isEqualTo("rsv-1");
        assertThat(works.get(WORK_ID).getStatus()).isEqualTo(BudgetWorkStatus.DONE);
        verify(issueStore, never()).allowIssue(anyLong(), anyLong());
    }

    @Test
    void 예산_작업이_DONE이면_다시_열_때_예산을_다시_부르지_않는다() {
        given(draftPlatformCoupon());
        budgetReturns(Outcome.SUCCEEDED, "rsv-1");
        doThrow(new CouponIssueStoreUnavailableException("연결 실패", null)).doNothing().when(issueStore).prepare(any());
        assertThatThrownBy(() -> useCase.open(ADMIN_ID, COUPON_ID)).isInstanceOf(CouponIssueStoreUnavailableException.class);

        var view = useCase.open(ADMIN_ID, COUPON_ID);

        assertThat(view.status()).isEqualTo(CouponStatus.OPEN);
        verify(budgetPort, times(1)).reserve(any(), anyLong(), anyInt(), anyLong());
        verify(issueStore, times(2)).prepare(any());
    }

    @Test
    void 예산_작업이_PENDING이면_같은_작업_ID로_다시_예약한다() {
        given(draftPlatformCoupon());
        when(budgetPort.reserve(WORK_ID, COUPON_ID, 1, BUDGET))
                .thenReturn(new BudgetResult(Outcome.UNKNOWN, null, "timeout"))
                .thenReturn(new BudgetResult(Outcome.SUCCEEDED, "rsv-1", null));
        assertCouponError(() -> useCase.open(ADMIN_ID, COUPON_ID), CouponErrorCode.PROMOTION_BUDGET_UNAVAILABLE);

        var view = useCase.open(ADMIN_ID, COUPON_ID);

        assertThat(view.status()).isEqualTo(CouponStatus.OPEN);
        verify(budgetPort, times(2)).reserve(WORK_ID, COUPON_ID, 1, BUDGET);
        verify(budgetWorkRepository, times(1)).save(any());
    }

    @Test
    void 거절된_예산_작업은_남기고_다시_열면_시도_번호를_붙인_새_작업_ID로_다시_예약한다() {
        String retryWorkId = WORK_ID + "-a2";
        given(draftPlatformCoupon());
        budgetReturns(Outcome.REJECTED, null);
        when(budgetPort.reserve(retryWorkId, COUPON_ID, 1, BUDGET))
                .thenReturn(new BudgetResult(Outcome.SUCCEEDED, "rsv-2", null));
        assertCouponError(() -> useCase.open(ADMIN_ID, COUPON_ID), CouponErrorCode.PROMOTION_BUDGET_REJECTED);

        var view = useCase.open(ADMIN_ID, COUPON_ID);

        assertThat(view.status()).isEqualTo(CouponStatus.OPEN);
        assertThat(coupon.getBudgetReservationId()).isEqualTo("rsv-2");
        assertThat(works.get(WORK_ID).getStatus()).isEqualTo(BudgetWorkStatus.FAILED);
        assertThat(works.get(retryWorkId).getStatus()).isEqualTo(BudgetWorkStatus.DONE);
        assertThat(works.get(retryWorkId).getResult()).isEqualTo("rsv-2");
        InOrder order = inOrder(budgetPort);
        order.verify(budgetPort).reserve(WORK_ID, COUPON_ID, 1, BUDGET);
        order.verify(budgetPort).reserve(retryWorkId, COUPON_ID, 1, BUDGET);
        verify(budgetWorkRepository, times(2)).save(any());
    }

    @Test
    void 같은_새_작업_ID를_다른_요청이_먼저_저장했으면_409이고_billing을_부르지_않는다() {
        given(draftPlatformCoupon());
        doThrow(new DataIntegrityViolationException("pk_budget_work")).when(budgetWorkRepository).save(any());

        assertCouponError(() -> useCase.open(ADMIN_ID, COUPON_ID), CouponErrorCode.COUPON_CONCURRENT_UPDATE);

        verifyNoInteractions(budgetPort, issueStore);
    }

    @Test
    void 판매_중이_아닌_강의의_플랫폼_쿠폰은_409이고_예산과_Redis를_부르지_않는다() {
        given(draftPlatformCoupon());
        lectureSaleStatus("REJECTED");

        CouponCreateUseCaseTest.assertNotOnSale(() -> useCase.open(ADMIN_ID, COUPON_ID));

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.DRAFT);
        verifyNoInteractions(budgetPort, budgetWorkRepository, issueStore);
    }

    @Test
    void 판매_중이_아닌_강의의_판매자_쿠폰은_승인하지_않고_Redis를_부르지_않는다() {
        given(reviewSellerCoupon());
        lectureSaleStatus("PENDING_INSPECTION");

        CouponCreateUseCaseTest.assertNotOnSale(() -> useCase.approve(ADMIN_ID, COUPON_ID));

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.REVIEW);
        verifyNoInteractions(issueStore);
    }

    @Test
    void 모든_강의_대상_플랫폼_쿠폰은_강의를_조회하지_않고_연다() {
        given(withId(Coupon.createPlatform(ADMIN_ID, anyLectureAmount().build(), NOW)));
        when(budgetPort.reserve(eq(WORK_ID), eq(COUPON_ID), eq(1), anyLong()))
                .thenReturn(new BudgetResult(Outcome.SUCCEEDED, "rsv-1", null));

        var view = useCase.open(ADMIN_ID, COUPON_ID);

        assertThat(view.status()).isEqualTo(CouponStatus.OPEN);
        assertThat(coupon.getTargetType()).isEqualTo(CouponTargetType.ANY_LECTURE);
        verifyNoInteractions(lectureCatalog);
    }

    @Test
    void 예산_연동이_없으면_503이고_아무것도_하지_않는다() {
        given(draftPlatformCoupon());
        when(budgetPortProvider.getIfAvailable()).thenReturn(null);

        assertCouponError(() -> useCase.open(ADMIN_ID, COUPON_ID), CouponErrorCode.PROMOTION_BUDGET_UNAVAILABLE);

        assertThat(works).isEmpty();
        verifyNoInteractions(issueStore);
    }

    @Test
    void 판매자_쿠폰_승인은_예산_없이_Redis를_열고_OPEN이_된다() {
        given(reviewSellerCoupon());

        var view = useCase.approve(ADMIN_ID, COUPON_ID);

        assertThat(view.status()).isEqualTo(CouponStatus.OPEN);
        InOrder order = inOrder(issueStore);
        order.verify(issueStore).prepare(argThat(control -> control.fence() == 0));
        order.verify(issueStore).allowIssue(COUPON_ID, 1);
        verifyNoInteractions(budgetPortProvider, budgetPort, budgetWorkRepository);
    }

    @Test
    void Redis를_준비하는_사이_거절되면_OPEN으로_바꾸지_않고_접수도_열지_않는다() {
        given(reviewSellerCoupon());
        doAnswer(invocation -> {
            coupon.reject("다른 관리자가 먼저 거절했다", NOW);
            return null;
        }).when(issueStore).prepare(any());

        assertCouponError(() -> useCase.approve(ADMIN_ID, COUPON_ID), CouponErrorCode.INVALID_COUPON_STATUS);

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.REJECTED);
        verify(issueStore, never()).allowIssue(anyLong(), anyLong());
        verify(issueStore, never()).block(anyLong(), anyString(), anyLong());
    }

    @Test
    void 승인_뒤_접수_열기_응답을_잃으면_DB는_OPEN이고_다시_승인하면_version을_올려_접수를_다시_연다() {
        given(reviewSellerCoupon());
        when(issueStore.allowIssue(anyLong(), anyLong()))
                .thenThrow(new CouponIssueStoreUnavailableException("응답 유실", null))
                .thenReturn(AllowResult.APPLIED);
        assertThatThrownBy(() -> useCase.approve(ADMIN_ID, COUPON_ID)).isInstanceOf(CouponIssueStoreUnavailableException.class);
        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.OPEN);

        var view = useCase.approve(ADMIN_ID, COUPON_ID);

        assertThat(view.status()).isEqualTo(CouponStatus.OPEN);
        InOrder order = inOrder(issueStore);
        order.verify(issueStore).allowIssue(COUPON_ID, 1);
        order.verify(issueStore).allowIssue(COUPON_ID, 2);
        verify(issueStore, times(1)).prepare(any());
    }

    @Test
    void 열린_플랫폼_쿠폰을_다시_열면_예산_준비_없이_version을_올려_접수를_다시_연다() {
        given(draftPlatformCoupon());
        budgetReturns(Outcome.SUCCEEDED, "rsv-1");
        useCase.open(ADMIN_ID, COUPON_ID);
        long openedVersion = coupon.getVersion();

        var view = useCase.open(ADMIN_ID, COUPON_ID);

        assertThat(view.status()).isEqualTo(CouponStatus.OPEN);
        verify(issueStore).allowIssue(COUPON_ID, openedVersion + 1);
        verify(budgetPort, times(1)).reserve(any(), anyLong(), anyInt(), anyLong());
        verify(issueStore, times(1)).prepare(any());
    }

    @Test
    void 유형이_다른_쿠폰은_OPEN이어도_다시_열지_않는다() {
        given(openSellerCoupon());

        assertCouponError(() -> useCase.open(ADMIN_ID, COUPON_ID), CouponErrorCode.INVALID_COUPON_STATUS);

        verifyNoInteractions(issueStore);
    }

    @Test
    void 접수_열기가_더_새_열기에_밀려도_DB가_더_새_version으로_OPEN이면_성공이다() {
        given(reviewSellerCoupon());
        when(issueStore.allowIssue(anyLong(), anyLong())).thenAnswer(invocation -> {
            // 그 사이 같은 쿠폰을 연 다른 요청이 version을 올려 커밋하고 Redis를 먼저 열었다
            ReflectionTestUtils.setField(coupon, "version", coupon.getVersion() + 1);
            return AllowResult.SUPERSEDED;
        });

        var view = useCase.approve(ADMIN_ID, COUPON_ID);

        assertThat(view.status()).isEqualTo(CouponStatus.OPEN);
    }

    static void assertCouponError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, CouponErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(CouponException.class).extracting("errorCode").isEqualTo(code);
    }
}

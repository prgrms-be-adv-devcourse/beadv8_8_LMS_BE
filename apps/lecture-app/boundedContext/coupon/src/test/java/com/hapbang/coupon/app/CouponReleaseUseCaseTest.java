package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.CLOCK;
import static com.hapbang.coupon.app.CouponUsageFixture.*;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.CouponUsage;
import com.hapbang.coupon.domain.CouponUsageStatus;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.domain.ProcessedOperation;
import com.hapbang.coupon.domain.usage.AppliedCoupon;
import com.hapbang.coupon.domain.usage.UsageItem;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.out.CouponUsageRepository;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.coupon.out.MemberCouponState;
import com.hapbang.coupon.out.ProcessedOperationRepository;
import com.hapbang.shared.coupon.api.CouponReleaseRequest;
import com.hapbang.shared.coupon.api.CouponReleaseResponse;

// 해제 서비스의 흐름(사용 시도 확인 → 조건부 갱신 → 해제 사실·작업 기록, 같은 작업 ID 재응답, 해제 불가)을 본다.
// AVAILABLE·EXPIRED를 가르는 조건은 저장소 테스트가 실제 DB로 본다
class CouponReleaseUseCaseTest {

    private static final String WORK_ID = "order-900:release:1";
    private static final long MEMBER_COUPON = 31L;

    private final MemberCouponRepository memberCouponRepository = mock();
    private final CouponUsageRepository couponUsageRepository = mock();
    private final ProcessedOperationRepository processedOperationRepository = mock();
    private CouponReleaseUseCase useCase;
    private CouponUsage usage;

    @BeforeEach
    void setUp() {
        var transactionManager = transactionManager();
        useCase = new CouponReleaseUseCase(org.mockito.Mockito.mock(CouponBudgetReleaseRecorder.class), support(memberCouponRepository, processedOperationRepository,
                transactionManager), memberCouponRepository, couponUsageRepository, processedOperationRepository,
                transactionManager, CLOCK);
        usage = CouponUsage.reserve(MEMBER_ID, ORDER_ID, new UsageItem(ORDER_ITEM_ID, LECTURE_ID, SELLER_ID, PRICE),
                new AppliedCoupon(MEMBER_COUPON, 1L, CouponType.SELLER, 1, PRICE, 1_000, 0, 1_000, 0, 1),
                "order-900:reserve:1", NOW);
        when(couponUsageRepository.findByMemberCouponIdAndGeneration(MEMBER_COUPON, 1)).thenReturn(Optional.of(usage));
        when(processedOperationRepository.findByOperationId(anyString())).thenReturn(Optional.empty());
        when(memberCouponRepository.release(MEMBER_COUPON, 1, NOW)).thenReturn(1);
    }

    @Test
    void 만료_전이면_AVAILABLE로_해제하고_해제_사실과_작업_기록을_남긴다() {
        givenStateAfterRelease(MemberCouponStatus.AVAILABLE);

        CouponReleaseResponse response = useCase.release(request(ORDER_ID));

        assertThat(response.usages()).singleElement().satisfies(released -> {
            assertThat(released.memberCouponId()).isEqualTo(MEMBER_COUPON);
            assertThat(released.generation()).isEqualTo(1);
            assertThat(released.releasedTo()).isEqualTo("AVAILABLE");
        });
        assertThat(usage.getStatus()).isEqualTo(CouponUsageStatus.RELEASED);
        assertThat(usage.getReleaseWorkId()).isEqualTo(WORK_ID);
        assertThat(usage.getReleaseCompensationRef()).isEqualTo("comp-1");
        verify(processedOperationRepository).saveAndFlush(argThat(operation ->
                operation.getOperationId().equals("coupon-release:" + WORK_ID)
                        && operation.getResult().equals("RELEASED")));
    }

    @Test
    void 만료_뒤면_EXPIRED로_해제한다() {
        givenStateAfterRelease(MemberCouponStatus.EXPIRED);

        CouponReleaseResponse response = useCase.release(request(ORDER_ID));

        assertThat(response.usages().getFirst().releasedTo()).isEqualTo("EXPIRED");
        assertThat(usage.getReleasedTo()).isEqualTo(MemberCouponStatus.EXPIRED);
    }

    @Test
    void 같은_작업_ID와_같은_내용이면_조건부_갱신_없이_처음_결과를_다시_준다() {
        usage.release(WORK_ID, "주문 실패", "comp-1", MemberCouponStatus.AVAILABLE, NOW);
        CouponReleaseRequest request = request(ORDER_ID);
        when(processedOperationRepository.findByOperationId("coupon-release:" + WORK_ID)).thenReturn(Optional.of(
                ProcessedOperation.ofWork("coupon-release:" + WORK_ID,
                        CouponReleaseUseCase.contentKey(request, MEMBER_ID), "RELEASED", null, NOW)));
        when(couponUsageRepository.findByOrderIdAndReleaseWorkIdOrderByMemberCouponIdAsc(ORDER_ID, WORK_ID))
                .thenReturn(List.of(usage));

        CouponReleaseResponse response = useCase.release(request);

        assertThat(response.usages().getFirst().releasedTo()).isEqualTo("AVAILABLE");
        verify(memberCouponRepository, never()).release(anyLong(), anyLong(), any());
    }

    @Test
    void 발급_쿠폰이_그_사용_시도로_예약된_상태가_아니면_USED처럼_해제할_수_없다() {
        when(memberCouponRepository.release(MEMBER_COUPON, 1, NOW)).thenReturn(0);
        when(memberCouponRepository.findStateById(MEMBER_COUPON)).thenReturn(Optional.of(new MemberCouponState(
                MEMBER_COUPON, MEMBER_ID, MemberCouponStatus.USED, 1, EXPIRES_AT, ORDER_ITEM_ID)));

        assertUsageError(() -> useCase.release(request(ORDER_ID)), CouponErrorCode.COUPON_RELEASE_NOT_ALLOWED,
                MEMBER_COUPON);
        verify(processedOperationRepository, never()).saveAndFlush(any());
    }

    @Test
    void 다른_주문의_사용_시도는_해제할_수_없다() {
        assertUsageError(() -> useCase.release(request(ORDER_ID + 1)), CouponErrorCode.COUPON_RELEASE_NOT_ALLOWED,
                MEMBER_COUPON);
        verify(memberCouponRepository, never()).release(anyLong(), anyLong(), any());
    }

    private void givenStateAfterRelease(MemberCouponStatus status) {
        when(memberCouponRepository.findStateById(MEMBER_COUPON)).thenReturn(Optional.of(new MemberCouponState(
                MEMBER_COUPON, MEMBER_ID, status, 1, EXPIRES_AT, null)));
    }

    private static CouponReleaseRequest request(long orderId) {
        return new CouponReleaseRequest(orderId, WORK_ID, List.of(new CouponReleaseRequest.Usage(MEMBER_COUPON, 1L)),
                "주문 실패", "comp-1");
    }
}

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
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.domain.ProcessedOperation;
import com.hapbang.coupon.domain.usage.AppliedCoupon;
import com.hapbang.coupon.domain.usage.UsageContentKey;
import com.hapbang.coupon.domain.usage.UsageItem;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.CouponUsageRepository;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.coupon.out.ProcessedOperationRepository;
import com.hapbang.shared.coupon.api.CouponReservationStatusResponse;

// 예약 상태 조회의 네 상태와 소유 문맥, 가격 인상 제한 조회의 전달을 본다
class CouponReservationStatusQueryTest {

    private static final String WORK_ID = "order-900:reserve:1";
    private static final String OPERATION_ID = "coupon-reserve:" + WORK_ID;

    private final ProcessedOperationRepository processedOperationRepository = mock();
    private final CouponUsageRepository couponUsageRepository = mock();
    private CouponReservationStatusQuery query;

    @BeforeEach
    void setUp() {
        query = new CouponReservationStatusQuery(support(mock(MemberCouponRepository.class),
                processedOperationRepository, transactionManager()), couponUsageRepository);
        when(processedOperationRepository.findByOperationId(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void 기록이_없으면_NOT_FOUND다() {
        CouponReservationStatusResponse response = query.status(WORK_ID, MEMBER_ID, ORDER_ID);

        assertThat(response.status()).isEqualTo("NOT_FOUND");
        assertThat(response.items()).isEmpty();
    }

    @Test
    void 다른_회원이나_다른_주문의_작업이면_NOT_FOUND다() {
        givenRecorded("RESERVED", null);

        assertThat(query.status(WORK_ID, OTHER_MEMBER_ID, ORDER_ID).status()).isEqualTo("NOT_FOUND");
        assertThat(query.status(WORK_ID, MEMBER_ID, ORDER_ID + 1).status()).isEqualTo("NOT_FOUND");
        verifyNoInteractions(couponUsageRepository);
    }

    @Test
    void 거절_기록이면_REJECTED와_거절_코드만_준다() {
        givenRecorded("REJECTED", "COUPON_NOT_USABLE:31");

        CouponReservationStatusResponse response = query.status(WORK_ID, MEMBER_ID, ORDER_ID);

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.rejectCode()).isEqualTo("COUPON_NOT_USABLE");
        assertThat(response.items()).isEmpty();
    }

    @Test
    void 예약_기록이면_사용_시도별_상태를_주고_하나라도_예약_중이면_RESERVED다() {
        givenRecorded("RESERVED", null);
        CouponUsage reserved = usage(31L, 1);
        CouponUsage released = usage(32L, 2);
        released.release("order-900:release:1", "주문 실패", null, MemberCouponStatus.AVAILABLE, NOW);
        when(couponUsageRepository.findByReservationWorkIdOrderByOrderItemIdAscApplyOrderAsc(WORK_ID))
                .thenReturn(List.of(reserved, released));

        CouponReservationStatusResponse response = query.status(WORK_ID, MEMBER_ID, ORDER_ID);

        assertThat(response.status()).isEqualTo("RESERVED");
        assertThat(response.rejectCode()).isNull();
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.discount()).isEqualTo(2_000);
            assertThat(item.coupons()).extracting(CouponReservationStatusResponse.Coupon::status)
                    .containsExactly("RESERVED", "RELEASED");
        });
    }

    @Test
    void 모든_사용_시도가_해제됐으면_RELEASED다() {
        givenRecorded("RESERVED", null);
        CouponUsage released = usage(31L, 1);
        released.release("order-900:release:1", "주문 실패", null, MemberCouponStatus.EXPIRED, NOW);
        when(couponUsageRepository.findByReservationWorkIdOrderByOrderItemIdAscApplyOrderAsc(WORK_ID))
                .thenReturn(List.of(released));

        assertThat(query.status(WORK_ID, MEMBER_ID, ORDER_ID).status()).isEqualTo("RELEASED");
    }

    @Test
    void 가격_인상_제한은_저장소_판정을_강의_ID와_함께_돌려준다() {
        CouponRepository couponRepository = mock();
        when(couponRepository.existsPriceRaiseBlocker(LECTURE_ID, NOW)).thenReturn(true);

        var response = new CouponPriceRaiseBlockedQuery(couponRepository, CLOCK).blocked(LECTURE_ID);

        assertThat(response.lectureId()).isEqualTo(LECTURE_ID);
        assertThat(response.blocked()).isTrue();
    }

    private void givenRecorded(String result, String detail) {
        when(processedOperationRepository.findByOperationId(OPERATION_ID)).thenReturn(Optional.of(
                ProcessedOperation.ofWork(OPERATION_ID, UsageContentKey.of(MEMBER_ID, ORDER_ID, List.of("x")), result,
                        detail, NOW)));
    }

    private static CouponUsage usage(long memberCouponId, int applyOrder) {
        CouponType type = applyOrder == 1 ? CouponType.SELLER : CouponType.PLATFORM;
        long seller = type == CouponType.SELLER ? 1_000 : 0;
        return CouponUsage.reserve(MEMBER_ID, ORDER_ID, new UsageItem(ORDER_ITEM_ID, LECTURE_ID, SELLER_ID, PRICE),
                new AppliedCoupon(memberCouponId, memberCouponId, type, applyOrder, PRICE, 1_000, 1_000 - seller,
                        seller, 0, 1), WORK_ID, NOW);
    }
}

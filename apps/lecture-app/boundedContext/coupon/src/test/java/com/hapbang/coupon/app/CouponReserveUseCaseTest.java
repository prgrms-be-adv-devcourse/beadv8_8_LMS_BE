package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.CLOCK;
import static com.hapbang.coupon.app.CouponUsageFixture.*;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

import com.hapbang.coupon.domain.CouponUsage;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.domain.ProcessedOperation;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.out.CouponUsageRepository;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.coupon.out.MemberCouponState;
import com.hapbang.coupon.out.ProcessedOperationRepository;
import com.hapbang.shared.coupon.api.CouponReserveRequest;
import com.hapbang.shared.coupon.api.CouponReserveResponse;

// 예약 서비스의 흐름(재검증·근거 대조 → 오름차순 조건부 갱신 → 사용 시도·작업 기록, 거절 기록, 같은 작업 ID 재응답)을 본다.
// 실제 롤백과 동시 예약은 CouponUsageE2eTest가, 금액·근거 대조 규칙은 도메인 테스트가 본다
class CouponReserveUseCaseTest {

    private static final String WORK_ID = "order-900:reserve:1";
    private static final long SELLER_COUPON = 21L;
    private static final long PLATFORM_COUPON = 12L;

    private final MemberCouponRepository memberCouponRepository = mock();
    private final CouponUsageRepository couponUsageRepository = mock();
    private final ProcessedOperationRepository processedOperationRepository = mock();
    private final PlatformTransactionManager transactionManager = transactionManager();
    private final List<CouponUsage> savedUsages = new ArrayList<>();
    private CouponReserveUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CouponReserveUseCase(support(memberCouponRepository, processedOperationRepository,
                transactionManager), memberCouponRepository, couponUsageRepository, processedOperationRepository,
                transactionManager, CLOCK);
        givenOwnedByIds(memberCouponRepository, owned(SELLER_COUPON, MEMBER_ID, sellerRateCoupon(1L)),
                owned(PLATFORM_COUPON, MEMBER_ID, platformAnyAmountCoupon(2L)));
        when(memberCouponRepository.reserve(anyLong(), anyLong(), anyLong(), anyLong(), any())).thenReturn(1);
        when(processedOperationRepository.findByOperationId(anyString())).thenReturn(Optional.empty());
        when(couponUsageRepository.saveAllAndFlush(anyList())).thenAnswer(invocation -> {
            savedUsages.addAll(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        when(couponUsageRepository.findByReservationWorkIdOrderByOrderItemIdAscApplyOrderAsc(WORK_ID))
                .thenAnswer(invocation -> savedUsages.stream()
                        .sorted((left, right) -> Integer.compare(left.getApplyOrder(), right.getApplyOrder()))
                        .toList());
    }

    @Test
    void 판매자_플랫폼_두_장을_발급_쿠폰_ID_오름차순으로_예약하고_사용_시도와_작업_기록을_남긴다() {
        CouponReserveResponse response = useCase.reserve(request(sellerBasis(1_000), platformBasis()));

        InOrder order = inOrder(memberCouponRepository);
        order.verify(memberCouponRepository).reserve(PLATFORM_COUPON, MEMBER_ID, 0, ORDER_ITEM_ID, NOW);
        order.verify(memberCouponRepository).reserve(SELLER_COUPON, MEMBER_ID, 0, ORDER_ITEM_ID, NOW);
        assertThat(savedUsages).extracting(CouponUsage::getGeneration).containsOnly(1L);
        ArgumentCaptor<ProcessedOperation> operation = ArgumentCaptor.forClass(ProcessedOperation.class);
        verify(processedOperationRepository).saveAndFlush(operation.capture());
        assertThat(operation.getValue().getOperationId()).isEqualTo("coupon-reserve:" + WORK_ID);
        assertThat(operation.getValue().getResult()).isEqualTo("RESERVED");
        assertThat(operation.getValue().getContentKey()).startsWith("m" + MEMBER_ID + "|o" + ORDER_ID + "|");

        assertThat(response.status()).isEqualTo("RESERVED");
        CouponReserveResponse.Item item = response.items().getFirst();
        assertThat(item.orderItemId()).isEqualTo(ORDER_ITEM_ID);
        assertThat(item.discount()).isEqualTo(2_500);
        assertThat(item.sellerDiscount()).isEqualTo(1_000);
        assertThat(item.platformDiscount()).isEqualTo(1_500);
        assertThat(item.coupons()).extracting(CouponReserveResponse.Coupon::memberCouponId)
                .containsExactly(SELLER_COUPON, PLATFORM_COUPON);
        assertThat(item.coupons()).extracting(CouponReserveResponse.Coupon::reservedGeneration).containsOnly(1L);
    }

    @Test
    void 한_장의_조건부_갱신이_0행이면_사용_시도를_쓰지_않고_롤백한_뒤_거절_코드와_발급_쿠폰을_기록한다() {
        when(memberCouponRepository.reserve(eq(SELLER_COUPON), anyLong(), anyLong(), anyLong(), any())).thenReturn(0);
        when(memberCouponRepository.findStateById(SELLER_COUPON)).thenReturn(Optional.of(new MemberCouponState(
                SELLER_COUPON, MEMBER_ID, MemberCouponStatus.RESERVED, 1, EXPIRES_AT, 777L)));

        assertUsageError(() -> useCase.reserve(request(sellerBasis(1_000), platformBasis())),
                CouponErrorCode.COUPON_NOT_USABLE, SELLER_COUPON);

        verify(couponUsageRepository, never()).saveAllAndFlush(anyList());
        verify(transactionManager).rollback(any());
        ArgumentCaptor<ProcessedOperation> operation = ArgumentCaptor.forClass(ProcessedOperation.class);
        verify(processedOperationRepository).saveAndFlush(operation.capture());
        assertThat(operation.getValue().getResult()).isEqualTo("REJECTED");
        assertThat(operation.getValue().getDetail()).isEqualTo("COUPON_NOT_USABLE:" + SELLER_COUPON);
    }

    @Test
    void 금액_근거가_다시_계산한_값과_다르면_조건부_갱신_전에_거절하고_기록한다() {
        assertUsageError(() -> useCase.reserve(request(sellerBasis(999), platformBasis())),
                CouponErrorCode.COUPON_AMOUNT_MISMATCH, SELLER_COUPON);

        verify(memberCouponRepository, never()).reserve(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verify(processedOperationRepository).saveAndFlush(
                argThat(operation -> "COUPON_AMOUNT_MISMATCH:21".equals(operation.getDetail())));
    }

    @Test
    void 계산_뒤_다른_사용_시도로_세대가_바뀌었으면_세대_불일치로_거절한다() {
        CouponReserveRequest.Coupon staleSeller = new CouponReserveRequest.Coupon(SELLER_COUPON, 1L, 1, 1, 1_000L, 0L,
                1_000L);

        assertUsageError(() -> useCase.reserve(request(staleSeller, platformBasis())),
                CouponErrorCode.COUPON_GENERATION_MISMATCH, SELLER_COUPON);
        verify(memberCouponRepository, never()).reserve(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void 같은_주문_항목이_두_번_있으면_아무것도_읽거나_쓰지_않고_400이다() {
        CouponReserveRequest request = new CouponReserveRequest(MEMBER_ID, ORDER_ID, WORK_ID, List.of(
                new CouponReserveRequest.Item(ORDER_ITEM_ID, LECTURE_ID, SELLER_ID, PRICE, List.of(sellerBasis(1_000))),
                new CouponReserveRequest.Item(ORDER_ITEM_ID, LECTURE_ID + 1, SELLER_ID, PRICE,
                        List.of(platformBasis()))));

        assertUsageError(() -> useCase.reserve(request), CouponErrorCode.COUPON_DUPLICATE_ORDER_ITEM, null);
        verifyNoInteractions(transactionManager, couponUsageRepository);
        verify(memberCouponRepository, never()).reserve(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verify(processedOperationRepository, never()).saveAndFlush(any());
    }

    @Test
    void 같은_작업_ID와_같은_내용의_예약_기록이_있으면_사용_시도로_처음_응답을_다시_만든다() {
        CouponReserveRequest request = request(sellerBasis(1_000), platformBasis());
        useCase.reserve(request);
        givenRecorded(request, "RESERVED", null);
        clearInvocations(memberCouponRepository);

        CouponReserveResponse replayed = useCase.reserve(request);

        assertThat(replayed.items().getFirst().discount()).isEqualTo(2_500);
        verify(memberCouponRepository, never()).reserve(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void 해제한_예약을_같은_작업_ID로_다시_보내면_재응답하지_않고_409이다() {
        CouponReserveRequest request = request(sellerBasis(1_000), platformBasis());
        useCase.reserve(request);
        savedUsages.getFirst().release("order-900:release:1", "주문 실패", null, MemberCouponStatus.AVAILABLE, NOW);
        givenRecorded(request, "RESERVED", null);
        clearInvocations(memberCouponRepository);

        assertUsageError(() -> useCase.reserve(request), CouponErrorCode.COUPON_WORK_CONFLICT, null);
        verify(memberCouponRepository, never()).reserve(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void 같은_작업_ID와_같은_내용의_거절_기록이_있으면_같은_코드와_발급_쿠폰으로_거절한다() {
        CouponReserveRequest request = request(sellerBasis(1_000), platformBasis());
        givenRecorded(request, "REJECTED", "COUPON_GENERATION_MISMATCH:" + SELLER_COUPON);

        assertUsageError(() -> useCase.reserve(request), CouponErrorCode.COUPON_GENERATION_MISMATCH, SELLER_COUPON);
        verifyNoInteractions(transactionManager);
    }

    @Test
    void 같은_작업_ID에_다른_내용이면_409이다() {
        givenRecorded(request(sellerBasis(1_000), platformBasis()), "RESERVED", null);

        assertUsageError(() -> useCase.reserve(request(sellerBasis(1_000))), CouponErrorCode.COUPON_WORK_CONFLICT,
                null);
    }

    private void givenRecorded(CouponReserveRequest request, String result, String detail) {
        when(processedOperationRepository.findByOperationId("coupon-reserve:" + WORK_ID)).thenReturn(Optional.of(
                ProcessedOperation.ofWork("coupon-reserve:" + WORK_ID, CouponReserveUseCase.contentKey(request),
                        result, detail, NOW)));
    }

    private static CouponReserveRequest request(CouponReserveRequest.Coupon... coupons) {
        return new CouponReserveRequest(MEMBER_ID, ORDER_ID, WORK_ID, List.of(new CouponReserveRequest.Item(
                ORDER_ITEM_ID, LECTURE_ID, SELLER_ID, PRICE, List.of(coupons))));
    }

    /** 판매자 10% 쿠폰을 원가격(10,000)에 먼저 적용한 근거 */
    private static CouponReserveRequest.Coupon sellerBasis(long discount) {
        return new CouponReserveRequest.Coupon(SELLER_COUPON, 0L, 1, 1, discount, 0L, discount);
    }

    /** 플랫폼 1,500 HP 쿠폰을 판매자 할인 뒤(9,000)에 적용한 근거 */
    private static CouponReserveRequest.Coupon platformBasis() {
        return new CouponReserveRequest.Coupon(PLATFORM_COUPON, 0L, 1, 2, 1_500L, 1_500L, 0L);
    }
}

package com.hapbang.coupon.app;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.CouponTerms;
import com.hapbang.coupon.domain.DiscountType;
import com.hapbang.coupon.domain.ExpiryType;
import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.CouponUsageRepository;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.coupon.out.ProcessedOperationRepository;
import com.hapbang.shared.coupon.api.CouponReserveRequest;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.RedisTestcontainersConfig;

/**
 * 예약의 앞 장 조건부 갱신이 성공한 뒤 뒤 장이 실패하면 전부 롤백되는지 실제 DB로 결정적으로 본다. 첫 조건부 갱신(ID가 작은 판매자 쿠폰)
 * 직후·두 번째(ID가 큰 플랫폼 쿠폰) 전에, 다른 주문이 별도 트랜잭션으로 플랫폼 쿠폰을 먼저 예약하게 끼워 넣는다.
 */
@IntegrationTest
@Import(RedisTestcontainersConfig.class)
class CouponReserveRollbackTest {

    private static final long ORDER_ID = 9301L;
    private static final long ORDER_ITEM_ID = 93011L;
    private static final long OTHER_ORDER_ITEM_ID = 99999L;
    private static final long PRICE = 10_000L;
    private static final String WORK_ID = "order-9301:reserve:1";

    @Autowired
    private CouponReserveUseCase couponReserveUseCase;

    @MockitoSpyBean
    private MemberCouponRepository memberCouponRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private CouponUsageRepository couponUsageRepository;

    @Autowired
    private ProcessedOperationRepository processedOperationRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void 앞_장을_예약한_뒤_뒤_장의_조건부_갱신이_0행이면_앞_장도_되돌리고_거절을_기록한다() {
        long sellerCoupon = issue(couponRepository.save(Coupon.createSeller(SELLER_ID,
                terms(CouponTargetType.LECTURE, LECTURE_ID, PRICE, DiscountType.RATE, 10), Instant.now())).getId());
        long platformCoupon = issue(couponRepository.save(Coupon.createPlatform(ADMIN_ID,
                terms(CouponTargetType.ANY_LECTURE, null, null, DiscountType.AMOUNT, 1_500), Instant.now())).getId());
        assertThat(sellerCoupon).isLessThan(platformCoupon);
        AtomicInteger firstUpdated = interleaveReservationOf(platformCoupon);

        CouponReserveRequest request = new CouponReserveRequest(MEMBER_ID, ORDER_ID, WORK_ID, List.of(
                new CouponReserveRequest.Item(ORDER_ITEM_ID, LECTURE_ID, SELLER_ID, PRICE, List.of(
                        new CouponReserveRequest.Coupon(sellerCoupon, 0L, 1, 1, 1_000L, 0L, 1_000L),
                        new CouponReserveRequest.Coupon(platformCoupon, 0L, 1, 2, 1_500L, 1_500L, 0L)))));

        CouponUsageFixture.assertUsageError(() -> couponReserveUseCase.reserve(request),
                CouponErrorCode.COUPON_NOT_USABLE, platformCoupon);

        // 판매자 쿠폰의 조건부 갱신은 실제로 한 행을 바꿨다(그 뒤 롤백)
        assertThat(firstUpdated.get()).isEqualTo(1);
        // 먼저 갱신한 판매자 쿠폰은 롤백돼 AVAILABLE·세대 0 그대로다
        assertThat(memberCouponRepository.findStateById(sellerCoupon).orElseThrow()).satisfies(state -> {
            assertThat(state.status()).isEqualTo(MemberCouponStatus.AVAILABLE);
            assertThat(state.generation()).isZero();
            assertThat(state.currentOrderItemId()).isNull();
        });
        // 플랫폼 쿠폰은 끼어든 다른 주문의 예약만 남는다
        assertThat(memberCouponRepository.findStateById(platformCoupon).orElseThrow()).satisfies(state -> {
            assertThat(state.status()).isEqualTo(MemberCouponStatus.RESERVED);
            assertThat(state.currentOrderItemId()).isEqualTo(OTHER_ORDER_ITEM_ID);
        });
        assertThat(couponUsageRepository.count()).isZero();
        assertThat(processedOperationRepository.findByOperationId("coupon-reserve:" + WORK_ID)).get()
                .satisfies(operation -> {
                    assertThat(operation.getResult()).isEqualTo("REJECTED");
                    assertThat(operation.getDetail()).isEqualTo("COUPON_NOT_USABLE:" + platformCoupon);
                });
    }

    /**
     * 첫 예약 조건부 갱신이 끝난 직후, 다른 주문이 별도 트랜잭션으로 이 발급 쿠폰을 먼저 예약한다(한 번만).
     *
     * @return 첫 조건부 갱신이 바꾼 행 수
     */
    private AtomicInteger interleaveReservationOf(long memberCouponId) {
        AtomicBoolean interleaved = new AtomicBoolean();
        AtomicInteger firstUpdated = new AtomicInteger(-1);
        TransactionTemplate otherOrder = new TransactionTemplate(transactionManager);
        otherOrder.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // 저장소는 인터페이스(JDK 프록시)라 callRealMethod를 쓸 수 없다. spy의 기본 응답(원래 빈에 위임)으로 실제 갱신을 한다
        Answer<?> delegate = mockingDetails(memberCouponRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object updated = delegate.answer(invocation);
            if (interleaved.compareAndSet(false, true)) {
                firstUpdated.set((Integer) updated);
                otherOrder.executeWithoutResult(status -> memberCouponRepository.reserve(memberCouponId, MEMBER_ID, 0,
                        OTHER_ORDER_ITEM_ID, Instant.now()));
            }
            return updated;
        }).when(memberCouponRepository).reserve(anyLong(), anyLong(), anyLong(), anyLong(), any());
        return firstUpdated;
    }

    private long issue(long couponId) {
        Instant now = Instant.now();
        return memberCouponRepository.save(MemberCoupon.issue(MEMBER_ID, couponId, "req-" + couponId, now,
                now.plus(Duration.ofDays(30)), now)).getId();
    }

    private static CouponTerms terms(CouponTargetType targetType, Long lectureId, Long basePrice,
                                     DiscountType discountType, long discountValue) {
        Instant now = Instant.now();
        return new CouponTerms("롤백 확인 쿠폰", targetType, lectureId, basePrice, discountType, discountValue, null, 10,
                now.plusSeconds(60), now.plus(Duration.ofDays(7)), ExpiryType.RELATIVE, 30, null, true);
    }
}

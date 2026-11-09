package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.CLOCK;
import static com.hapbang.coupon.app.CouponUsageFixture.*;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.CouponUsage;
import com.hapbang.coupon.domain.CouponUsageStatus;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.domain.ProcessedOperation;
import com.hapbang.coupon.domain.usage.AppliedCoupon;
import com.hapbang.coupon.domain.usage.UsageItem;
import com.hapbang.coupon.out.CouponUsageRepository;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.coupon.out.MemberCouponState;
import com.hapbang.coupon.out.ProcessedOperationRepository;
import com.hapbang.shared.lecture.event.OrderItemCancelCompletedEvent;
import com.hapbang.shared.lecture.event.OrderItemPaidEvent;

import io.micrometer.core.instrument.MeterRegistry;

// 소비 서비스의 흐름(멱등 확인 → 사용 시도 확인 → 전이·조건부 갱신 → 멱등 기록)과 분기(중복 eventId, 내용 충돌, 순서 역전, 없는 사용 시도,
// 조건부 갱신 0행)를 본다. 전이 규칙 자체는 도메인 테스트가, 조건부 갱신 조건은 저장소 테스트가 본다
class CouponOrderConsumerUseCaseTest {

    private static final long MEMBER_COUPON = 31L;
    private static final long GENERATION = 1L;
    private static final Instant PAID_AT = NOW.minusSeconds(60);
    private static final OrderItemPaidEvent PAID = OrderItemPaidEvent.of(ORDER_ID, ORDER_ITEM_ID, MEMBER_ID,
            List.of(new OrderItemPaidEvent.Usage(MEMBER_COUPON, GENERATION)), PAID_AT);
    private static final OrderItemCancelCompletedEvent CANCELED = OrderItemCancelCompletedEvent.of(ORDER_ID,
            ORDER_ITEM_ID, MEMBER_ID, List.of(new OrderItemCancelCompletedEvent.Usage(MEMBER_COUPON, GENERATION)),
            "lecture-cancel:901", List.of(), NOW.minusSeconds(30));

    private final CouponUsageRepository couponUsageRepository = mock();
    private final MemberCouponRepository memberCouponRepository = mock();
    private final ProcessedOperationRepository processedOperationRepository = mock();
    /** 저장한 멱등 기록. 같은 이벤트를 다시 보낼 때 읽힌다 */
    private final AtomicReference<ProcessedOperation> processed = new AtomicReference<>();

    private CouponOrderConsumerUseCase useCase;
    private CouponUsage usage;

    @BeforeEach
    void setUp() {
        CouponOrderConsumerMetrics metrics = new CouponOrderConsumerMetrics(
                new DefaultListableBeanFactory().getBeanProvider(MeterRegistry.class));
        useCase = new CouponOrderConsumerUseCase(couponUsageRepository, memberCouponRepository,
                processedOperationRepository, metrics, transactionManager(), CLOCK);
        usage = CouponUsage.reserve(MEMBER_ID, ORDER_ID, new UsageItem(ORDER_ITEM_ID, LECTURE_ID, SELLER_ID, PRICE),
                new AppliedCoupon(MEMBER_COUPON, 1L, CouponType.SELLER, 1, PRICE, 1_000, 0, 1_000, 0, 1),
                "lecture-order:900:reserve", NOW);
        when(couponUsageRepository.findByOrderItemIdOrderByMemberCouponIdAscGenerationAsc(ORDER_ITEM_ID))
                .thenReturn(List.of(usage));
        when(processedOperationRepository.findByOperationId(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(processed.get())
                        .filter(found -> found.getOperationId().equals(invocation.getArgument(0))));
        when(processedOperationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            processed.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        when(memberCouponRepository.markUsed(MEMBER_COUPON, GENERATION, NOW)).thenReturn(1);
        when(memberCouponRepository.restore(MEMBER_COUPON, GENERATION, NOW)).thenReturn(1);
    }

    private void givenCoupon(MemberCouponStatus status) {
        when(memberCouponRepository.findStateById(MEMBER_COUPON)).thenReturn(Optional.of(
                new MemberCouponState(MEMBER_COUPON, MEMBER_ID, status, GENERATION, EXPIRES_AT, ORDER_ITEM_ID)));
    }

    @Test
    void 결제_완료는_사용_시도를_PAID로_발급_쿠폰을_USED로_바꾸고_eventId로_멱등_기록을_남긴다() {
        givenCoupon(MemberCouponStatus.RESERVED);

        CouponOrderConsumerUseCase.Result result = useCase.consume(PAID);

        assertThat(result).isEqualTo(CouponOrderConsumerUseCase.Result.APPLIED);
        assertThat(usage.getStatus()).isEqualTo(CouponUsageStatus.PAID);
        verify(memberCouponRepository).markUsed(MEMBER_COUPON, GENERATION, NOW);
        assertThat(processed.get().getOperationId()).isEqualTo("order-item-paid:" + ORDER_ITEM_ID);
        assertThat(processed.get().getResult()).isEqualTo("APPLIED");
    }

    @Test
    void 같은_eventId가_같은_내용으로_다시_오면_아무것도_바꾸지_않는다() {
        givenCoupon(MemberCouponStatus.RESERVED);
        useCase.consume(PAID);
        clearInvocations(memberCouponRepository, couponUsageRepository);

        CouponOrderConsumerUseCase.Result result = useCase.consume(PAID);

        assertThat(result).isEqualTo(CouponOrderConsumerUseCase.Result.DUPLICATE);
        verifyNoInteractions(memberCouponRepository, couponUsageRepository);
    }

    @Test
    void 같은_eventId에_다른_내용이_오면_재시도_없이_격리한다() {
        givenCoupon(MemberCouponStatus.RESERVED);
        useCase.consume(PAID);
        OrderItemPaidEvent otherTime = OrderItemPaidEvent.of(ORDER_ID, ORDER_ITEM_ID, MEMBER_ID, PAID.usages(),
                PAID_AT.plusSeconds(1));

        assertThatThrownBy(() -> useCase.consume(otherTime))
                .isInstanceOf(CouponOrderConsumerIsolationException.class);
    }

    /** 같은 key라도 Modulith 전송 순서가 바뀔 수 있다. 취소 완료가 먼저 오면 쿠폰을 돌리고, 늦은 결제 완료는 사실만 남긴다 */
    @Test
    void 취소_완료가_결제_완료보다_먼저_오면_복원하고_늦은_결제_완료는_상태를_바꾸지_않는다() {
        givenCoupon(MemberCouponStatus.RESERVED);

        assertThat(useCase.consume(CANCELED)).isEqualTo(CouponOrderConsumerUseCase.Result.APPLIED);
        verify(memberCouponRepository).restore(MEMBER_COUPON, GENERATION, NOW);
        assertThat(usage.getStatus()).isEqualTo(CouponUsageStatus.CANCELED);
        assertThat(usage.getRestoredTo()).isEqualTo(MemberCouponStatus.AVAILABLE);

        givenCoupon(MemberCouponStatus.AVAILABLE);
        assertThat(useCase.consume(PAID)).isEqualTo(CouponOrderConsumerUseCase.Result.FACT_ONLY);

        verify(memberCouponRepository, never()).markUsed(anyLong(), anyLong(), any());
        assertThat(usage.getStatus()).isEqualTo(CouponUsageStatus.CANCELED);
        assertThat(usage.getPaidEventId()).isEqualTo(PAID.eventId());
    }

    @Test
    void 주문_항목에_이벤트의_사용_시도가_없거나_회원이_다르면_재시도_없이_격리한다() {
        givenCoupon(MemberCouponStatus.RESERVED);
        OrderItemPaidEvent otherGeneration = OrderItemPaidEvent.of(ORDER_ID, ORDER_ITEM_ID, MEMBER_ID,
                List.of(new OrderItemPaidEvent.Usage(MEMBER_COUPON, GENERATION + 1)), PAID_AT);
        OrderItemPaidEvent otherMember = OrderItemPaidEvent.of(ORDER_ID, ORDER_ITEM_ID, OTHER_MEMBER_ID, PAID.usages(),
                PAID_AT);

        assertThatThrownBy(() -> useCase.consume(otherGeneration))
                .isInstanceOf(CouponOrderConsumerIsolationException.class);
        assertThatThrownBy(() -> useCase.consume(otherMember))
                .isInstanceOf(CouponOrderConsumerIsolationException.class);
        verify(processedOperationRepository, never()).saveAndFlush(any());
    }

    /** 읽은 뒤 발급 쿠폰이 바뀌었으면 격리하지 않고 예외로 되돌려 제한 재시도가 다시 읽게 한다 */
    @Test
    void 발급_쿠폰_조건부_갱신이_0행이면_멱등_기록_없이_재시도할_예외를_던진다() {
        givenCoupon(MemberCouponStatus.RESERVED);
        when(memberCouponRepository.markUsed(MEMBER_COUPON, GENERATION, NOW)).thenReturn(0);

        assertThatThrownBy(() -> useCase.consume(PAID))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(CouponOrderConsumerIsolationException.class);
        verify(processedOperationRepository, never()).saveAndFlush(any());
    }
}

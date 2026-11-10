package com.hapbang.coupon.domain;

import static com.hapbang.coupon.domain.CouponFixture.*;
import static com.hapbang.coupon.domain.CouponUsageStatus.*;
import static com.hapbang.coupon.domain.MemberCouponStatus.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.hapbang.coupon.domain.usage.AppliedCoupon;
import com.hapbang.coupon.domain.usage.UsageItem;

// 주문 결제·취소 완료 이벤트를 사용 시도에 반영하는 전이 표: 결제 → PAID(발급 쿠폰 USED), 취소 → CANCELED(만료 전 AVAILABLE, 만료 시각
// 이후 EXPIRED), 취소·해제 뒤 늦은 결제와 이전 generation은 사실만 남긴다
class CouponUsageEventTest {

    private static final String PAID_EVENT = "order-item-paid:901";
    private static final String CANCEL_EVENT = "order-item-cancel-completed:901";
    private static final Instant PAID_AT = NOW.plusSeconds(10);
    private static final Instant COMPLETED_AT = NOW.plusSeconds(20);
    private static final Instant APPLIED_AT = NOW.plusSeconds(30);
    /** 예약으로 오른 generation(기대 0 + 1) */
    private static final long GENERATION = 1;

    /** 판매자 쿠폰 1,000 HP를 예약한 사용 시도(generation 1) */
    private static CouponUsage reserved() {
        AppliedCoupon applied = new AppliedCoupon(31L, 5L, CouponType.SELLER, 1, 10_000, 1_000, 0, 1_000, 0, 1);
        return CouponUsage.reserve(MEMBER_ID, 900L, new UsageItem(901L, LECTURE_ID, SELLER_ID, 10_000), applied,
                "lecture-order:900:reserve", NOW);
    }

    /** 사용 시도를 그 상태로 만든다. PAID·CANCELED는 이벤트 반영으로, RELEASED는 해제로 만든다 */
    private static CouponUsage inStatus(CouponUsageStatus status) {
        CouponUsage usage = reserved();
        switch (status) {
            case RESERVED -> {
            }
            case PAID -> usage.applyPaid(PAID_EVENT, PAID_AT, MemberCouponStatus.RESERVED, GENERATION, NOW);
            case CANCELED -> usage.applyCancelCompleted(CANCEL_EVENT, COMPLETED_AT, MemberCouponStatus.RESERVED,
                    GENERATION, NOW.plusSeconds(3_600), NOW);
            case RELEASED -> usage.release("lecture-order:900:release", "결제 실패", null, AVAILABLE, NOW);
        }
        assertThat(usage.getStatus()).isEqualTo(status);
        return usage;
    }

    // 결제 완료 -------------------------------------------------------------------------------------------------

    @Test
    void 예약한_시도에_결제_완료가_오면_PAID로_바꾸고_결제_사실을_남긴다() {
        CouponUsage usage = reserved();

        UsageEventOutcome outcome = usage.applyPaid(PAID_EVENT, PAID_AT, MemberCouponStatus.RESERVED, GENERATION,
                APPLIED_AT);

        assertThat(outcome).isEqualTo(UsageEventOutcome.APPLIED);
        assertThat(usage.getStatus()).isEqualTo(PAID);
        assertThat(usage.getPaidEventId()).isEqualTo(PAID_EVENT);
        assertThat(usage.getPaidAt()).isEqualTo(PAID_AT);
        assertThat(usage.getUpdatedAt()).isEqualTo(APPLIED_AT);
    }

    @Test
    void 이미_PAID면_결제_완료를_다시_반영하지_않는다() {
        CouponUsage usage = inStatus(PAID);

        UsageEventOutcome outcome = usage.applyPaid("other", APPLIED_AT, USED, GENERATION, APPLIED_AT);

        assertThat(outcome).isEqualTo(UsageEventOutcome.ALREADY_APPLIED);
        assertThat(usage.getPaidEventId()).isEqualTo(PAID_EVENT);
        assertThat(usage.getPaidAt()).isEqualTo(PAID_AT);
    }

    /** 취소·해제가 먼저 끝났으면 늦은 결제 완료는 결과를 뒤집지 않고 사실만 남긴다 */
    @ParameterizedTest
    @MethodSource("finishedBeforePaid")
    void 취소나_해제_뒤에_온_결제_완료는_사실만_남긴다(CouponUsageStatus status, MemberCouponStatus couponStatus) {
        CouponUsage usage = inStatus(status);

        UsageEventOutcome outcome = usage.applyPaid(PAID_EVENT, PAID_AT, couponStatus, GENERATION, APPLIED_AT);

        assertThat(outcome).isEqualTo(UsageEventOutcome.FACT_ONLY);
        assertThat(usage.getStatus()).isEqualTo(status);
        assertThat(usage.getPaidEventId()).isEqualTo(PAID_EVENT);
        assertThat(usage.getPaidAt()).isEqualTo(PAID_AT);
    }

    static Stream<Arguments> finishedBeforePaid() {
        return Stream.of(Arguments.of(CANCELED, AVAILABLE), Arguments.of(CANCELED, EXPIRED),
                Arguments.of(RELEASED, AVAILABLE));
    }

    /** 발급 쿠폰이 다른 generation이거나 RESERVED가 아니면(이미 다른 시도로 넘어감) 지금 사용 상태를 바꾸지 않는다 */
    @ParameterizedTest
    @MethodSource("notThisReservation")
    void 발급_쿠폰이_이_시도로_예약된_상태가_아니면_결제_완료는_사실만_남긴다(MemberCouponStatus couponStatus,
                                                                long couponGeneration) {
        CouponUsage usage = reserved();

        UsageEventOutcome outcome = usage.applyPaid(PAID_EVENT, PAID_AT, couponStatus, couponGeneration, APPLIED_AT);

        assertThat(outcome).isEqualTo(UsageEventOutcome.FACT_ONLY);
        assertThat(usage.getStatus()).isEqualTo(CouponUsageStatus.RESERVED);
        assertThat(usage.getPaidEventId()).isEqualTo(PAID_EVENT);
    }

    static Stream<Arguments> notThisReservation() {
        return Stream.of(Arguments.of(MemberCouponStatus.RESERVED, GENERATION + 1), Arguments.of(AVAILABLE, GENERATION),
                Arguments.of(USED, GENERATION));
    }

    // 취소·반환 완료 --------------------------------------------------------------------------------------------

    @Test
    void 결제한_시도에_취소_완료가_오면_CANCELED로_바꾸고_만료_전이면_AVAILABLE로_돌린다() {
        CouponUsage usage = inStatus(PAID);

        UsageEventOutcome outcome = usage.applyCancelCompleted(CANCEL_EVENT, COMPLETED_AT, USED, GENERATION,
                APPLIED_AT.plusSeconds(1), APPLIED_AT);

        assertThat(outcome).isEqualTo(UsageEventOutcome.APPLIED);
        assertThat(usage.getStatus()).isEqualTo(CANCELED);
        assertThat(usage.getRestoredTo()).isEqualTo(AVAILABLE);
        assertThat(usage.getRestoredAt()).isEqualTo(APPLIED_AT);
        assertThat(usage.getCancelEventId()).isEqualTo(CANCEL_EVENT);
        assertThat(usage.getCancelCompletedAt()).isEqualTo(COMPLETED_AT);
        // 결제 사실은 그대로 남는다
        assertThat(usage.getPaidEventId()).isEqualTo(PAID_EVENT);
    }

    @Test
    void 만료_시각과_같은_때_취소_완료를_반영하면_EXPIRED로_돌린다() {
        CouponUsage usage = inStatus(PAID);

        usage.applyCancelCompleted(CANCEL_EVENT, COMPLETED_AT, USED, GENERATION, APPLIED_AT, APPLIED_AT);

        assertThat(usage.getStatus()).isEqualTo(CANCELED);
        assertThat(usage.getRestoredTo()).isEqualTo(EXPIRED);
    }

    /** 결제 완료보다 먼저 온 취소 완료(순서 역전)는 예약 중인 시도를 바로 취소하고 발급 쿠폰을 돌린다 */
    @Test
    void 결제_완료보다_먼저_온_취소_완료는_예약한_시도를_CANCELED로_바꾼다() {
        CouponUsage usage = reserved();

        UsageEventOutcome outcome = usage.applyCancelCompleted(CANCEL_EVENT, COMPLETED_AT, MemberCouponStatus.RESERVED,
                GENERATION, NOW.plusSeconds(3_600), APPLIED_AT);

        assertThat(outcome).isEqualTo(UsageEventOutcome.APPLIED);
        assertThat(usage.getStatus()).isEqualTo(CANCELED);
        assertThat(usage.getRestoredTo()).isEqualTo(AVAILABLE);
        assertThat(usage.getPaidEventId()).isNull();
    }

    @Test
    void 이미_CANCELED면_취소_완료를_다시_반영하지_않는다() {
        CouponUsage usage = inStatus(CANCELED);
        Instant restoredAt = usage.getRestoredAt();

        UsageEventOutcome outcome = usage.applyCancelCompleted("other", APPLIED_AT, AVAILABLE, GENERATION, APPLIED_AT,
                APPLIED_AT);

        assertThat(outcome).isEqualTo(UsageEventOutcome.ALREADY_APPLIED);
        assertThat(usage.getCancelEventId()).isEqualTo(CANCEL_EVENT);
        assertThat(usage.getRestoredAt()).isEqualTo(restoredAt);
    }

    @Test
    void 해제한_시도에_온_취소_완료는_사실만_남긴다() {
        CouponUsage usage = inStatus(RELEASED);

        UsageEventOutcome outcome = usage.applyCancelCompleted(CANCEL_EVENT, COMPLETED_AT, AVAILABLE, GENERATION,
                NOW.plusSeconds(3_600), APPLIED_AT);

        assertThat(outcome).isEqualTo(UsageEventOutcome.FACT_ONLY);
        assertThat(usage.getStatus()).isEqualTo(RELEASED);
        assertThat(usage.getCancelEventId()).isEqualTo(CANCEL_EVENT);
        assertThat(usage.getRestoredTo()).isNull();
    }

    /** 이전 generation의 취소 완료: 발급 쿠폰이 이미 다음 시도로 예약·사용 중이면 지금 사용 상태를 바꾸지 않는다 */
    @Test
    void 발급_쿠폰이_다른_generation이면_취소_완료는_사실만_남긴다() {
        CouponUsage usage = inStatus(PAID);

        UsageEventOutcome outcome = usage.applyCancelCompleted(CANCEL_EVENT, COMPLETED_AT, MemberCouponStatus.RESERVED,
                GENERATION + 1, NOW.plusSeconds(3_600), APPLIED_AT);

        assertThat(outcome).isEqualTo(UsageEventOutcome.FACT_ONLY);
        assertThat(usage.getStatus()).isEqualTo(PAID);
        assertThat(usage.getRestoredTo()).isNull();
        assertThat(usage.getCancelEventId()).isEqualTo(CANCEL_EVENT);
    }
}

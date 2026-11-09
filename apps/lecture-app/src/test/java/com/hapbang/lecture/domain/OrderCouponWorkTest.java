package com.hapbang.lecture.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

// 주문의 쿠폰 예약·해제 작업 상태 전이
class OrderCouponWorkTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 10, 0);
    private static final Duration RETRY_AFTER = Duration.ofSeconds(30);

    @Test
    void 예약_작업은_주문마다_정해진_작업_ID로_PENDING에서_시작한다() {
        OrderCouponWork work = OrderCouponWork.reserve(7L, NOW);

        assertThat(work.getWorkId()).isEqualTo("lecture-order:7:reserve");
        assertThat(work.getKind()).isEqualTo(OrderCouponWorkKind.RESERVE);
        assertThat(work.getStatus()).isEqualTo(OrderCouponWorkStatus.PENDING);
        assertThat(work.getAttemptCount()).isZero();
        assertThat(work.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(OrderCouponWork.release(7L, NOW).getWorkId()).isEqualTo("lecture-order:7:release");
    }

    @Test
    void 시도를_기록하면_횟수가_오르고_다음_시도_시각이_재시도_간격만큼_밀린다() {
        OrderCouponWork work = OrderCouponWork.reserve(7L, NOW);

        work.startAttempt(NOW, RETRY_AFTER);
        work.startAttempt(NOW.plusSeconds(30), RETRY_AFTER);

        assertThat(work.getAttemptCount()).isEqualTo(2);
        assertThat(work.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(work.hasAttemptsLeft(3)).isTrue();
        assertThat(work.hasAttemptsLeft(2)).isFalse();
    }

    @Test
    void 예약_성공이_다시_와도_그대로_두고_다른_결과로는_바꾸지_않는다() {
        OrderCouponWork work = OrderCouponWork.reserve(7L, NOW);

        work.markReserved(NOW);
        work.markReserved(NOW.plusSeconds(1));

        assertThat(work.getStatus()).isEqualTo(OrderCouponWorkStatus.RESERVED);
        assertThat(work.getUpdatedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> work.markRejected("COUPON_NOT_AVAILABLE", NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> work.startAttempt(NOW, RETRY_AFTER)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 예약_거절은_거절_코드를_남기고_다시_와도_처음_코드를_둔다() {
        OrderCouponWork work = OrderCouponWork.reserve(7L, NOW);

        work.markRejected("COUPON_NOT_AVAILABLE", NOW);
        work.markRejected("COUPON_EXPIRED", NOW);

        assertThat(work.getStatus()).isEqualTo(OrderCouponWorkStatus.REJECTED);
        assertThat(work.getRejectCode()).isEqualTo("COUPON_NOT_AVAILABLE");
        assertThatThrownBy(() -> work.markReserved(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 해제_작업은_해제_성공으로만_끝나고_예약_결과로는_끝나지_않는다() {
        OrderCouponWork work = OrderCouponWork.release(7L, NOW);

        assertThatThrownBy(() -> work.markReserved(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> work.markRejected("X", NOW)).isInstanceOf(IllegalStateException.class);
        work.markReleased(NOW);

        assertThat(work.getStatus()).isEqualTo(OrderCouponWorkStatus.RELEASED);
        assertThatThrownBy(() -> OrderCouponWork.reserve(7L, NOW).markReleased(NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 시도_상한에_닿아_멈춘_작업은_다시_보내거나_결과를_받지_않는다() {
        OrderCouponWork work = OrderCouponWork.reserve(7L, NOW);

        work.markDead(NOW);

        assertThat(work.getStatus()).isEqualTo(OrderCouponWorkStatus.DEAD);
        assertThat(work.isPending()).isFalse();
        assertThatThrownBy(() -> work.startAttempt(NOW, RETRY_AFTER)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> work.markReserved(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> work.markDead(NOW)).isInstanceOf(IllegalStateException.class);
    }
}

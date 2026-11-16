package com.hapbang.auxiliary.notification.in.event.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

/** 리스너의 검증 변환(ND-181). 메시지를 만드는 비용은 검증에 실패했을 때만 낸다(N14 PR 리뷰, 강의 리스너) */
class RejectedEventsTest {

    private final AtomicInteger messageBuilt = new AtomicInteger();
    private final Supplier<String> message = () -> {
        messageBuilt.incrementAndGet();
        return "알림을 만들 수 없는 강의 이벤트입니다: LectureOrderPaidEvent";
    };

    @Test
    void 변환에_성공하면_메시지를_만들지_않고_값을_돌려준다() {
        String converted = RejectedEvents.orReject(message, () -> "초안");

        assertThat(converted).isEqualTo("초안");
        assertThat(messageBuilt).hasValue(0);
    }

    @Test
    void 값_검증에_실패하면_만든_메시지와_원인으로_재시도_없는_예외를_던진다() {
        IllegalArgumentException invalid = new IllegalArgumentException("주문 항목이 비었다");

        assertThatThrownBy(() -> RejectedEvents.orReject(message, () -> {
            throw invalid;
        }))
                .isExactlyInstanceOf(RejectedEventException.class)
                .hasMessage("알림을 만들 수 없는 강의 이벤트입니다: LectureOrderPaidEvent")
                .hasCause(invalid);
        assertThat(messageBuilt).hasValue(1);
    }

    @Test
    void 검증이_아닌_오류는_바꾸지_않고_메시지도_만들지_않는다() {
        IllegalStateException storeFailure = new IllegalStateException("DB 장애");

        assertThatThrownBy(() -> RejectedEvents.orReject(message, () -> {
            throw storeFailure;
        })).isSameAs(storeFailure);
        assertThat(messageBuilt).hasValue(0);
    }
}

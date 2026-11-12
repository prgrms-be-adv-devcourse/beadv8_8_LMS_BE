package com.hapbang.auxiliary.notification.in.event;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.notification.app.NotificationAlertService;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.in.event.kafka.RejectedEventException;
import com.hapbang.shared.billing.event.ChargeFailedEvent;
import com.hapbang.shared.billing.event.ExchangeApprovedEvent;
import com.hapbang.shared.billing.event.RefundFailedEvent;
import com.hapbang.shared.billing.event.RefundManualReviewRequiredEvent;

/**
 * 필수 값이 빈 N7 지갑 이벤트(#907)는 재시도 없이 DLT로 가도록 {@link RejectedEventException}으로 바꾸고 저장하지 않는다
 * (오류 처리기의 재시도 제외 타입, 출연·강의 리스너와 같은 방식).
 */
class NotificationBillingEventListenerTest {

    private static final Instant AT = Instant.parse("2026-11-12T01:00:00Z");

    private final NotificationAlertService alerts = mock(NotificationAlertService.class);
    private final NotificationBillingEventListener listener = new NotificationBillingEventListener(alerts,
            NotificationKafkaConsumerSettings.of("billing.events.v1", "group"));

    @Test
    void 필수_값이_빈_지갑_4종은_재시도하지_않는_예외로_바꾼다() {
        assertThatThrownBy(() -> listener.on(new ChargeFailedEvent(null, 1L, 1L, 1L, AT)))
                .isInstanceOf(RejectedEventException.class);
        assertThatThrownBy(() -> listener.on(new RefundFailedEvent(1L, null, 1L, AT)))
                .isInstanceOf(RejectedEventException.class);
        assertThatThrownBy(() -> listener.on(new RefundManualReviewRequiredEvent(1L, 1L, 1L, null)))
                .isInstanceOf(RejectedEventException.class);
        assertThatThrownBy(() -> listener.on(new ExchangeApprovedEvent(1L, 1L, 1L, null, AT)))
                .isInstanceOf(RejectedEventException.class);
        verifyNoInteractions(alerts);
    }
}

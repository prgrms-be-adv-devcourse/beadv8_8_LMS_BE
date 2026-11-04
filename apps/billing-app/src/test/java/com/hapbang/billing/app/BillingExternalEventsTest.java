package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.billing.event.ChargeCompletedEvent;
import com.hapbang.shared.billing.event.ExchangeCompletedEvent;
import com.hapbang.shared.billing.event.ExchangeFinalFailedEvent;
import com.hapbang.shared.billing.event.ExchangePayoutFailedEvent;
import com.hapbang.shared.billing.event.ExchangeRejectedEvent;
import com.hapbang.shared.billing.event.PayoutCompletedEvent;
import com.hapbang.shared.billing.event.RefundCompletedEvent;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

// Kafka로 내보내는 지갑 이벤트 판단 (#497). shared 지갑 이벤트 중 @Externalized가 붙은 것만 내보낸다.
class BillingExternalEventsTest {

    @Test
    @DisplayName("지갑 이벤트 7개는 모두 내보낸다")
    void externalized() {
        assertThat(List.of(ChargeCompletedEvent.class, PayoutCompletedEvent.class, RefundCompletedEvent.class,
                ExchangeCompletedEvent.class, ExchangeRejectedEvent.class, ExchangePayoutFailedEvent.class,
                ExchangeFinalFailedEvent.class))
                .allMatch(BillingExternalEvents::isExternalizedType);
    }

    @Test
    @DisplayName("다른 컨텍스트의 shared 이벤트(회원 이벤트)는 @Externalized가 붙어 있어도 지갑이 내보내지 않는다 (패키지로 거름)")
    void otherContextEventsNotExternalized() {
        // 애노테이션만으로는 걸러지지 않는 경우여야 패키지 조건을 확인한다
        assertThat(UserJoinedEvent.class.isAnnotationPresent(Externalized.class)).isTrue();
        assertThat(BillingExternalEvents.isExternalizedType(UserJoinedEvent.class)).isFalse();
        assertThat(BillingExternalEvents.isExternalizedType(UserModifiedEvent.class)).isFalse();
    }
}

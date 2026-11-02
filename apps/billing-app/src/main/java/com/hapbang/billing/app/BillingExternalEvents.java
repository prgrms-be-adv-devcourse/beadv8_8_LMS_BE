package com.hapbang.billing.app;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.billing.event.BillingEventTopic;

// Kafka로 내보내는 지갑 이벤트인지 판단한다 (#497). 외부화 설정과 재발행 배치가 같이 쓴다.
// shared 지갑 이벤트(com.hapbang.shared.billing.event) 중 @Externalized가 붙은 레코드만 내보낸다. 토픽·key도 그 애노테이션이 정한다.
// 다른 컨텍스트의 shared 이벤트(회원 이벤트 등)에도 @Externalized가 붙지만, 지갑은 그것을 받기만 하므로 내보내지 않는다.
public final class BillingExternalEvents {

    private static final String PACKAGE = BillingEventTopic.class.getPackageName();

    private BillingExternalEvents() {
    }

    public static boolean isExternalized(Object event) {
        return isExternalizedType(event.getClass());
    }

    static boolean isExternalizedType(Class<?> type) {
        return type.getPackageName().equals(PACKAGE) && type.isAnnotationPresent(Externalized.class);
    }
}

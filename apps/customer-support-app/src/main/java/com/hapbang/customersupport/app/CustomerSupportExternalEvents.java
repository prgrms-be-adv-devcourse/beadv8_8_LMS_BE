package com.hapbang.customersupport.app;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.customersupport.event.CustomerSupportEventTopic;

/**
 * Kafka로 내보내는 고객센터 이벤트인지 판단한다 (#279). 외부화 설정과 재발행 배치가 같이 쓴다.
 * shared 고객센터 이벤트({@code com.hapbang.shared.customersupport.event}) 중 {@link Externalized}가 붙은 레코드만 내보낸다.
 * 다른 컨텍스트의 shared 이벤트(강의 이벤트)는 받기만 하므로 내보내지 않는다.
 */
public final class CustomerSupportExternalEvents {

    private static final String PACKAGE = CustomerSupportEventTopic.class.getPackageName();

    private CustomerSupportExternalEvents() {
    }

    public static boolean isExternalized(Object event) {
        Class<?> type = event.getClass();
        return type.getPackageName().equals(PACKAGE) && type.isAnnotationPresent(Externalized.class);
    }
}

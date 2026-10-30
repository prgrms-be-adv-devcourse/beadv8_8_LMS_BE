package com.hapbang.appearance.out.event;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;

import com.hapbang.shared.appearance.event.AppearanceApplicationSubmittedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;

/**
 * 출연 알림 이벤트를 Kafka로 내보낸다 (Modulith 이벤트 외부화, #393).
 * <ul>
 *     <li>대상은 요청 도착({@link AppearanceRequestSentEvent})·지원 도착({@link AppearanceApplicationSubmittedEvent}) 두 가지다.
 *     출연 취소 요청 이벤트 3종은 받는 곳이 없어 같은 JVM 안에만 둔다.</li>
 *     <li>발행 코드는 그대로다. 업무 트랜잭션 안에서 발행한 이벤트가 Modulith 발행 기록({@code event_publication})에 남고,
 *     커밋 뒤에 Kafka로 보낸다. 롤백되면 보내지 않고, 보내기에 실패하면 기록이 남아 서버 재시작 때 다시 보낸다.</li>
 *     <li>토픽은 {@value #TOPIC}, 키는 알림 받는 회원 ID(요청 도착 = 게스트, 지원 도착 = 호스트)다.</li>
 *     <li>본문은 shared 이벤트 레코드를 그대로 JSON으로 싣는다(회원 이벤트와 같은 방식). 봉투 정보는 헤더에 둔다:
 *     {@value #EVENT_ID}, {@value #EVENT_TYPE}, {@value #EVENT_VERSION}.</li>
 *     <li>{@value #EVENT_ID}는 이벤트 종류와 요청서·지원 ID로 만든다. 같은 이벤트를 다시 보내도 같은 값이라 받는 쪽이 중복을 거를 수 있다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class AppearanceNotificationExternalizationConfig {

    public static final String TOPIC = "appearance.notification.v1";

    public static final String EVENT_ID = "eventId";
    public static final String EVENT_TYPE = "eventType";
    public static final String EVENT_VERSION = "eventVersion";

    /** 본문 형식 버전. 필드를 추가만 하면 그대로 두고, 받는 쪽이 못 읽게 바뀌면 올린다 */
    static final String VERSION = "1";

    @Bean
    EventExternalizationConfiguration appearanceNotificationExternalization() {
        return EventExternalizationConfiguration.externalizing()
                .select(event -> event instanceof AppearanceRequestSentEvent
                        || event instanceof AppearanceApplicationSubmittedEvent)
                .route(AppearanceRequestSentEvent.class,
                        event -> RoutingTarget.forTarget(TOPIC).andKey(String.valueOf(event.request().guestId())))
                .route(AppearanceApplicationSubmittedEvent.class,
                        event -> RoutingTarget.forTarget(TOPIC).andKey(String.valueOf(event.application().hostId())))
                .headers(AppearanceRequestSentEvent.class,
                        event -> headers(AppearanceRequestSentEvent.class, event.request().requestId()))
                .headers(AppearanceApplicationSubmittedEvent.class,
                        event -> headers(AppearanceApplicationSubmittedEvent.class, event.application().applicationId()))
                .build();
    }

    /** 이벤트 종류와 요청서·지원 ID로 정해지는 이벤트 ID. 요청 도착·지원 도착은 요청서·지원 하나에 한 번만 발행된다 */
    public static String eventId(Class<?> eventType, Long referenceId) {
        String name = eventType.getSimpleName() + ":" + referenceId;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static Map<String, Object> headers(Class<?> eventType, Long referenceId) {
        return Map.of(
                EVENT_ID, eventId(eventType, referenceId),
                EVENT_TYPE, eventType.getSimpleName(),
                EVENT_VERSION, VERSION);
    }
}

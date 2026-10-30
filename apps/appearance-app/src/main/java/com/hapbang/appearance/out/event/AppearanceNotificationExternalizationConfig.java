package com.hapbang.appearance.out.event;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;

import com.hapbang.appearance.app.AppearanceExternalEvents;
import com.hapbang.shared.appearance.event.AppearanceApplicationSubmittedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;

/**
 * 출연 알림 이벤트를 Kafka로 내보낸다 (Modulith 이벤트 외부화, #393).
 * <ul>
 *     <li>대상은 {@link AppearanceExternalEvents}가 정한다(요청 도착·지원 도착).</li>
 *     <li>발행 코드는 그대로다. 업무 트랜잭션 안에서 발행한 이벤트가 Modulith 발행 기록({@code event_publication})에 남고,
 *     커밋 뒤에 Kafka로 보낸다. 롤백되면 보내지 않고, 보내기에 실패하면 기록이 남아 재발행 배치
 *     ({@code AppearanceNotificationRepublishUseCase})와 서버 재시작 때 다시 보낸다.</li>
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

    /** {@value #EVENT_TYPE} 헤더 값. 레코드 이름을 바꿔도 그대로 둔다(받는 쪽 계약, 이벤트 ID 재료) */
    public static final String REQUEST_SENT = "AppearanceRequestSentEvent";
    public static final String APPLICATION_SUBMITTED = "AppearanceApplicationSubmittedEvent";

    /** 본문 형식 버전. 필드를 추가만 하면 그대로 두고, 받는 쪽이 못 읽게 바뀌면 올린다 */
    static final String VERSION = "1";

    @Bean
    EventExternalizationConfiguration appearanceNotificationExternalization() {
        return EventExternalizationConfiguration.externalizing()
                .select(AppearanceExternalEvents::isExternalized)
                .route(AppearanceRequestSentEvent.class,
                        event -> RoutingTarget.forTarget(TOPIC).andKey(String.valueOf(event.request().guestId())))
                .route(AppearanceApplicationSubmittedEvent.class,
                        event -> RoutingTarget.forTarget(TOPIC).andKey(String.valueOf(event.application().hostId())))
                .headers(AppearanceRequestSentEvent.class,
                        event -> headers(REQUEST_SENT, event.request().requestId()))
                .headers(AppearanceApplicationSubmittedEvent.class,
                        event -> headers(APPLICATION_SUBMITTED, event.application().applicationId()))
                .build();
    }

    /** 이벤트 종류({@link #REQUEST_SENT}·{@link #APPLICATION_SUBMITTED})와 요청서·지원 ID로 정해지는 이벤트 ID.
     * 요청 도착·지원 도착은 요청서·지원 하나에 한 번만 발행된다 */
    public static String eventId(String eventType, Long referenceId) {
        String name = eventType + ":" + referenceId;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static Map<String, Object> headers(String eventType, Long referenceId) {
        return Map.of(
                EVENT_ID, eventId(eventType, referenceId),
                EVENT_TYPE, eventType,
                EVENT_VERSION, VERSION);
    }
}

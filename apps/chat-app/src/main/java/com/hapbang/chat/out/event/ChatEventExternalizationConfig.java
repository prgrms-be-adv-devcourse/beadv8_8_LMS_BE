package com.hapbang.chat.out.event;

import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.modulith.events.EventExternalizationConfiguration;

import com.hapbang.chat.app.ChatExternalEvents;
import com.hapbang.shared.chat.event.ChatEventTopic;
import com.hapbang.shared.chat.event.GroupMemberKickedEvent;
import com.hapbang.shared.chat.event.GroupMembersInvitedEvent;
import com.hapbang.shared.chat.event.GroupRoomClosedEvent;

/**
 * 채팅 이벤트를 Kafka로 내보낸다(Modulith 이벤트 외부화). 규칙은 docs/architecture/infrastructure/kafka.md "발행"을 따른다.
 * <ul>
 *     <li>대상은 {@link ChatExternalEvents}가 정한다(shared 채팅 이벤트 중 {@code @Externalized}가 붙은 레코드뿐). 받기만 하는
 *     다른 컨텍스트의 shared 이벤트(회원·출연)는 내보내지 않는다. 토픽({@value ChatEventTopic#NAME})과 key(방 ID)는 레코드의
 *     {@code @Externalized}가 정하고, 이 설정은 헤더만 더한다.</li>
 *     <li>단체방 초대·강퇴·폐쇄 트랜잭션 안에서 발행한 이벤트가 발행 기록({@code event_publication})에 남고 커밋 뒤에 Kafka로 간다.
 *     롤백되면 보내지 않는다. 보내지 못한 기록은 재발행 배치({@code ChatEventRepublisher}, 매분)와 서버 재시작 때 다시 보낸다.</li>
 *     <li>이 발행 기록은 방 안 메시지를 전달하는 채팅 outbox(Redis Streams로 적재)와 다른 것이다. 방 안 안내·전달 작업은 그대로다.</li>
 *     <li>헤더는 {@value #EVENT_TYPE}·{@value #EVENT_VERSION} 두 개다. 이벤트 ID는 두지 않고 받는 쪽은 사건 ID로 중복을 거른다.</li>
 *     <li>토픽은 채팅 앱이 선언한다(브로커 자동 생성은 꺼져 있다). 외부화를 끈 테스트에서는 선언하지 않는다.</li>
 *     <li>보내는 템플릿은 {@link ChatEventKafkaConfig}가 코드로 만든다.</li>
 * </ul>
 * 내보낼 이벤트를 더하면 shared 레코드에 {@code @Externalized}를 붙이고 여기에 {@value #EVENT_TYPE} 헤더를 더한다.
 */
@Configuration(proxyBeanMethods = false)
public class ChatEventExternalizationConfig {

    public static final String EVENT_TYPE = "eventType";
    public static final String EVENT_VERSION = "eventVersion";

    /** {@value #EVENT_TYPE} 헤더 값. 레코드 이름을 바꿔도 그대로 둔다(받는 쪽 계약) */
    public static final String GROUP_MEMBERS_INVITED = "GroupMembersInvitedEvent";
    public static final String GROUP_MEMBER_KICKED = "GroupMemberKickedEvent";
    public static final String GROUP_ROOM_CLOSED = "GroupRoomClosedEvent";

    /** 본문 형식 버전. 선택 필드를 추가만 하면 그대로 두고, 깨지는 변경은 새 레코드 타입으로 한다(kafka.md 이벤트 버전) */
    static final String VERSION = "1";

    @Bean
    EventExternalizationConfiguration chatEventExternalization() {
        return EventExternalizationConfiguration.externalizing()
                .select(ChatExternalEvents::isExternalized)
                .headers(GroupMembersInvitedEvent.class, event -> headers(GROUP_MEMBERS_INVITED))
                .headers(GroupMemberKickedEvent.class, event -> headers(GROUP_MEMBER_KICKED))
                .headers(GroupRoomClosedEvent.class, event -> headers(GROUP_ROOM_CLOSED))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "spring.modulith.events.externalization.enabled", matchIfMissing = true)
    NewTopic chatEventTopic() {
        return TopicBuilder.name(ChatEventTopic.NAME).partitions(ChatEventKafkaConstants.TOPIC_PARTITIONS)
                .replicas(ChatEventKafkaConstants.TOPIC_REPLICAS).build();
    }

    private static Map<String, Object> headers(String eventType) {
        return Map.of(EVENT_TYPE, eventType, EVENT_VERSION, VERSION);
    }
}

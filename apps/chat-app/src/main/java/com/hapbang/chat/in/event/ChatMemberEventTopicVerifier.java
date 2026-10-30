package com.hapbang.chat.in.event;

import org.apache.kafka.clients.admin.TopicDescription;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 앱이 뜬 뒤 회원 이벤트 DLT가 있는지, 원본 토픽과 파티션 수가 같은지 확인해 로그로 알린다.
 * <p>
 * DLT 생성 실패는 앱 시작을 막지 않는다(fail-safe). 회원 이벤트 소비가 멈추면 가입·변경 반영이 늦어질 뿐이지만, 앱이 뜨지 않으면
 * 채팅 전체가 멈추기 때문이다. 대신 DLT가 없으면 실패 레코드를 DLT로 보내지 못해 offset을 커밋하지 않고 다시 처리하므로
 * (브로커가 토픽을 자동 생성하지 않는 한) 그 파티션의 처리가 멈춘다는 점을 ERROR로 남긴다.
 * 확인은 시작을 늦추지 않도록 별도 스레드에서 한다.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty(name = ChatMemberEventProperties.ENABLED_PROPERTY, matchIfMissing = true)
@RequiredArgsConstructor
public class ChatMemberEventTopicVerifier {

    public enum Result {
        /** DLT가 있고 원본과 파티션 수가 같다 */
        OK,
        /** DLT가 없거나 확인하지 못했다 */
        DEAD_LETTER_UNAVAILABLE,
        /** 원본 토픽이 아직 없거나 확인하지 못해 파티션 수를 비교하지 못했다 */
        SOURCE_UNAVAILABLE,
        /** DLT와 원본의 파티션 수가 다르다 */
        PARTITION_MISMATCH
    }

    private final KafkaAdmin kafkaAdmin;
    private final ChatMemberEventProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    void verifyInBackground() {
        Thread.ofVirtual().name("chat-member-event-topic-check").start(this::verify);
    }

    public Result verify() {
        Integer deadLetter = partitions(properties.deadLetterTopic());
        if (deadLetter == null) {
            log.error("회원 이벤트 DLT {}를 확인하지 못했습니다. 만들지 못했거나 브로커에 연결하지 못했습니다. "
                            + "DLT가 없으면 실패한 회원 이벤트를 DLT로 보내지 못해 offset을 커밋하지 않고 같은 레코드를 다시 처리하므로 "
                            + "(브로커 자동 생성이 꺼져 있으면) 그 파티션의 소비가 멈춥니다. 토픽을 만들고(파티션 수 = 원본 {}) 확인하세요.",
                    properties.deadLetterTopic(), properties.topic());
            return Result.DEAD_LETTER_UNAVAILABLE;
        }
        Integer source = partitions(properties.topic());
        if (source == null) {
            log.warn("회원 이벤트 토픽 {}를 확인하지 못해 DLT {}와 파티션 수를 비교하지 못했습니다(DLT 파티션 {}). "
                            + "원본이 만들어진 뒤 hapbang.chat.member-events.dead-letter-partitions가 원본과 같은지 확인하세요.",
                    properties.topic(), properties.deadLetterTopic(), deadLetter);
            return Result.SOURCE_UNAVAILABLE;
        }
        if (!source.equals(deadLetter)) {
            log.warn("회원 이벤트 DLT {}의 파티션 수({})가 원본 {}({})와 다릅니다. DLT는 회원 ID key로 파티션을 정하므로 발행은 되지만, "
                            + "원본과 같게 맞추세요(hapbang.chat.member-events.dead-letter-partitions).",
                    properties.deadLetterTopic(), deadLetter, properties.topic(), source);
            return Result.PARTITION_MISMATCH;
        }
        log.info("회원 이벤트 토픽 {}와 DLT {}를 확인했습니다(파티션 {}).", properties.topic(), properties.deadLetterTopic(),
                source);
        return Result.OK;
    }

    private Integer partitions(String topic) {
        try {
            TopicDescription description = kafkaAdmin.describeTopics(topic).get(topic);
            return description == null ? null : description.partitions().size();
        } catch (RuntimeException e) {
            log.debug("토픽 {}를 확인하지 못했습니다", topic, e);
            return null;
        }
    }
}

package com.hapbang.lecture.in;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.hapbang.lecture.app.LectureObjectionFacade;
import com.hapbang.lecture.config.LectureKafkaConfig;
import com.hapbang.shared.customersupport.event.CustomerSupportEventTopic;
import com.hapbang.shared.customersupport.event.LectureObjectionResolutionRequestedEvent;
import com.hapbang.shared.customersupport.event.LectureObjectionWithdrawalRequestedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 고객센터가 Kafka로 보낸 이의 제기 판단·철회 요청을 받아 항목에 반영한다 (#278, D60 (2)(3)·D71).
 * <ul>
 *     <li>{@code customersupport.events.v1}에는 고객센터 이벤트 타입이 섞여 온다. 받을 타입마다 {@link KafkaHandler}를 두고,
 *     모르는 타입은 무시하고 넘어간다(ADR 0001).</li>
 *     <li>중복·재전달은 명령 ID로 거른다({@code LectureObjectionCommandUseCase}). 거절은 결과 이벤트로 나가므로 실패가 아니다.</li>
 *     <li>낙관적 락 충돌 같은 기술 실패는 예외로 재시도하고, 계속 실패하면 그룹별 DLT로 간다({@link LectureKafkaConfig}).</li>
 * </ul>
 * 반영과 결과 이벤트 기록이 커밋된 뒤 리스너가 끝나야 offset이 커밋된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(
        topics = CustomerSupportEventTopic.NAME,
        groupId = LectureKafkaConfig.OBJECTION_COMMAND_GROUP,
        properties = {
                "value.deserializer=org.springframework.kafka.support.serializer.ErrorHandlingDeserializer",
                "spring.deserializer.value.delegate.class=org.springframework.kafka.support.serializer.JacksonJsonDeserializer",
                // 하위 패키지까지 자동으로 허용된다고 가정하지 않고 실제 이벤트 패키지만 믿는다.
                "spring.json.trusted.packages=com.hapbang.shared.customersupport.event",
                "auto.offset.reset=earliest",
                // 토픽은 발행 앱이 partition 3개로 선언한다. 소비자가 먼저 붙어도 partition 1개짜리 토픽을 만들지 않게 한다
                // (운영 broker는 자동 생성이 꺼져 있다. 자동 생성이 켜진 테스트 broker에서 다른 테스트의 토픽을 망가뜨리지 않게 한다)
                "allow.auto.create.topics=false"
        })
public class LectureObjectionRequestListener {

    private final LectureObjectionFacade lectureObjectionFacade;

    @KafkaHandler
    public void on(LectureObjectionResolutionRequestedEvent event) {
        lectureObjectionFacade.resolve(event);
    }

    @KafkaHandler
    public void on(LectureObjectionWithdrawalRequestedEvent event) {
        lectureObjectionFacade.withdraw(event);
    }

    /** 강의가 쓰지 않는 고객센터 이벤트. 토픽에 타입이 섞이므로 실패로 보내지 않고 넘어간다. */
    @KafkaHandler(isDefault = true)
    public void ignore(Object event) {
        log.debug("강의가 쓰지 않는 고객센터 이벤트라 무시한다 type={}", event.getClass().getSimpleName());
    }
}

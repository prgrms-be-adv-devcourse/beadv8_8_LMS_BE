package com.hapbang.customersupport.in;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.hapbang.customersupport.app.LectureObjectionResultUseCase;
import com.hapbang.customersupport.global.config.CustomerSupportKafkaConfig;
import com.hapbang.shared.lecture.event.LectureEventTopic;
import com.hapbang.shared.lecture.event.LectureObjectionRequestRefusedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnActionRequiredEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnCompletedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionWithdrawnEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 강의가 Kafka로 보낸 이의 제기 결과·반환 이벤트를 받아 문의에 기록한다 (#279, D60 (4)(5)·D71).
 * <ul>
 *     <li>{@code lecture.events.v1}에는 강의 이벤트 타입이 섞여 온다. 받을 타입마다 {@link KafkaHandler}를 두고,
 *     모르는 타입은 무시하고 넘어간다(ADR 0001).</li>
 *     <li>어떤 타입으로 읽을지는 {@code eventType} 헤더로 정한다({@link LectureEventTypeResolver}, #722). 받을 타입을 더하면 그 표에도
 *     더한다. 모르는 타입은 클래스를 찾지 않고 {@link IgnoredLectureEvent}로 읽어, native 이미지에 없는 클래스여도 DLT로 가지 않는다.</li>
 *     <li>중복·늦은 결과는 문의의 연동 상태와 명령 ID로 거르고, HP 반환 상태는 앞으로만 바꾼다({@link LectureObjectionResultUseCase}).</li>
 *     <li>기술 실패는 예외로 재시도하고, 계속 실패하면 그룹별 DLT로 간다({@link CustomerSupportKafkaConfig}).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(
        topics = LectureEventTopic.NAME,
        groupId = CustomerSupportKafkaConfig.OBJECTION_RESULT_GROUP,
        properties = {
                "value.deserializer=org.springframework.kafka.support.serializer.ErrorHandlingDeserializer",
                "spring.deserializer.value.delegate.class=org.springframework.kafka.support.serializer.JacksonJsonDeserializer",
                // 하위 패키지까지 자동으로 허용된다고 가정하지 않고 실제 이벤트 패키지만 믿는다.
                "spring.json.trusted.packages=com.hapbang.shared.lecture.event",
                // eventType 헤더로 타입을 정한다. 헤더가 없을 때만 __TypeId__로 찾는다(#722)
                "spring.json.value.type.method=com.hapbang.customersupport.in.LectureEventTypeResolver.resolve",
                "auto.offset.reset=earliest",
                // 토픽은 강의 앱이 partition 3개로 선언한다. 소비자가 먼저 붙어도 partition 1개짜리 토픽을 만들지 않게 한다
                "allow.auto.create.topics=false"
        })
public class CustomerSupportLectureEventListener {

    private final LectureObjectionResultUseCase lectureObjectionResultUseCase;

    @KafkaHandler
    public void on(LectureObjectionResolvedEvent event) {
        lectureObjectionResultUseCase.apply(event);
        logHandled(event.inquiryId(), event.orderItemId());
    }

    @KafkaHandler
    public void on(LectureObjectionWithdrawnEvent event) {
        lectureObjectionResultUseCase.apply(event);
        logHandled(event.inquiryId(), event.orderItemId());
    }

    @KafkaHandler
    public void on(LectureObjectionRequestRefusedEvent event) {
        lectureObjectionResultUseCase.apply(event);
        logHandled(event.inquiryId(), event.orderItemId());
    }

    @KafkaHandler
    public void on(LectureObjectionReturnCompletedEvent event) {
        lectureObjectionResultUseCase.apply(event);
        logHandled(event.inquiryId(), event.orderItemId());
    }

    @KafkaHandler
    public void on(LectureObjectionReturnActionRequiredEvent event) {
        lectureObjectionResultUseCase.apply(event);
        logHandled(event.inquiryId(), event.orderItemId());
    }

    // 이벤트 종류는 MDC eventType으로 붙는다(#582)
    private static void logHandled(Long inquiryId, Long orderItemId) {
        log.info("강의 이의 제기 결과 이벤트를 처리했다 inquiryId={}, orderItemId={}", inquiryId, orderItemId);
    }

    /**
     * 고객센터가 쓰지 않는 강의 이벤트({@link LectureEventTypeResolver}가 고른다). 토픽에 타입이 섞이므로 실패로 보내지 않고 넘어간다.
     * 처리기 파라미터라 Spring AOT가 native 이미지에 이 레코드를 등록한다. 이벤트 종류는 MDC eventType으로 붙는다(#582)
     */
    @KafkaHandler
    public void ignore(IgnoredLectureEvent event) {
        log.debug("고객센터가 쓰지 않는 강의 이벤트라 무시한다");
    }

    /** eventType 헤더 없이 {@code __TypeId__}로 읽은, 처리기가 없는 강의 이벤트. 위와 같이 넘어간다. */
    @KafkaHandler(isDefault = true)
    public void ignore(Object event) {
        log.debug("고객센터가 쓰지 않는 강의 이벤트라 무시한다 type={}", event.getClass().getSimpleName());
    }
}

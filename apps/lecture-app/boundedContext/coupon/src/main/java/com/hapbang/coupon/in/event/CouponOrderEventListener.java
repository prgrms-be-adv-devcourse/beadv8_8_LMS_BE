package com.hapbang.coupon.in.event;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.hapbang.coupon.app.CouponOrderConsumerUseCase;
import com.hapbang.coupon.config.CouponOrderConsumerKafkaConfig;
import com.hapbang.shared.lecture.event.LectureEventTopic;
import com.hapbang.shared.lecture.event.OrderItemCancelCompletedEvent;
import com.hapbang.shared.lecture.event.OrderItemPaidEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 강의 이벤트 토픽({@code lecture.events.v1})의 주문 항목 결제 완료·취소 완료를 받아 쿠폰 사용 상태에 반영한다
 * (그룹 {@code lecture-app.coupon-order}).
 * <ul>
 *     <li>토픽에는 강의 이벤트 타입이 섞인다. {@code eventType} 헤더가 두 이벤트가 아니면 본문을 읽지 않고 무시한다
 *     ({@link CouponOrderEventTypeResolver}).</li>
 *     <li>중복·재발행·순서 역전은 소비 UseCase가 거른다({@link CouponOrderConsumerUseCase}). 반영 트랜잭션이 커밋된 뒤 리스너가 끝나야
 *     offset이 커밋된다. 같은 주문 항목의 이벤트는 key({@code order-item:<주문 항목 ID>})가 같아 한 partition에서 차례로 처리한다.</li>
 *     <li>실패는 예외로 던진다. 쿠폰 전용 오류 처리기가 제한 재시도 뒤 격리 기록을 남기고 DLT로 보낸다
 *     ({@link CouponOrderConsumerKafkaConfig}). 형식 오류·없는 사용 시도·회원·주문 불일치·내용 충돌은 재시도 없이 바로 격리한다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(
        id = CouponOrderConsumerKafkaConfig.LISTENER_ID,
        topics = LectureEventTopic.NAME,
        groupId = CouponOrderConsumerKafkaConfig.GROUP,
        containerFactory = CouponOrderConsumerKafkaConfig.CONTAINER_FACTORY,
        properties = {
                "value.deserializer=org.springframework.kafka.support.serializer.ErrorHandlingDeserializer",
                "spring.deserializer.value.delegate.class=org.springframework.kafka.support.serializer.JacksonJsonDeserializer",
                // 하위 패키지까지 자동으로 허용된다고 가정하지 않고 실제 이벤트 패키지만 믿는다
                "spring.json.trusted.packages=com.hapbang.shared.lecture.event",
                // eventType 헤더로 타입을 정한다. 헤더가 없을 때만 __TypeId__로 찾는다
                "spring.json.value.type.method=com.hapbang.coupon.in.event.CouponOrderEventTypeResolver.resolve",
                "auto.offset.reset=earliest",
                // 토픽은 강의가 partition 3개로 선언한다. 소비자가 먼저 붙어도 partition 1개짜리 토픽을 만들지 않게 한다
                "allow.auto.create.topics=false"
        })
public class CouponOrderEventListener {

    private final CouponOrderConsumerUseCase couponOrderConsumerUseCase;

    @KafkaHandler
    public void on(OrderItemPaidEvent event) {
        couponOrderConsumerUseCase.consume(event);
    }

    @KafkaHandler
    public void on(OrderItemCancelCompletedEvent event) {
        couponOrderConsumerUseCase.consume(event);
    }

    /** 이 소비자가 쓰지 않는 강의 이벤트. 토픽에 타입이 섞이므로 실패로 보내지 않고 넘어간다 */
    @KafkaHandler(isDefault = true)
    public void ignore(Object event) {
        log.debug("쿠폰 주문 소비자가 쓰지 않는 강의 이벤트라 무시한다 eventType={}", event.getClass().getSimpleName());
    }
}

package com.hapbang.coupon.in.event;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.hapbang.coupon.app.CouponIssueConsumerUseCase;
import com.hapbang.coupon.config.CouponIssueConsumerKafkaConfig;
import com.hapbang.shared.coupon.event.CouponEventTopic;
import com.hapbang.shared.coupon.event.CouponIssueRequested;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 쿠폰 이벤트 토픽({@code coupon.events.v1})의 발급 요청을 받아 DB에 확정한다(그룹 {@code lecture-app.coupon-issue}).
 * <ul>
 *     <li>토픽에는 쿠폰 이벤트 타입이 섞일 수 있다(ADR 0001). 받을 타입마다 {@link KafkaHandler}를 두고 모르는 타입은 무시한다.</li>
 *     <li>중복·재발행은 소비 멱등이 거른다({@link CouponIssueConsumerUseCase}). 확정 트랜잭션이 커밋된 뒤 리스너가 끝나야 offset이
 *     커밋된다.</li>
 *     <li>실패는 예외로 던진다. 쿠폰 전용 오류 처리기가 제한 재시도 뒤 격리 기록을 남기고 DLT로 보낸다
 *     ({@link CouponIssueConsumerKafkaConfig}). 형식 오류·없는 쿠폰·epoch 불일치·내용 충돌은 재시도 없이 바로 격리한다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(
        id = CouponIssueConsumerKafkaConfig.LISTENER_ID,
        topics = CouponEventTopic.NAME,
        groupId = CouponIssueConsumerKafkaConfig.GROUP,
        containerFactory = CouponIssueConsumerKafkaConfig.CONTAINER_FACTORY,
        properties = {
                "value.deserializer=org.springframework.kafka.support.serializer.ErrorHandlingDeserializer",
                "spring.deserializer.value.delegate.class=org.springframework.kafka.support.serializer.JacksonJsonDeserializer",
                // 하위 패키지까지 자동으로 허용된다고 가정하지 않고 실제 이벤트 패키지만 믿는다
                "spring.json.trusted.packages=com.hapbang.shared.coupon.event",
                "auto.offset.reset=earliest",
                // 토픽은 발행 쪽 선언(partition 3개)으로 만든다. 소비자가 먼저 붙어도 partition 1개짜리 토픽을 만들지 않게 한다
                "allow.auto.create.topics=false"
        })
public class CouponIssueEventListener {

    private final CouponIssueConsumerUseCase couponIssueConsumerUseCase;

    @KafkaHandler
    public void on(CouponIssueRequested event) {
        couponIssueConsumerUseCase.consume(event);
    }

    /** 이 소비자가 쓰지 않는 쿠폰 이벤트. 토픽에 타입이 섞이므로 실패로 보내지 않고 넘어간다 */
    @KafkaHandler(isDefault = true)
    public void ignore(Object event) {
        log.debug("발급 확정 소비자가 쓰지 않는 쿠폰 이벤트라 무시한다 eventType={}", event.getClass().getSimpleName());
    }
}

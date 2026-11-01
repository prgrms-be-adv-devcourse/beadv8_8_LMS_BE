package com.hapbang.billing.in;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.hapbang.billing.app.BillingUserSyncUseCase;
import com.hapbang.billing.global.config.BillingKafkaConfig;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 회원 앱이 Kafka(member.events.v1)로 보낸 가입·변경 이벤트로 Billing 회원 복제본을 동기화한다 (#294, #430).
// 소비 그룹은 billing-app.member-replica다. 지갑 생성(UserJoinedEventListener, billing-app.wallet-creation)과 그룹이 달라
// 복제본 반영이 실패해도 지갑 생성은 영향을 받지 않고, 실패한 쪽만 재시도 뒤 자기 그룹 DLT로 간다(BD-123).
// 관리자도 복제한다(지갑과 달리). 멱등은 회원 원본 버전 비교다: 같거나 낮은 버전은 무시하고, 가입보다 변경이 먼저 와도 복제본을 만든다
// (BD-103). 반영은 BillingUserSyncUseCase가 짧은 트랜잭션으로 하고, 그 커밋 뒤 리스너가 끝나야 offset이 커밋된다.
// 토픽에는 회원 이벤트 타입이 섞여 온다(ADR 0001). 받을 타입마다 @KafkaHandler를 두고 모르는 타입은 무시한다.
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(
        topics = BillingKafkaConfig.MEMBER_EVENTS_TOPIC,
        groupId = BillingKafkaConfig.MEMBER_REPLICA_GROUP,
        properties = {
                "value.deserializer=org.springframework.kafka.support.serializer.ErrorHandlingDeserializer",
                "spring.deserializer.value.delegate.class=org.springframework.kafka.support.serializer.JacksonJsonDeserializer",
                // 하위 패키지까지 자동으로 허용된다고 가정하지 않고 실제 이벤트 패키지만 믿는다.
                "spring.json.trusted.packages=com.hapbang.shared.member.event",
                "auto.offset.reset=earliest"
        })
public class BillingMemberEventListener {

    private final BillingUserSyncUseCase billingUserSyncUseCase;

    @KafkaHandler
    public void on(UserJoinedEvent event) {
        billingUserSyncUseCase.apply(event.user());
    }

    @KafkaHandler
    public void on(UserModifiedEvent event) {
        billingUserSyncUseCase.apply(event.user());
    }

    // 복제본에 쓰지 않는 회원 이벤트(예: 로그아웃). 토픽에 타입이 섞이므로 실패로 보내지 않고 넘어간다.
    @KafkaHandler(isDefault = true)
    public void ignore(Object event) {
        log.debug("회원 복제본이 쓰지 않는 회원 이벤트라 무시한다 type={}", event.getClass().getSimpleName());
    }
}

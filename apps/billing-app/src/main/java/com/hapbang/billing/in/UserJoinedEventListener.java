package com.hapbang.billing.in;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.hapbang.billing.app.WalletFacade;
import com.hapbang.billing.global.config.BillingKafkaConfig;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 회원 앱이 Kafka(member.events.v1)로 보낸 가입 이벤트를 받아 관리자를 제외한 회원의 지갑을 만든다 (#42, #430).
// 소비 그룹은 billing-app.wallet-creation이다(복제본 갱신과 분리, BD-123). 멱등은 회원 ID UNIQUE(uk_wallet_user_id)다:
// 다시 받거나 지갑 조회가 먼저 만들었으면 기존 지갑을 그대로 둔다. 지갑 저장 커밋 뒤 리스너가 끝나야 offset이 커밋된다.
// 가입 말고 다른 회원 이벤트(변경·로그아웃 등)는 무시한다.
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(
        topics = BillingKafkaConfig.MEMBER_EVENTS_TOPIC,
        groupId = BillingKafkaConfig.WALLET_CREATION_GROUP,
        properties = {
                "value.deserializer=org.springframework.kafka.support.serializer.ErrorHandlingDeserializer",
                "spring.deserializer.value.delegate.class=org.springframework.kafka.support.serializer.JacksonJsonDeserializer",
                // 하위 패키지까지 자동으로 허용된다고 가정하지 않고 실제 이벤트 패키지만 믿는다.
                "spring.json.trusted.packages=com.hapbang.shared.member.event",
                "auto.offset.reset=earliest"
        })
public class UserJoinedEventListener {

    private final WalletFacade walletFacade;

    @KafkaHandler
    public void on(UserJoinedEvent event) {
        UserDto user = event.user();
        // 관리자는 HP를 쓰지 않으므로 지갑을 만들지 않는다 (BD-3).
        if (user.role() == Role.ADMIN) {
            return;
        }
        // 재전달되었거나 지갑 조회가 먼저 만들었으면 기존 지갑을 그대로 쓴다 (회원당 지갑 1개).
        walletFacade.getOrCreateWallet(user.id());
    }

    // 지갑 생성에 쓰지 않는 회원 이벤트(변경·로그아웃 등). 토픽에 타입이 섞이므로 실패로 보내지 않고 넘어간다.
    @KafkaHandler(isDefault = true)
    public void ignore(Object event) {
        log.debug("지갑 생성이 쓰지 않는 회원 이벤트라 무시한다 type={}", event.getClass().getSimpleName());
    }
}

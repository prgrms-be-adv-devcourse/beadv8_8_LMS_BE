package com.hapbang.chat.in.websocket;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.dto.ChatSubscriptionSignalEvent;
import com.hapbang.chat.app.port.LocalChatSender;
import com.hapbang.chat.app.port.LocalChatSender.RoomTarget;

import lombok.RequiredArgsConstructor;

/**
 * 방 topic 구독 거절·회수 이벤트를 받아 그 구독에 프레임을 쓴다. 비동기·트랜잭션 이벤트 리스너가 아니라서 이벤트를 낸 스레드에서
 * 바로 돈다(거절 프레임은 구독 허가가 돌아오기 전에, 회수 프레임은 구독을 뺀 직후에 쓰인다). 프레임은 다른 방 topic 프레임과 같은
 * 송신기({@link LocalChatSender})로 쓰고, 전달 실패는 송신기가 기록만 하고 알리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ChatSubscriptionSignalListener {

    private final LocalChatSender localChatSender;

    @EventListener
    public void onSignal(ChatSubscriptionSignalEvent event) {
        localChatSender.sendSubscriptionSignal(event.roomId(),
                new RoomTarget(event.sessionId(), event.subscriptionId(), event.participationVersion()),
                event.signal(), event.reason(), event.currentParticipationVersion());
    }
}
